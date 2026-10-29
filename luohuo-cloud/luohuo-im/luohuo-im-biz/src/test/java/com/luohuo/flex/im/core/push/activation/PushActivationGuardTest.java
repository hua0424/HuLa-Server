package com.luohuo.flex.im.core.push.activation;

import com.luohuo.flex.im.core.push.activation.ActivationQualification.D7Budget;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.Evidence;
import com.luohuo.flex.im.core.push.activation.ActivationQualification.RollbackTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aichatoverview#487 T6：启用资格装配层的可运行检查。
 *
 * <p>判定规则本身由 {@link PushActivationGateTest} 与共享夹具钉住，这里只证明装配层
 * <b>真的</b>把文件、部署 manifest 与类路径事实接进了判定——包括"文件缺失就拒绝"，
 * 不允许出现"读不到材料于是放行"的宽松路径。
 */
class PushActivationGuardTest {

	private static final String IM_SHA = "a".repeat(64);
	private static final String WS_SHA = "b".repeat(64);
	private static final String SOURCE_SHA = "c".repeat(40);
	private static final String ROLLBACK_SHA = "d".repeat(40);
	private static final String CONFIG_DIGEST = "e".repeat(64);

	@TempDir
	Path temp;

	@Test
	void absentQualificationBlocksInsteadOfFallingThrough() {
		PushActivationGuard guard = guard(temp.resolve("missing.json"), manifest(List.of("im")));

		PushActivationGate.Verdict verdict = guard.verdict();

		assertFalse(verdict.ready());
		assertEquals(List.of("qualification_absent"), verdict.reasons());
	}

	@Test
	void unreadableManifestBlocksBecauseFactsCannotBeInvented() {
		Path qualification = write("qualification.json", ActivationFactsJson.qualification());
		PushActivationGuard guard = guard(qualification, temp.resolve("no-manifest.json"));

		PushActivationGate.Verdict verdict = guard.verdict();

		assertFalse(verdict.ready());
		assertEquals(List.of("facts_absent"), verdict.reasons());
	}

	@Test
	void completeMaterialWithMatchingManifestBecomesReady() {
		PushActivationGuard guard = guard(null, manifest(List.of("im", "ws")));
		ReflectionTestUtils.setField(guard, "rollbackBackupPath", completeBackup().toString());
		// 资格材料里的配置摘要必须是"实际生效配置"的摘要——这里用 guard 自己的规范化输入算，
		// 手工写死一个常量就会变成永远 config.digest_mismatch。
		ReflectionTestUtils.setField(guard, "qualificationPath",
				write("qualification.json", ActivationFactsJson.qualification(guard)).toString());

		PushActivationGate.Verdict verdict = guard.verdict();

		assertTrue(verdict.ready(), () -> "expected READY, reasons=" + verdict.reasons());
	}

	@Test
	void manifestWithAnUnapprovedServiceBlocksEvenWhenEverythingElseMatches() {
		Path qualification = write("qualification.json", ActivationFactsJson.qualification());
		PushActivationGuard guard = guard(qualification, manifest(List.of("im", "ws", "gateway")));
		ReflectionTestUtils.setField(guard, "rollbackBackupPath", completeBackup().toString());

		PushActivationGate.Verdict verdict = guard.verdict();

		assertFalse(verdict.ready());
		assertTrue(verdict.reasons().contains("artifact.unapproved:gateway"), () -> verdict.reasons().toString());
	}

	@Test
	void missingRollbackBackupBlocksBecauseRollbackIsNotActuallyAvailable() {
		Path qualification = write("qualification.json", ActivationFactsJson.qualification());
		PushActivationGuard guard = guard(qualification, manifest(List.of("im", "ws")));
		ReflectionTestUtils.setField(guard, "rollbackBackupPath", temp.resolve("no-backup").toString());

		PushActivationGate.Verdict verdict = guard.verdict();

		assertFalse(verdict.ready());
		assertTrue(verdict.reasons().contains("rollback.unavailable"), () -> verdict.reasons().toString());
	}

	@Test
	void scopeMismatchBetweenApprovalAndThisDeploymentBlocks() {
		Path qualification = write("qualification.json", ActivationFactsJson.qualification());
		PushActivationGuard guard = guard(qualification, manifest(List.of("im", "ws")));
		ReflectionTestUtils.setField(guard, "rollbackBackupPath", completeBackup().toString());
		ReflectionTestUtils.setField(guard, "cohort", "cohort-b");

		PushActivationGate.Verdict verdict = guard.verdict();

		assertFalse(verdict.ready());
		assertTrue(verdict.reasons().contains("scope.cohort_mismatch"), () -> verdict.reasons().toString());
	}

	private PushActivationGuard guard(Path qualification, Path manifest) {
		PushActivationGuard guard = new PushActivationGuard();
		ReflectionTestUtils.setField(guard, "qualificationPath", qualification == null ? "" : qualification.toString());
		ReflectionTestUtils.setField(guard, "manifestPath", manifest.toString());
		ReflectionTestUtils.setField(guard, "rollbackBackupPath", "");
		ReflectionTestUtils.setField(guard, "environment", "test-83");
		ReflectionTestUtils.setField(guard, "backendKey", "hula-test");
		ReflectionTestUtils.setField(guard, "tenantScope", "tenant-1");
		ReflectionTestUtils.setField(guard, "cohort", "cohort-a");
		ReflectionTestUtils.setField(guard, "capacityModel", "bounded-per-node");
		ReflectionTestUtils.setField(guard, "maxActiveNodes", 64);
		ReflectionTestUtils.setField(guard, "maxTotalTasks", 512);
		ReflectionTestUtils.setField(guard, "maxTasksPerNode", 4);
		ReflectionTestUtils.setField(guard, "maxPayloadBytes", 16777216);
		ReflectionTestUtils.setField(guard, "maxConcurrentRetries", 64);
		ReflectionTestUtils.setField(guard, "maxExpandRooms", 256);
		ReflectionTestUtils.setField(guard, "maxExpandTargets", 4096);
		ReflectionTestUtils.setField(guard, "expandBudgetMillis", 2000L);
		return guard;
	}

	private Path completeBackup() {
		try {
			Path backup = Files.createDirectories(temp.resolve("backup-" + System.nanoTime()));
			Files.writeString(backup.resolve("COMPLETE"), "", StandardCharsets.UTF_8);
			return backup;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private Path manifest(List<String> services) {
		Map<String, String> artifacts = new LinkedHashMap<>();
		for (String service : services) {
			artifacts.put(service, "im".equals(service) ? IM_SHA : "ws".equals(service)
					? WS_SHA : "1".repeat(64));
		}
		StringBuilder json = new StringBuilder("{\"schema\":1,\"release_id\":\"verify487-fixture\",")
				.append("\"branch\":\"task/t6-487-safe-enable\",")
				.append("\"source_sha\":\"").append(SOURCE_SHA).append("\",")
				.append("\"services\":[").append(services.stream().map(s -> "\"" + s + "\"")
						.reduce((a, b) -> a + "," + b).orElse("")).append("],")
				.append("\"built_at\":\"2026-10-29T00:00:00Z\",\"artifacts\":{");
		boolean first = true;
		for (Map.Entry<String, String> entry : artifacts.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
		}
		json.append("}}");
		return write("manifest-" + System.nanoTime() + ".json", json.toString());
	}

	private Path write(String name, String content) {
		try {
			Path file = temp.resolve(name);
			Files.writeString(file, content, StandardCharsets.UTF_8);
			return file;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 与测试机 sidecar 同形的合格材料（数值来自 D7 冻结记录）。 */
	private static final class ActivationFactsJson {

		/**
		 * 配置摘要必须等于"实际生效配置"的摘要：资格材料声明的就是这一份，不能手写常量。
		 * 早先写死 {@code CONFIG_DIGEST} 时，READY 用例永远报 {@code config.digest_mismatch}——
		 * 看着安全，其实是门禁永远打不开。
		 */
		private static String qualification(PushActivationGuard guard) {
			return build(ActivationFacts.configDigestOf(guard.effectiveCapacityConfig()));
		}

		private static String qualification() {
			return build(CONFIG_DIGEST);
		}

		private static String build(String configDigest) {
			ActivationQualification qualification = new ActivationQualification(
					ActivationQualification.SCHEMA,
					"isolated candidate verification",
					"release-manager@hula",
					"test-83",
					"hula-test",
					"tenant-1",
					"cohort-a",
					Map.of("im", IM_SHA, "ws", WS_SHA),
					SOURCE_SHA,
					configDigest,
					"bounded-per-node",
					budgets(),
					new ActivationFacts.ProtocolSupport(true, true, true, true),
					new Evidence("huaaichat/aichatoverview#487", "d7-486-20261029",
							"verify487-gate-01", "drill-487-rollback-01 PASS"),
					new RollbackTarget(ROLLBACK_SHA, Map.of("im", "1".repeat(64), "ws", "2".repeat(64)),
							"f".repeat(64), true, "rollback drill PASS"),
					new ActivationQualification.ConditionalFeatures(false, false,
							"display omission/coalescing not in scope"));
			return com.luohuo.basic.jackson.JsonUtil.toJson(qualification);
		}

		private static Map<String, D7Budget> budgets() {
			Map<String, D7Budget> budgets = new LinkedHashMap<>();
			budgets.put("max-active-nodes", budget(64, "logical_targets"));
			budgets.put("max-total-tasks", budget(512, "logical_targets"));
			budgets.put("max-tasks-per-node", budget(4, "logical_targets"));
			budgets.put("max-payload-bytes", budget(16777216, "bytes"));
			budgets.put("max-concurrent-retries", budget(64, "in_flight_retries"));
			budgets.put("max-expand-rooms", budget(256, "rooms"));
			budgets.put("max-expand-targets", budget(4096, "targets"));
			budgets.put("expand-budget-millis", budget(2000, "millis"));
			return budgets;
		}

		private static D7Budget budget(long value, String unit) {
			return new D7Budget(value, unit, "definition", "workload", "300s steady + 120s peak",
					"d7-486-20261029");
		}
	}
}
