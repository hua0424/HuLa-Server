package com.luohuo.flex.im.core.push.activation;

import com.luohuo.flex.im.core.push.activation.ActivationFacts.ProtocolSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新策略启用资格材料（aichatoverview#487 T6）：由获授权发布人员提供的、可版本化的 sidecar，
 * 声明"哪个精确候选在哪个作用域内被批准启用，以及凭什么批准"。
 *
 * <p>设计要点（对应 #487 设计 v1 §3.1）：
 * <ul>
 *   <li><b>源码 SHA 与制品摘要各自记录，互不替代</b>——源码对得上不代表构建出的字节对得上。</li>
 *   <li><b>D7 数值是结构化数据而非 {@code testPassed:true}</b>——没有单位、观察窗口与测量依据的
 *       数字不算证据，因此 {@link D7Budget} 要求每项带完了这些才构造得出来。</li>
 *   <li><b>回退 R 是精确 tuple 而非 "previous tag"</b>——任意旧版本不是回退方案。</li>
 *   <li><b>作用域不能跨环境复制</b>：{@code environment}/{@code backendKey}/{@code tenantScope}/{@code cohort}
 *       全部参与比对，把测试环境的批准搬到生产会直接在作用域上被拒。</li>
 * </ul>
 *
 * <p>本类不做任何判定，判定在 {@link PushActivationGate}。
 */
public record ActivationQualification(
		String schema,
		String purpose,
		String authorizedBy,
		String environment,
		String backendKey,
		String tenantScope,
		String cohort,
		Map<String, String> artifacts,
		String sourceSha,
		String configDigest,
		String capacityModel,
		Map<String, D7Budget> budgets,
		ProtocolSupport protocol,
		Evidence evidence,
		RollbackTarget rollback,
		ConditionalFeatures conditional) {

	/** 当前资格材料 schema；未知 schema 一律 BLOCKED，不尝试宽松解析。 */
	public static final String SCHEMA = "push-activation-v1";

	/**
	 * D7 冻结数值：一个数只有同时带上单位、定义、负载/故障前提、观察窗口与测量依据才算证据。
	 * 缺任何一项构造不出来——这正是"数值缺失如实作为验收缺口"在代码里的落点。
	 */
	public record D7Budget(long value, String unit, String definition, String workload, String observeWindow,
			String measuredFrom) {

		public boolean complete() {
			return value > 0 && notBlank(unit) && notBlank(definition) && notBlank(workload)
					&& notBlank(observeWindow) && notBlank(measuredFrom);
		}

		/** 与运行时实际上限比对：运行时不得超过被冻结的额度。 */
		public boolean covers(long actual) {
			return complete() && actual > 0 && actual <= value;
		}
	}

	/**
	 * 证据索引：责任 Issue 与独立证据位置。空字符串视为缺失——"没有证据"不能与"证据通过"等价。
	 */
	public record Evidence(String responsibleIssue, String d7FrozenVersion, String verification,
			String activationDrill) {

		public boolean complete() {
			return notBlank(responsibleIssue) && notBlank(d7FrozenVersion) && notBlank(verification)
					&& notBlank(activationDrill);
		}
	}

	/**
	 * 合格回退目标 R：精确代码 + 制品 + 配置 tuple，且必须声明状态兼容与演练证据。
	 * {@code sourceSha}/{@code artifacts}/{@code configDigest} 任一缺失即"没有可宣称合格的回退"。
	 */
	public record RollbackTarget(String sourceSha, Map<String, String> artifacts, String configDigest,
			boolean stateCompatible, String drillEvidence) {

		public boolean usable() {
			return notBlank(sourceSha) && artifacts != null && !artifacts.isEmpty() && notBlank(configDigest)
					&& stateCompatible && notBlank(drillEvidence);
		}
	}

	/**
	 * 条件功能：展示省略/合并必须默认关闭。AC06 要求"未启用时没有未经批准的降级"，
	 * 因此这里只允许显式声明为关闭；声明为开启但未附证据同样 BLOCKED。
	 */
	public record ConditionalFeatures(boolean omissionEnabled, boolean coalescingEnabled, String evidence) {

		public boolean defaultOff() {
			return !omissionEnabled && !coalescingEnabled;
		}
	}

	public boolean schemaKnown() {
		return SCHEMA.equals(schema);
	}

	/**
	 * 逐项收集缺失/不完整的资格材料。返回空列表表示"材料层面看起来齐全"，
	 * 是否真的可以启用仍要由 {@link PushActivationGate} 与实际事实比对后决定。
	 */
	public List<String> materialGaps() {
		List<String> gaps = new ArrayList<>();
		if (!schemaKnown()) {
			gaps.add("qualification.schema_unknown:" + schema);
		}
		requireText(gaps, "qualification.purpose", purpose);
		requireText(gaps, "qualification.authorized_by", authorizedBy);
		requireText(gaps, "qualification.environment", environment);
		requireText(gaps, "qualification.backend", backendKey);
		requireText(gaps, "qualification.tenant_scope", tenantScope);
		requireText(gaps, "qualification.cohort", cohort);
		requireText(gaps, "qualification.source_sha", sourceSha);
		if (!isSha40(sourceSha)) {
			gaps.add("qualification.source_sha_malformed");
		}
		requireText(gaps, "qualification.config_digest", configDigest);
		requireText(gaps, "qualification.capacity_model", capacityModel);
		if (artifacts == null || artifacts.isEmpty()) {
			gaps.add("qualification.artifacts_missing");
		} else {
			artifacts.forEach((service, digest) -> {
				if (!notBlank(service) || !isSha64(digest)) {
					gaps.add("qualification.artifact_malformed:" + service);
				}
			});
		}
		if (protocol == null || !protocol.allSupported()) {
			gaps.add("qualification.protocol_unsupported:" + protocolGap(protocol));
		}
		if (budgets == null || budgets.isEmpty()) {
			gaps.add("qualification.d7_missing");
		} else {
			budgets.forEach((key, budget) -> {
				if (budget == null || !budget.complete()) {
					gaps.add("qualification.d7_incomplete:" + key);
				}
			});
		}
		if (evidence == null || !evidence.complete()) {
			gaps.add("qualification.evidence_incomplete");
		}
		if (rollback == null || !rollback.usable()) {
			gaps.add("qualification.rollback_missing");
		}
		if (conditional == null || !conditional.defaultOff()) {
			gaps.add("qualification.conditional_not_default_off");
		}
		return gaps;
	}

	/** 冻结额度表：键 → 冻结值，用于与运行时实际预算逐一比对。 */
	public Map<String, Long> frozenBudgetValues() {
		Map<String, Long> values = new LinkedHashMap<>();
		if (budgets != null) {
			budgets.forEach((key, budget) -> {
				if (budget != null) {
					values.put(key, budget.value());
				}
			});
		}
		return values;
	}

	private static String protocolGap(ProtocolSupport support) {
		if (support == null) {
			return "none";
		}
		List<String> missing = new ArrayList<>();
		if (!support.t1Ack()) {
			missing.add("t1_ack");
		}
		if (!support.t2WindowReceipt()) {
			missing.add("t2_window_receipt");
		}
		if (!support.t3CurrentRead()) {
			missing.add("t3_current_read");
		}
		if (!support.t4SourceGrant()) {
			missing.add("t4_source_grant");
		}
		return String.join("+", missing);
	}

	private static void requireText(List<String> gaps, String what, String value) {
		if (!notBlank(value)) {
			gaps.add(what + "_missing");
		}
	}

	static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	static boolean isSha40(String value) {
		return value != null && value.matches("[0-9a-f]{40}");
	}

	static boolean isSha64(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	/** 只用于测试与日志的可读摘要；不含任何凭据或正文。 */
	public Map<String, Object> summary() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("schema", schema);
		out.put("environment", environment);
		out.put("backend", backendKey);
		out.put("tenantScope", tenantScope);
		out.put("cohort", cohort);
		out.put("sourceSha", sourceSha);
		out.put("capacityModel", capacityModel);
		out.put("artifacts", artifacts == null ? Map.of() : Map.copyOf(artifacts));
		out.put("budgets", frozenBudgetValues());
		out.put("rollbackSha", rollback == null ? null : rollback.sourceSha());
		return out;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ActivationQualification other && Objects.equals(summary(), other.summary());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(summary());
	}
}
