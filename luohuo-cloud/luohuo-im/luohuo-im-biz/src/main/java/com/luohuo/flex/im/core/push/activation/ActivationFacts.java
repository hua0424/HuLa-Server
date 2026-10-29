package com.luohuo.flex.im.core.push.activation;

import java.util.Map;

/**
 * 运行时事实（aichatoverview#487 T6）：进程实际加载了什么、生效配置是什么、当前处在哪个作用域。
 *
 * <p>与 {@link ActivationQualification}（人工授权材料）成对使用：资格材料声明"打算启用什么"，
 * 事实声明"实际跑起来是什么"。两者必须逐项相等才 READY。
 *
 * <p>刻意只有数据、没有行为——判定规则全部集中在 {@link PushActivationGate}，这样部署脚本的
 * preflight 与启动路径可以共享同一份规则而不必各自实现一遍。
 *
 * @param environment   运行环境标识（如 test / prod），与资格材料的作用域比对
 * @param backendKey    后端标识；同一环境下的不同后端不能互相顶替批准
 * @param tenantScope   租户作用域；资格材料批准的是哪些租户
 * @param cohort        实例/分组标识；候选 cohort 之外的实例不得激活
 * @param artifacts     实际制品摘要（服务名 → sha256），与本进程实际加载的字节一致
 * @param sourceSha     实际源码 SHA（来自部署 manifest，非版本字符串推断）
 * @param configDigest  生效配置的规范化摘要，见 {@link #configDigestOf}
 * @param capacityModel 实际选用的调度模型标识（如 {@code bounded-per-node}）
 * @param budgets       实际生效的预算数值（键 → 数值），与资格材料声明逐一比对
 * @param protocol      协议/存储兼容能力：T1 ACK、T2 窗口回执、T3 当前读、T4 来源与授权
 * @param rollbackRecoverable 回退版本 R 的制品当前确实可取回
 */
public record ActivationFacts(
		String environment,
		String backendKey,
		String tenantScope,
		String cohort,
		Map<String, String> artifacts,
		String sourceSha,
		String configDigest,
		String capacityModel,
		Map<String, Long> budgets,
		ProtocolSupport protocol,
		boolean rollbackRecoverable) {

	/**
	 * 协议/存储兼容能力。任一为 false 时，声明了该能力的资格材料不得激活：
	 * 缺 T1 则逐目标反馈失真，缺 T2/T3 则恢复合同不成立，缺 T4 则撤权保护失效。
	 */
	public record ProtocolSupport(boolean t1Ack, boolean t2WindowReceipt, boolean t3CurrentRead, boolean t4SourceGrant) {

		public boolean allSupported() {
			return t1Ack && t2WindowReceipt && t3CurrentRead && t4SourceGrant;
		}
	}

	/**
	 * 生效配置的规范化摘要：只取能改变新策略行为的那几个键，按字典序拼成 {@code key=value} 行再算 sha256。
	 * 这样"配置文件里看起来一样、实际生效值不同"（Nacos 覆盖、环境变量、profile 差异）会被摘要差异暴露出来，
	 * 而不是靠人眼比对多个来源。
	 */
	public static String configDigestOf(Map<String, ?> effective) {
		StringBuilder sb = new StringBuilder();
		effective.keySet().stream().sorted().forEach(key -> {
			Object value = effective.get(key);
			sb.append(key).append('=').append(value == null ? "" : value).append('\n');
		});
		return PushActivationGate.sha256Hex(sb.toString());
	}
}
