package com.luohuo.flex.im.core.push.activation;

import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.flex.im.core.push.activation.ActivationFacts.ProtocolSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启用资格的运行时装配（aichatoverview#487 T6）：读取人工授权 sidecar 与部署 manifest，
 * 构造 {@link ActivationFacts}，交给 {@link PushActivationGate} 判定，并把结论冻结成启动快照。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>启动时判定一次，之后不再变</b>——不做热启用中心。资格漂移（后续换了配置/制品）
 *       不会让已启用的进程悄悄改回旧路径；要变只能重新部署。</li>
 *   <li><b>读不到就拒绝</b>：sidecar 缺失、manifest 缺失、JSON 坏格式、未知 schema 全部 BLOCKED，
 *       不存在"缺文件就当通过"的宽松分支。</li>
 *   <li><b>事实取自可核对来源</b>：制品摘要与源码 SHA 来自部署 manifest（部署脚本已逐 jar 校验
 *       与 live 字节一致），协议能力来自类路径上的实际实现探测，配置摘要来自实际生效的配置值。</li>
 * </ol>
 */
@Slf4j
@Component
public class PushActivationGuard {

	/** 资格材料 sidecar 路径；未配置表示没有授权材料。 */
	@Value("${luohuo.push.capacity.activation.qualification-path:}")
	private String qualificationPath;

	/** 部署 manifest 路径（deploy-server.sh 产出的 releases/&lt;id&gt;/manifest.json）。 */
	@Value("${luohuo.push.capacity.activation.manifest-path:}")
	private String manifestPath;

	/** 回退 backup 目录（含 COMPLETE 与 SHA256SUMS 的 backups/&lt;id&gt;）。 */
	@Value("${luohuo.push.capacity.activation.rollback-backup-path:}")
	private String rollbackBackupPath;

	@Value("${luohuo.push.capacity.activation.environment:}")
	private String environment;

	@Value("${luohuo.push.capacity.activation.backend-key:}")
	private String backendKey;

	@Value("${luohuo.push.capacity.activation.tenant-scope:}")
	private String tenantScope;

	@Value("${luohuo.push.capacity.activation.cohort:}")
	private String cohort;

	@Value("${luohuo.push.capacity.activation.capacity-model:bounded-per-node}")
	private String capacityModel;

	@Value("${luohuo.push.capacity.max-active-nodes:64}")
	private int maxActiveNodes;

	@Value("${luohuo.push.capacity.max-total-tasks:512}")
	private int maxTotalTasks;

	@Value("${luohuo.push.capacity.max-tasks-per-node:4}")
	private int maxTasksPerNode;

	@Value("${luohuo.push.capacity.max-payload-bytes:16777216}")
	private int maxPayloadBytes;

	@Value("${luohuo.push.capacity.max-concurrent-retries:64}")
	private int maxConcurrentRetries;

	@Value("${luohuo.push.capacity.max-expand-rooms:256}")
	private int maxExpandRooms;

	@Value("${luohuo.push.capacity.max-expand-targets:4096}")
	private int maxExpandTargets;

	@Value("${luohuo.push.capacity.expand-budget-millis:2000}")
	private long expandBudgetMillis;

	private volatile PushActivationGate.Verdict verdict = PushActivationGate.Verdict.blocked(
			List.of("activation.not_evaluated"), "启用资格尚未判定，新策略保持未启用");

	private volatile boolean evaluated;

	@PostConstruct
	public void evaluate() {
		ensureEvaluated();
	}

	/**
	 * 判定一次并冻结。{@code @PostConstruct} 的执行顺序不由 Spring 保证，
	 * 所以消费者（{@code PushService.init()}）通过 {@link #verdict()} 触发同一个幂等判定，
	 * 避免"资格材料齐全却因为初始化顺序读到 not_evaluated 而永远不启用"。
	 */
	private void ensureEvaluated() {
		if (evaluated) {
			return;
		}
		synchronized (this) {
			if (evaluated) {
				return;
			}
			ActivationQualification qualification = loadQualification();
			ActivationFacts facts = buildFacts();
			verdict = PushActivationGate.evaluateActivation(qualification, facts);
			evaluated = true;
			if (verdict.ready()) {
				log.info("推送容量策略启用资格判定通过（#487 T6）: {}", verdict.detail());
			} else {
				log.warn("推送容量策略启用资格判定未通过（#487 T6），保持未启用: {}", verdict.detail());
			}
		}
	}

	/** 启动快照：只读，不随配置漂移变化。 */
	public PushActivationGate.Verdict verdict() {
		ensureEvaluated();
		return verdict;
	}

	public boolean ready() {
		return verdict.ready();
	}

	/** 生效配置的规范化摘要输入：只取能改变新策略行为的键。 */
	public Map<String, Object> effectiveCapacityConfig() {
		Map<String, Object> effective = new LinkedHashMap<>();
		effective.put("capacityModel", capacityModel);
		effective.put("maxActiveNodes", maxActiveNodes);
		effective.put("maxTotalTasks", maxTotalTasks);
		effective.put("maxTasksPerNode", maxTasksPerNode);
		effective.put("maxPayloadBytes", maxPayloadBytes);
		effective.put("maxConcurrentRetries", maxConcurrentRetries);
		effective.put("maxExpandRooms", maxExpandRooms);
		effective.put("maxExpandTargets", maxExpandTargets);
		effective.put("expandBudgetMillis", expandBudgetMillis);
		return effective;
	}

	/** 运行时预算：与资格材料的 D7 冻结值逐一比对（实际 ≤ 冻结）。 */
	public Map<String, Long> runtimeBudgets() {
		Map<String, Long> budgets = new LinkedHashMap<>();
		budgets.put("max-active-nodes", (long) maxActiveNodes);
		budgets.put("max-total-tasks", (long) maxTotalTasks);
		budgets.put("max-tasks-per-node", (long) maxTasksPerNode);
		budgets.put("max-payload-bytes", (long) maxPayloadBytes);
		budgets.put("max-concurrent-retries", (long) maxConcurrentRetries);
		budgets.put("max-expand-rooms", (long) maxExpandRooms);
		budgets.put("max-expand-targets", (long) maxExpandTargets);
		budgets.put("expand-budget-millis", expandBudgetMillis);
		return budgets;
	}

	private ActivationQualification loadQualification() {
		if (isBlank(qualificationPath)) {
			log.warn("#487 T6：未配置启用资格材料路径，新策略不可启用");
			return null;
		}
		String content = readFile(qualificationPath, "资格材料");
		if (content == null) {
			return null;
		}
		ActivationQualification parsed = JsonUtil.parse(content, ActivationQualification.class);
		if (parsed == null) {
			log.warn("#487 T6：启用资格材料无法解析为 {}，按 BLOCKED 处理", ActivationQualification.SCHEMA);
		}
		return parsed;
	}

	private ActivationFacts buildFacts() {
		String content = isBlank(manifestPath) ? null : readFile(manifestPath, "部署 manifest");
		if (content == null) {
			// 没有 manifest 就没有可核对的制品摘要与源码 SHA——不能凭空编造事实。
			return null;
		}
		DeployManifest manifest = JsonUtil.parse(content, DeployManifest.class);
		if (manifest == null || manifest.artifacts() == null || manifest.artifacts().isEmpty()) {
			log.warn("#487 T6：部署 manifest 无效，无法核对实际制品");
			return null;
		}
		return new ActivationFacts(
				environment,
				backendKey,
				tenantScope,
				cohort,
				Map.copyOf(manifest.artifacts()),
				manifest.sourceSha(),
				ActivationFacts.configDigestOf(effectiveCapacityConfig()),
				capacityModel,
				runtimeBudgets(),
				probeProtocolSupport(),
				rollbackRecoverable());
	}

	/**
	 * 协议能力探测：看类路径上是否存在 T1–T5 实际落地的实现。
	 * 这比配置开关诚实——开关可以被误配成 true，类不存在则说明这份制品根本没带该能力。
	 */
	private ProtocolSupport probeProtocolSupport() {
		boolean t1Ack = hasClass("com.luohuo.flex.im.core.user.service.dto.PushSubmission")
				&& hasMethod("com.luohuo.flex.im.core.user.service.dto.PushSubmission", "isFullyAdmitted");
		boolean t2Window = hasClass("com.luohuo.flex.im.domain.vo.res.MsgWindowResp");
		boolean t3CurrentRead = hasClass("com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigStateResp");
		boolean t4SourceGrant = hasClass("com.luohuo.flex.im.core.chat.service.GroupRunAdmissionService");
		return new ProtocolSupport(t1Ack, t2Window, t3CurrentRead, t4SourceGrant);
	}

	/** 回退制品是否真的取得到：backup 目录存在且写了 COMPLETE 标记。 */
	private boolean rollbackRecoverable() {
		if (isBlank(rollbackBackupPath)) {
			return false;
		}
		Path backup = Path.of(rollbackBackupPath);
		return Files.isDirectory(backup) && Files.isRegularFile(backup.resolve("COMPLETE"));
	}

	private String readFile(String path, String what) {
		try {
			Path file = Path.of(path);
			if (!Files.isRegularFile(file)) {
				log.warn("#487 T6：{}不存在: {}", what, path);
				return null;
			}
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			log.warn("#487 T6：读取{}失败: {} ({})", what, path, e.getMessage());
			return null;
		}
	}

	private static boolean hasClass(String name) {
		try {
			Class.forName(name, false, PushActivationGuard.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean hasMethod(String className, String method) {
		try {
			Class<?> type = Class.forName(className, false, PushActivationGuard.class.getClassLoader());
			for (var candidate : type.getMethods()) {
				if (candidate.getName().equals(method)) {
					return true;
				}
			}
		} catch (Throwable ignored) {
			return false;
		}
		return false;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * deploy-server.sh 产出的 manifest 里本判定需要的那几项。
	 *
	 * <p>字段是 snake_case：那是部署脚本实际写出的形状，用 camelCase 会解析成 null，
	 * 于是每次判定都以 {@code version.source_sha_mismatch} 拒绝——看起来"很安全"，
	 * 实际是永远启用不了。这里显式绑定，并有 {@code PushActivationGuardTest} 钉住。
	 */
	record DeployManifest(
			@com.fasterxml.jackson.annotation.JsonProperty("release_id") String releaseId,
			@com.fasterxml.jackson.annotation.JsonProperty("branch") String branch,
			@com.fasterxml.jackson.annotation.JsonProperty("source_sha") String sourceSha,
			@com.fasterxml.jackson.annotation.JsonProperty("artifacts") Map<String, String> artifacts) {
	}
}
