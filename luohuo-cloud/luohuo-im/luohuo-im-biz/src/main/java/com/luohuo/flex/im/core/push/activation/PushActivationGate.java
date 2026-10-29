package com.luohuo.flex.im.core.push.activation;

import cn.hutool.crypto.digest.DigestUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 新策略启用门（aichatoverview#487 T6）：把"人工授权材料 + 运行时事实"判定成 READY 或 BLOCKED。
 *
 * <p>这是<b>唯一</b>的判定规则来源。部署 preflight 与进程启动路径都调这一个方法，
 * 因此不存在"部署脚本放行、进程又自己判断一遍"的分叉——{@code PushActivationGateTest}
 * 与 {@code deploy-server-contracts.sh} 分别从两侧钉住同一组期望。
 *
 * <p>刻意的设计约束：
 * <ul>
 *   <li><b>没有 force / unsafe / ignore-errors 选项</b>。只有 READY 才能启用；任何绕过路径都必须
 *       新增代码，而不是新增一个配置键。</li>
 *   <li><b>未知即拒绝</b>：未知 schema、缺字段、坏格式、空事实一律 BLOCKED 并给出原因码，
 *       不沿着宽松默认值继续。</li>
 *   <li><b>不猜</b>：运行时预算必须被冻结额度覆盖（实际 ≤ 冻结），而不是"配置里有一个数就放行"。</li>
 * </ul>
 */
public final class PushActivationGate {

	/** 判定结果：只有 {@link #READY} 允许启用新策略。 */
	public enum Outcome {
		READY,
		BLOCKED
	}

	/**
	 * 判定结论。{@code reasons} 为稳定排序的原因码集合，可直接写日志/证据而不重排；
	 * {@code detail} 保留人读说明，不含凭据与正文。
	 */
	public record Verdict(Outcome outcome, List<String> reasons, String detail) {

		public boolean ready() {
			return outcome == Outcome.READY;
		}

		public static Verdict ready(String detail) {
			return new Verdict(Outcome.READY, List.of(), detail);
		}

		public static Verdict blocked(List<String> reasons, String detail) {
			return new Verdict(Outcome.BLOCKED, List.copyOf(reasons), detail);
		}
	}

	private PushActivationGate() {
	}

	/**
	 * 判定能否启用新策略。
	 *
	 * @param qualification 人工授权的资格材料；{@code null} 表示没有授权
	 * @param facts         运行时实际事实；{@code null} 表示拿不到事实（同样必须拒绝）
	 */
	public static Verdict evaluateActivation(ActivationQualification qualification, ActivationFacts facts) {
		if (qualification == null) {
			return Verdict.blocked(List.of("qualification_absent"), "没有启用资格材料，新策略保持未启用");
		}
		if (facts == null) {
			return Verdict.blocked(List.of("facts_absent"), "无法取得运行时事实，不能证明候选已就位");
		}
		Set<String> reasons = new LinkedHashSet<>(qualification.materialGaps());
		compareScope(reasons, qualification, facts);
		compareArtifacts(reasons, qualification, facts);
		compareConfig(reasons, qualification, facts);
		compareBudgets(reasons, qualification, facts);
		compareProtocol(reasons, qualification, facts);
		compareRollback(reasons, qualification, facts);
		if (reasons.isEmpty()) {
			return Verdict.ready("资格材料与运行时事实一致，候选 " + qualification.sourceSha() + " 可启用");
		}
		List<String> ordered = new ArrayList<>(reasons);
		ordered.sort(String::compareTo);
		return Verdict.blocked(ordered, "候选未获启用资格，保持未启用：" + String.join(",", ordered));
	}

	private static void compareScope(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		// 作用域不能跨环境/后端/租户/实例组复制：测试环境的批准搬到别处必须被拒。
		scopeGap(reasons, "scope.environment_mismatch", q.environment(), f.environment());
		scopeGap(reasons, "scope.backend_mismatch", q.backendKey(), f.backendKey());
		scopeGap(reasons, "scope.tenant_mismatch", q.tenantScope(), f.tenantScope());
		// cohort 必须实际匹配：候选组之外的实例不得激活。
		scopeGap(reasons, "scope.cohort_mismatch", q.cohort(), f.cohort());
		// 源码 SHA：资格声明的源码必须就是进程实际来自的源码。
		if (!Objects.equals(q.sourceSha(), f.sourceSha())) {
			reasons.add("version.source_sha_mismatch");
		}
	}

	private static void scopeGap(Set<String> reasons, String code, String expected, String actual) {
		if (!ActivationQualification.notBlank(expected) || !Objects.equals(expected, actual)) {
			reasons.add(code);
		}
	}

	private static void compareArtifacts(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		Map<String, String> expected = q.artifacts();
		Map<String, String> actual = f.artifacts();
		if (expected == null || expected.isEmpty()) {
			reasons.add("artifact.none_declared");
			return;
		}
		if (actual == null || actual.isEmpty()) {
			reasons.add("artifact.facts_missing");
			return;
		}
		// 声明了哪些服务就必须逐个对上；缺一个都说明进程里跑的不是被批准的字节。
		expected.forEach((service, digest) -> {
			String live = actual.get(service);
			if (!ActivationQualification.notBlank(live)) {
				reasons.add("artifact.missing:" + service);
			} else if (!ActivationQualification.isSha64(digest) || !digest.equals(live)) {
				reasons.add("artifact.digest_mismatch:" + service);
			}
		});
		if (!actual.keySet().containsAll(expected.keySet())) {
			return;
		}
		// 多出来的可承载服务若改了行会也必须被批准，否则等于把未批准的服务带进候选。
		actual.keySet().stream().filter(service -> !expected.containsKey(service))
				.forEach(service -> reasons.add("artifact.unapproved:" + service));
	}

	private static void compareConfig(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		if (!ActivationQualification.notBlank(q.configDigest()) || !ActivationQualification.notBlank(f.configDigest())) {
			reasons.add("config.digest_missing");
			return;
		}
		if (!q.configDigest().equals(f.configDigest())) {
			// 生效配置与获批配置不同——包括 Nacos 覆盖、profile 差异造成的"看起来一样"。
			reasons.add("config.digest_mismatch");
		}
	}

	private static void compareBudgets(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		Map<String, Long> frozen = q.frozenBudgetValues();
		Map<String, Long> actual = f.budgets();
		if (frozen.isEmpty()) {
			reasons.add("budget.none_frozen");
			return;
		}
		if (actual == null || actual.isEmpty()) {
			reasons.add("budget.facts_missing");
			return;
		}
		Map<String, ActivationQualification.D7Budget> declared = q.budgets();
		frozen.forEach((key, value) -> {
			Long live = actual.get(key);
			ActivationQualification.D7Budget budget = declared.get(key);
			if (live == null) {
				reasons.add("budget.missing:" + key);
			} else if (budget == null) {
				// 声明缺失已由 materialGaps 报 qualification.d7_incomplete，这里只补一种，
				// 免得同一个缺口报两条。
				reasons.add("budget.undeclared:" + key);
			} else if (budget.complete() && live > value) {
				// 运行时比冻结值更宽 = 实际承载超出被测范围，不能启用。
				// 不完整的 D7 记录已由 materialGaps 报 qualification.d7_incomplete:<key>，这里不重复。
				reasons.add("budget.exceeds_frozen:" + key);
			} else if (budget.complete() && live <= 0) {
				reasons.add("budget.non_positive:" + key);
			}
		});
		actual.keySet().stream().filter(key -> !frozen.containsKey(key))
				.forEach(key -> reasons.add("budget.unfrozen:" + key));
	}

	private static void compareProtocol(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		if (q.protocol() == null || !q.protocol().allSupported()) {
			// 已在 materialGaps 里报过，这里不重复；仍记一条便于单看事实侧。
			reasons.add("protocol.qualification_unsupported");
		}
		if (f.protocol() == null || !f.protocol().allSupported()) {
			// 事实侧缺协议：T1 反馈/T2·T3 恢复/T4 授权缺任一项都不得启用。
			reasons.add("protocol.runtime_unsupported");
		}
	}

	private static void compareRollback(Set<String> reasons, ActivationQualification q, ActivationFacts f) {
		if (q.rollback() == null || !q.rollback().usable()) {
			reasons.add("rollback.qualification_missing");
		}
		if (!f.rollbackRecoverable()) {
			// 回退制品取不回来 = 没有可执行回滚，按 #487 AC5 必须停止启用相关策略。
			reasons.add("rollback.unavailable");
		}
	}

	/** sha256 十六进制摘要；供配置摘要与证据指纹使用。 */
	public static String sha256Hex(String value) {
		return DigestUtil.sha256Hex(value == null ? "" : value);
	}
}
