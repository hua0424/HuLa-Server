package com.luohuo.flex.im.core.push.activation;

import com.luohuo.flex.im.core.push.activation.ActivationFacts.ProtocolSupport;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.ConditionalFeatures;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.D7Budget;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.Evidence;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.RollbackTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #487 T6 启用门的可运行行为检查：一份完整资格 READY，以及逐项抽掉必要条件后必须 BLOCKED。
 *
 * <p>这些用例就是票面 AC2「文档警告或一个始终允许的开关不算门禁完成」的证据——
 * 每条否定用例都对应一个真实会发生的未就绪组合（缺 T1 迁移、缺恢复、缺授权、D7 未冻结、
 * 验证未通过、回滚不可用、旧访问未排除、跨环境复制批准）。
 */
class PushActivationGateTest {

	private static final String IM_SHA = "a".repeat(64);
	private static final String WS_SHA = "b".repeat(64);
	private static final String SOURCE_SHA = "c".repeat(40);
	private static final String ROLLBACK_SHA = "d".repeat(40);
	private static final String CONFIG_DIGEST = "e".repeat(64);

	// ---------- 完整资格：唯一允许启用的一行 ----------

	@Test
	@DisplayName("完整资格材料与匹配事实 → READY")
	void completeQualificationWithMatchingFactsIsReady() {
		PushActivationGate.Verdict verdict = PushActivationGate.evaluateActivation(qualification(), facts());
		assertEquals(PushActivationGate.Outcome.READY, verdict.outcome(), () -> "unexpected reasons: " + verdict.reasons());
		assertTrue(verdict.reasons().isEmpty());
	}

	@Test
	@DisplayName("没有资格材料 → BLOCKED（默认不启用）")
	void absentQualificationIsBlocked() {
		PushActivationGate.Verdict verdict = PushActivationGate.evaluateActivation(null, facts());
		assertBlocked(verdict, "qualification_absent");
	}

	@Test
	@DisplayName("拿不到运行时事实 → BLOCKED（不能凭空相信已就位）")
	void absentFactsAreBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), null), "facts_absent");
	}

	// ---------- 否定变体：逐项抽掉必要条件 ----------

	@Test
	@DisplayName("缺 T1 逐目标反馈协议 → BLOCKED")
	void missingT1AcknowledgementIsBlocked() {
		PushActivationGate.Verdict verdict = PushActivationGate.evaluateActivation(
				qualification(), factsWithProtocol(new ProtocolSupport(false, true, true, true)));
		assertBlocked(verdict, "protocol.runtime_unsupported");
	}

	@Test
	@DisplayName("缺 T2 窗口回执 → BLOCKED")
	void missingT2WindowReceiptIsBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(
				qualification(), factsWithProtocol(new ProtocolSupport(true, false, true, true))),
				"protocol.runtime_unsupported");
	}

	@Test
	@DisplayName("缺 T3 当前读 → BLOCKED")
	void missingT3CurrentReadIsBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(
				qualification(), factsWithProtocol(new ProtocolSupport(true, true, false, true))),
				"protocol.runtime_unsupported");
	}

	@Test
	@DisplayName("缺 T4 来源与授权保护 → BLOCKED")
	void missingT4SourceGrantIsBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(
				qualification(), factsWithProtocol(new ProtocolSupport(true, true, true, false))),
				"protocol.runtime_unsupported");
	}

	@Test
	@DisplayName("D7 数值不完整（缺观察窗口）→ BLOCKED")
	void incompleteD7BudgetIsBlocked() {
		Map<String, D7Budget> budgets = new LinkedHashMap<>(qualification().budgets());
		budgets.put("max-total-tasks", new D7Budget(512, "tasks", "同时可容纳任务数", "peak", "  ", "run/d7-486"));
		PushActivationGate.Verdict verdict = PushActivationGate.evaluateActivation(
				withBudgets(budgets), facts());
		assertBlocked(verdict, "qualification.d7_incomplete:max-total-tasks");
	}

	@Test
	@DisplayName("运行时预算超过冻结值 → BLOCKED（不得超出被测范围承载）")
	void runtimeBudgetExceedingFrozenIsBlocked() {
		Map<String, Long> actual = new LinkedHashMap<>(facts().budgets());
		actual.put("max-total-tasks", 4096L);
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), withBudgets(facts(), actual)),
				"budget.exceeds_frozen:max-total-tasks");
	}

	@Test
	@DisplayName("运行时出现未被冻结的预算维度 → BLOCKED")
	void unfrozenRuntimeBudgetIsBlocked() {
		Map<String, Long> actual = new LinkedHashMap<>(facts().budgets());
		actual.put("max-undeclared-dimension", 7L);
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), withBudgets(facts(), actual)),
				"budget.unfrozen:max-undeclared-dimension");
	}

	@Test
	@DisplayName("制品摘要不符 → BLOCKED（源码对得上不代表字节对得上）")
	void artifactDigestMismatchIsBlocked() {
		Map<String, String> actual = new LinkedHashMap<>(facts().artifacts());
		actual.put("im", "f".repeat(64));
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), withArtifacts(facts(), actual)),
				"artifact.digest_mismatch:im");
	}

	@Test
	@DisplayName("运行中多出未批准服务 → BLOCKED")
	void unapprovedLiveServiceIsBlocked() {
		Map<String, String> actual = new LinkedHashMap<>(facts().artifacts());
		actual.put("gateway", "f".repeat(64));
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), withArtifacts(facts(), actual)),
				"artifact.unapproved:gateway");
	}

	@Test
	@DisplayName("源码 SHA 不符 → BLOCKED")
	void sourceShaMismatchIsBlocked() {
		ActivationFacts facts = new ActivationFacts(facts().environment(), facts().backendKey(),
				facts().tenantScope(), facts().cohort(), facts().artifacts(), "9".repeat(40),
				facts().configDigest(), facts().capacityModel(), facts().budgets(), facts().protocol(),
				facts().rollbackRecoverable());
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), facts), "version.source_sha_mismatch");
	}

	@Test
	@DisplayName("生效配置摘要不符（如 Nacos 覆盖）→ BLOCKED")
	void configDigestMismatchIsBlocked() {
		ActivationFacts facts = new ActivationFacts(facts().environment(), facts().backendKey(),
				facts().tenantScope(), facts().cohort(), facts().artifacts(), facts().sourceSha(),
				"1".repeat(64), facts().capacityModel(), facts().budgets(), facts().protocol(),
				facts().rollbackRecoverable());
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), facts), "config.digest_mismatch");
	}

	@Test
	@DisplayName("跨环境复制批准 → BLOCKED（测试环境的资格不能搬到别处）")
	void crossEnvironmentQualificationIsBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(
				copyWithScope("prod", qualification().backendKey(), qualification().tenantScope(), qualification().cohort()),
				facts()), "scope.environment_mismatch");
	}

	@Test
	@DisplayName("候选 cohort 之外的实例 → BLOCKED")
	void outOfCohortInstanceIsBlocked() {
		assertBlocked(PushActivationGate.evaluateActivation(
				copyWithScope(qualification().environment(), qualification().backendKey(),
						qualification().tenantScope(), "cohort-z"),
				facts()), "scope.cohort_mismatch");
	}

	@Test
	@DisplayName("没有合格回退 R → BLOCKED（AC5：无合格回滚则停止启用）")
	void missingRollbackQualificationIsBlocked() {
		ActivationQualification noRollback = new ActivationQualification(
				qualification().schema(), qualification().purpose(), qualification().authorizedBy(),
				qualification().environment(), qualification().backendKey(), qualification().tenantScope(),
				qualification().cohort(), qualification().artifacts(), qualification().sourceSha(),
				qualification().configDigest(), qualification().capacityModel(), qualification().budgets(),
				qualification().protocol(), qualification().evidence(), null, qualification().conditional());
		assertBlocked(PushActivationGate.evaluateActivation(noRollback, facts()),
				"qualification.rollback_missing", "rollback.qualification_missing");
	}

	@Test
	@DisplayName("回退制品取不回来 → BLOCKED")
	void unrecoverableRollbackIsBlocked() {
		ActivationFacts facts = new ActivationFacts(facts().environment(), facts().backendKey(),
				facts().tenantScope(), facts().cohort(), facts().artifacts(), facts().sourceSha(),
				facts().configDigest(), facts().capacityModel(), facts().budgets(), facts().protocol(), false);
		assertBlocked(PushActivationGate.evaluateActivation(qualification(), facts), "rollback.unavailable");
	}

	@Test
	@DisplayName("展示省略/合并被开启 → BLOCKED（AC6 默认必须关闭）")
	void conditionalFeatureEnabledIsBlocked() {
		ActivationQualification enabled = new ActivationQualification(
				qualification().schema(), qualification().purpose(), qualification().authorizedBy(),
				qualification().environment(), qualification().backendKey(), qualification().tenantScope(),
				qualification().cohort(), qualification().artifacts(), qualification().sourceSha(),
				qualification().configDigest(), qualification().capacityModel(), qualification().budgets(),
				qualification().protocol(), qualification().evidence(), qualification().rollback(),
				new ConditionalFeatures(true, false, ""));
		assertBlocked(PushActivationGate.evaluateActivation(enabled, facts()),
				"qualification.conditional_not_default_off");
	}

	@Test
	@DisplayName("未知 schema → BLOCKED（不沿宽松默认值解析）")
	void unknownSchemaIsBlocked() {
		ActivationQualification unknown = new ActivationQualification(
				"push-activation-v2", qualification().purpose(), qualification().authorizedBy(),
				qualification().environment(), qualification().backendKey(), qualification().tenantScope(),
				qualification().cohort(), qualification().artifacts(), qualification().sourceSha(),
				qualification().configDigest(), qualification().capacityModel(), qualification().budgets(),
				qualification().protocol(), qualification().evidence(), qualification().rollback(),
				qualification().conditional());
		assertBlocked(PushActivationGate.evaluateActivation(unknown, facts()), "qualification.schema_unknown:push-activation-v2");
	}

	@Test
	@DisplayName("验证证据缺失 → BLOCKED")
	void missingVerificationEvidenceIsBlocked() {
		ActivationQualification noEvidence = new ActivationQualification(
				qualification().schema(), qualification().purpose(), qualification().authorizedBy(),
				qualification().environment(), qualification().backendKey(), qualification().tenantScope(),
				qualification().cohort(), qualification().artifacts(), qualification().sourceSha(),
				qualification().configDigest(), qualification().capacityModel(), qualification().budgets(),
				qualification().protocol(),
				new Evidence("huaaichat/aichatoverview#487", "d7-486-v1", "  ", "drill-1"),
				qualification().rollback(), qualification().conditional());
		assertBlocked(PushActivationGate.evaluateActivation(noEvidence, facts()),
				"qualification.evidence_incomplete");
	}

	@Test
	@DisplayName("配置摘要由生效值算出，配置差异必然改变摘要")
	void configDigestReflectsEffectiveValues() {
		Map<String, Object> baseline = new LinkedHashMap<>();
		baseline.put("maxTotalTasks", 512);
		baseline.put("maxTasksPerNode", 4);
		Map<String, Object> drifted = new LinkedHashMap<>();
		drifted.put("maxTotalTasks", 1024);
		drifted.put("maxTasksPerNode", 4);
		assertFalse(ActivationFacts.configDigestOf(baseline).equals(ActivationFacts.configDigestOf(drifted)));
		assertEquals(ActivationFacts.configDigestOf(baseline), ActivationFacts.configDigestOf(new LinkedHashMap<>(baseline)));
	}

	// ---------- 夹具 ----------

	private static void assertBlocked(PushActivationGate.Verdict verdict, String... codes) {
		assertEquals(PushActivationGate.Outcome.BLOCKED, verdict.outcome(), "expected BLOCKED but was READY");
		for (String code : codes) {
			assertTrue(verdict.reasons().contains(code),
					() -> "missing reason " + code + " in " + verdict.reasons());
		}
	}

	private static Map<String, D7Budget> budgets() {
		Map<String, D7Budget> budgets = new LinkedHashMap<>();
		budgets.put("max-active-nodes", new D7Budget(64, "nodes", "同时保留执行器的节点数", "peak", "60s", "d7-486-v1"));
		budgets.put("max-total-tasks", new D7Budget(512, "tasks", "全局同时可容纳任务数", "peak", "60s", "d7-486-v1"));
		budgets.put("max-tasks-per-node", new D7Budget(4, "tasks", "单节点同时任务数", "peak", "60s", "d7-486-v1"));
		budgets.put("max-payload-bytes", new D7Budget(16777216, "bytes", "共享载荷字节上限", "peak", "60s", "d7-486-v1"));
		budgets.put("max-concurrent-retries", new D7Budget(64, "chains", "并发重试链数", "peak", "60s", "d7-486-v1"));
		budgets.put("max-expand-rooms", new D7Budget(256, "rooms", "单次广播展开房间数", "peak", "60s", "d7-486-v1"));
		budgets.put("max-expand-targets", new D7Budget(4096, "targets", "单次广播展开目标数", "peak", "60s", "d7-486-v1"));
		budgets.put("expand-budget-millis", new D7Budget(2000, "ms", "单次广播展开耗时预算", "peak", "60s", "d7-486-v1"));
		return budgets;
	}

	private static Map<String, String> artifacts() {
		Map<String, String> artifacts = new LinkedHashMap<>();
		artifacts.put("im", IM_SHA);
		artifacts.put("ws", WS_SHA);
		return artifacts;
	}

	private static ActivationQualification qualification() {
		return new ActivationQualification(
				ActivationQualification.SCHEMA,
				"isolated-candidate-verification",
				"release-owner@run-da56ede1",
				"test",
				"backend-a",
				"tenant:1",
				"cohort-1",
				artifacts(),
				SOURCE_SHA,
				CONFIG_DIGEST,
				"bounded-per-node",
				budgets(),
				new ProtocolSupport(true, true, true, true),
				new Evidence("huaaichat/aichatoverview#487", "d7-486-v1", "evidence/verify487", "drill-1"),
				new RollbackTarget(ROLLBACK_SHA, artifacts(), "2".repeat(64), true, "drill-1-material-restored"),
				new ConditionalFeatures(false, false, "默认关闭，不需要证据"));
	}

	private static ActivationFacts facts() {
		return new ActivationFacts("test", "backend-a", "tenant:1", "cohort-1", artifacts(), SOURCE_SHA,
				CONFIG_DIGEST, "bounded-per-node", runtimeBudgets(), new ProtocolSupport(true, true, true, true), true);
	}

	private static ActivationFacts factsWithProtocol(ProtocolSupport protocol) {
		return new ActivationFacts(facts().environment(), facts().backendKey(), facts().tenantScope(),
				facts().cohort(), facts().artifacts(), facts().sourceSha(), facts().configDigest(),
				facts().capacityModel(), facts().budgets(), protocol, facts().rollbackRecoverable());
	}

	private static ActivationFacts withArtifacts(ActivationFacts base, Map<String, String> artifacts) {
		return new ActivationFacts(base.environment(), base.backendKey(), base.tenantScope(), base.cohort(),
				artifacts, base.sourceSha(), base.configDigest(), base.capacityModel(), base.budgets(),
				base.protocol(), base.rollbackRecoverable());
	}

	private static ActivationFacts withBudgets(ActivationFacts base, Map<String, Long> budgets) {
		return new ActivationFacts(base.environment(), base.backendKey(), base.tenantScope(), base.cohort(),
				base.artifacts(), base.sourceSha(), base.configDigest(), base.capacityModel(), budgets,
				base.protocol(), base.rollbackRecoverable());
	}

	private static ActivationQualification withBudgets(Map<String, D7Budget> budgets) {
		return new ActivationQualification(qualification().schema(), qualification().purpose(),
				qualification().authorizedBy(), qualification().environment(), qualification().backendKey(),
				qualification().tenantScope(), qualification().cohort(), qualification().artifacts(),
				qualification().sourceSha(), qualification().configDigest(), qualification().capacityModel(),
				budgets, qualification().protocol(), qualification().evidence(), qualification().rollback(),
				qualification().conditional());
	}

	private static ActivationQualification copyWithScope(String environment, String backend, String tenant,
			String cohort) {
		return new ActivationQualification(qualification().schema(), qualification().purpose(),
				qualification().authorizedBy(), environment, backend, tenant, cohort,
				qualification().artifacts(), qualification().sourceSha(), qualification().configDigest(),
				qualification().capacityModel(), qualification().budgets(), qualification().protocol(),
				qualification().evidence(), qualification().rollback(), qualification().conditional());
	}

	private static Map<String, Long> runtimeBudgets() {
		Map<String, Long> actual = new LinkedHashMap<>();
		actual.put("max-active-nodes", 64L);
		actual.put("max-total-tasks", 512L);
		actual.put("max-tasks-per-node", 4L);
		actual.put("max-payload-bytes", 16777216L);
		actual.put("max-concurrent-retries", 64L);
		actual.put("max-expand-rooms", 256L);
		actual.put("max-expand-targets", 4096L);
		actual.put("expand-budget-millis", 2000L);
		return actual;
	}
}
