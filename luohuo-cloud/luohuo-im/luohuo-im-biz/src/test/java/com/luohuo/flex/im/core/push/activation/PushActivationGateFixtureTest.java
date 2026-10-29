package com.luohuo.flex.im.core.push.activation;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #487 T6：启用门禁的**共享夹具**一致性测试。
 *
 * <p>夹具在伞仓 {@code tests/skill-contracts/fixtures/activation/}，同一批文件同时被部署 preflight
 * （{@code deploy-server-contracts.sh} 用 jq 实现）和本测试（Java 实现）消费。两侧都以此为准：
 * 任一侧规则漂移，必有一侧失败 —— 这就是设计 v1 §3 要求的「共享规则 + 一致性测试」，
 * 不引入跨语言框架。
 *
 * <p>期望值（{@code expect.reasons}）是**按合同手写**的，不是任何一侧实现生成的。
 *
 * <p>夹具目录用系统属性 {@code activation.fixtures} 指定；未指定时跳过（伞仓与子仓在不同 Git 仓，
 * 本测试无法在子仓单测里假定伞仓路径）。契约脚本始终会跑这批夹具，跳过只影响子仓单独跑的场景。
 */
class PushActivationGateFixtureTest {

	@Test
	@DisplayName("共享夹具：门禁判定与伞仓夹具期望逐项一致")
	void gateMatchesSharedFixtures() throws IOException {
		String dir = System.getProperty("activation.fixtures");
		if (dir == null || dir.isBlank()) {
			System.out.println("[#487 T6] activation.fixtures 未指定，跳过共享夹具用例（契约脚本仍会跑同一批文件）");
			return;
		}
		Path root = Path.of(dir);
		assertTrue(Files.isDirectory(root), "夹具目录不存在: " + root);

		List<String> mismatches = new ArrayList<>();
		List<String> names;
		try (Stream<Path> files = Files.list(root)) {
			names = files.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.sorted()
					.toList();
		}
		assertFalse(names.isEmpty(), "夹具目录里没有 json");

		for (String name : names) {
			JSONObject fixture = JSONUtil.parseObj(Files.readString(root.resolve(name), StandardCharsets.UTF_8));
			ActivationFacts facts = toFacts(fixture.getJSONObject("facts"));
			JSONObject rawQualification = fixture.getJSONObject("qualification");
			ActivationQualification qualification =
					rawQualification == null ? null : toQualification(rawQualification);

			PushActivationGate.Verdict verdict = PushActivationGate.evaluateActivation(qualification, facts);
			List<String> expected = fixture.getJSONObject("expect").getJSONArray("reasons").toList(String.class);
			List<String> actual = new ArrayList<>(verdict.reasons());
			actual.sort(String::compareTo);
			List<String> sortedExpected = new ArrayList<>(expected);
			sortedExpected.sort(String::compareTo);

			String expectedOutcome = fixture.getJSONObject("expect").getStr("outcome");
			if (!sortedExpected.equals(actual)) {
				mismatches.add(name + ": reasons expected=" + sortedExpected + " actual=" + actual);
			} else if (!expectedOutcome.equals(verdict.outcome().name())) {
				mismatches.add(name + ": outcome expected=" + expectedOutcome + " actual=" + verdict.outcome());
			}
		}
		assertTrue(mismatches.isEmpty(), "夹具不一致:\n" + String.join("\n", mismatches));
		assertTrue(names.size() >= 20, "夹具数量不足以覆盖否定变体: " + names.size());
	}

	private static ActivationFacts toFacts(JSONObject o) {
		return new ActivationFacts(
				o.getStr("environment"),
				o.getStr("backendKey"),
				o.getStr("tenantScope"),
				o.getStr("cohort"),
				toArtifacts(o.getJSONObject("artifacts")),
				o.getStr("sourceSha"),
				o.getStr("configDigest"),
				o.getStr("capacityModel"),
				toBudgets(o.getJSONObject("budgets")),
				toProtocol(o.getJSONObject("protocol")),
				Boolean.TRUE.equals(o.getBool("rollbackRecoverable")));
	}

	private static ActivationQualification toQualification(JSONObject o) {
		JSONObject conditional = o.getJSONObject("conditional");
		JSONObject rollback = o.getJSONObject("rollback");
		JSONObject evidence = o.getJSONObject("evidence");
		return new ActivationQualification(
				o.getStr("schema"),
				o.getStr("purpose"),
				o.getStr("authorizedBy"),
				o.getStr("environment"),
				o.getStr("backendKey"),
				o.getStr("tenantScope"),
				o.getStr("cohort"),
				toArtifacts(o.getJSONObject("artifacts")),
				o.getStr("sourceSha"),
				o.getStr("configDigest"),
				o.getStr("capacityModel"),
				toD7(o.getJSONObject("budgets")),
				toProtocol(o.getJSONObject("protocol")),
				evidence == null ? null : new ActivationQualification.Evidence(
						evidence.getStr("responsibleIssue"),
						evidence.getStr("d7FrozenVersion"),
						evidence.getStr("verification"),
						evidence.getStr("activationDrill")),
				rollback == null ? null : new ActivationQualification.RollbackTarget(
						rollback.getStr("sourceSha"),
						toArtifacts(rollback.getJSONObject("artifacts")),
						rollback.getStr("configDigest"),
						Boolean.TRUE.equals(rollback.getBool("stateCompatible")),
						rollback.getStr("drillEvidence")),
				conditional == null ? null : new ActivationQualification.ConditionalFeatures(
						Boolean.TRUE.equals(conditional.getBool("omissionEnabled")),
						Boolean.TRUE.equals(conditional.getBool("coalescingEnabled")),
						conditional.getStr("evidence")));
	}

	private static java.util.Map<String, String> toArtifacts(JSONObject o) {
		java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
		if (o == null) {
			return out;
		}
		for (String key : o.keySet()) {
			out.put(key, o.getStr(key));
		}
		return out;
	}

	private static java.util.Map<String, Long> toBudgets(JSONObject o) {
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		if (o == null) {
			return out;
		}
		for (String key : o.keySet()) {
			out.put(key, o.getLong(key));
		}
		return out;
	}

	private static java.util.Map<String, ActivationQualification.D7Budget> toD7(JSONObject o) {
		java.util.Map<String, ActivationQualification.D7Budget> out = new java.util.LinkedHashMap<>();
		if (o == null) {
			return out;
		}
		for (String key : o.keySet()) {
			JSONObject b = o.getJSONObject(key);
			out.put(key, new ActivationQualification.D7Budget(
					b.getLong("value"),
					b.getStr("unit"),
					b.getStr("definition"),
					b.getStr("workload"),
					b.getStr("observeWindow"),
					b.getStr("measuredFrom")));
		}
		return out;
	}

	private static ActivationFacts.ProtocolSupport toProtocol(JSONObject o) {
		if (o == null) {
			return new ActivationFacts.ProtocolSupport(false, false, false, false);
		}
		return new ActivationFacts.ProtocolSupport(
				Boolean.TRUE.equals(o.getBool("t1Ack")),
				Boolean.TRUE.equals(o.getBool("t2WindowReceipt")),
				Boolean.TRUE.equals(o.getBool("t3CurrentRead")),
				Boolean.TRUE.equals(o.getBool("t4SourceGrant")));
	}
}
