package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.jackson.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #486 T5 / D7 的容量读数台：把账本各维度的可测量量与准入开销钉成可复跑的数字。
 *
 * <p>这不是 SLA 断言，而是「同一个候选在固定输入下的确定性读数」：真实节点的 p95/p99
 * 恢复时限需要获准的测试环境负载才能测（见 #486 manifest 的未验证项），本类提供的是
 * 可以随代码一起回归的相对稳定部分——维度边界、拒绝分布、账本状态体积、单次准入开销。
 * 数值变了说明容量语义变了，需要重新走 D7 冻结。
 */
class PushCapacityMeasurementTest {

	private static final long ADMISSION_SAMPLES = 20_000L;

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	@Test
	@DisplayName("D7：维度边界与拒绝分布（单节点满 / 全局满 / 健康节点不受影响）")
	void dimensionsAndRejectionDistribution() {
		// 单节点 4 任务、4 个活跃节点、全局 16 任务。
		PushCapacityLedger ledger = new PushCapacityLedger(4, 16, 4, 1 << 20, 8);
		List<PushCapacityLedger.Admission> held = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			PushCapacityLedger.Reservation r = ledger.reserve("node-a", "payload-" + i);
			assertTrue(r.admitted(), "节点 a 的第 " + (i + 1) + " 个任务应在额度内, got=" + r.reason());
			held.add(r.admission());
		}
		assertEquals(PushCapacityLedger.Reject.NODE_QUEUE_FULL, ledger.reserve("node-a", "payload-x").reject(),
				"第 5 个任务应命中单节点上限");
		PushCapacityLedger.Reservation healthy = ledger.reserve("node-b", "payload-b");
		assertTrue(healthy.admitted(), "健康节点不受别的节点满额影响, got=" + healthy.reason());
		held.add(healthy.admission());
		held.forEach(PushCapacityLedger.Admission::release);
		assertEquals(0L, number(ledger.snapshot().get("payloadBytes")), "释放后载荷字节归零");

		// 全局上限：16 个任务打散到 4 个节点，第 17 个必须是全局拒绝而不是节点拒绝。
		List<PushCapacityLedger.Admission> all = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			PushCapacityLedger.Reservation r = ledger.reserve("n" + (i % 4), "g-" + i);
			assertTrue(r.admitted(), "全局额度内第 " + (i + 1) + " 个任务应准入, got=" + r.reason());
			all.add(r.admission());
		}
		assertEquals(PushCapacityLedger.Reject.GLOBAL_TASK_LIMIT, ledger.reserve("n0", "g-x").reject(),
				"全局满额优先报告全局维度");
		all.forEach(PushCapacityLedger.Admission::release);
		assertEquals(0L, number(ledger.snapshot().get("payloadBytes")), "全部释放后载荷字节归零");
		// 4 个 node-a + 1 个 node-b + 16 个全局批次，全部结算且不重复计数。
		assertEquals(21L, number(ledger.snapshot().get("releasedTasks")));

		System.out.println("[D7][capacity] 维度边界: perNode=4 activeNodes=4 globalTasks=16 "
				+ "-> 第5个同节点=NODE_QUEUE_FULL, 健康节点仍准入, 第17个=GLOBAL_TASK_LIMIT, 释放后 payloadBytes=0");
	}

	@Test
	@DisplayName("D7：拒绝原因按维度分账，可用于超载观测")
	void rejectionReasonsAreAccounted() {
		// 载荷字节按 JSON 文本长度估算（字符串会被引号包住），故预算取 100 后用一个 200 字符载荷越界。
		PushCapacityLedger ledger = new PushCapacityLedger(4, 8, 2, 100, 1);
		List<PushCapacityLedger.Admission> held = new ArrayList<>();
		String sharedPayload = "1234";
		held.add(ledger.reserve("a", sharedPayload).admission());   // 节点 a 第 1 个
		held.add(ledger.reserve("a", sharedPayload).admission());   // 同引用不计新增载荷，节点 a 第 2 个
		assertEquals(PushCapacityLedger.Reject.NODE_QUEUE_FULL, ledger.reserve("a", "x").reject(),
				"同节点第 3 个任务超单节点上限");
		assertEquals(PushCapacityLedger.Reject.PAYLOAD_LIMIT, ledger.reserve("b", "z".repeat(200)).reject(),
				"超出载荷预算");
		// 重试槽 1 个：第 1 次准入，第 2 次超限。
		PushCapacityLedger.Reservation retry = ledger.reserveRetry("delay");
		assertTrue(retry.admitted(), "第 1 次重试预约应在额度内, got=" + retry.reason());
		assertEquals(PushCapacityLedger.Reject.RETRY_LIMIT, ledger.reserveRetry("delay").reject(),
				"重试槽 1 个已用尽");
		retry.admission().release();

		@SuppressWarnings("unchecked")
		Map<String, Number> rejections = (Map<String, Number>) ledger.snapshot().get("rejections");
		// 快照按 Reject 枚举名分账（既有 PushCapacityLedgerTest 已钉），reason() 是对外暴露的原因码。
		assertEquals(1L, rejections.get(PushCapacityLedger.Reject.PAYLOAD_LIMIT.name()).longValue());
		assertEquals(1L, rejections.get(PushCapacityLedger.Reject.NODE_QUEUE_FULL.name()).longValue());
		assertEquals(1L, rejections.get(PushCapacityLedger.Reject.RETRY_LIMIT.name()).longValue());
		System.out.println("[D7][capacity] 拒绝分布: " + JsonUtil.toJson(rejections));
		held.forEach(PushCapacityLedger.Admission::release);
	}

	@Test
	@DisplayName("D7：账本状态体积（节点数 × 跟踪条目的字节数）")
	void trackingStateFootprint() {
		for (int nodes : new int[]{1, 8, 64, 256}) {
			PushCapacityLedger ledger = new PushCapacityLedger(nodes, nodes * 4, 4, 1 << 30, 64);
			List<PushCapacityLedger.Admission> held = new ArrayList<>();
			for (int i = 0; i < nodes * 4; i++) {
				PushCapacityLedger.Reservation r = ledger.reserve("node-" + (i % nodes), "p-" + i);
				if (r.admitted()) {
					held.add(r.admission());
				}
			}
			String snapshot = JsonUtil.toJson(ledger.snapshot());
			int snapshotBytes = snapshot.getBytes(StandardCharsets.UTF_8).length;
			System.out.println("[D7][capacity] nodes=" + nodes + " tasks=" + held.size()
					+ " snapshotBytes=" + snapshotBytes + " admitted=" + ledger.snapshot().get("admittedTasks")
					+ " payloadBytes=" + ledger.snapshot().get("payloadBytes"));
			assertTrue(snapshotBytes > 0);
			held.forEach(PushCapacityLedger.Admission::release);
			assertEquals(0L, number(ledger.snapshot().get("payloadBytes")), "释放后不留载荷残留");
		}
	}

	@Test
	@DisplayName("D7：单次准入开销（纳秒级，固定输入可复跑）")
	void admissionOverhead() {
		PushCapacityLedger ledger = new PushCapacityLedger(64, 8192, 64, 1 << 30, 64);
		String payload = "{\"type\":\"im.message\",\"seq\":1}";
		// 预热避免类加载/JIT 计入读数；预热占用同样登记，最后一起归还，否则残留会计入载荷。
		List<PushCapacityLedger.Admission> held = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			PushCapacityLedger.Reservation r = ledger.reserve("node-" + (i % 64), payload);
			if (r.admitted()) {
				held.add(r.admission());
			}
		}
		long start = System.nanoTime();
		for (long i = 0; i < ADMISSION_SAMPLES; i++) {
			PushCapacityLedger.Reservation r = ledger.reserve("node-" + (i % 64), payload);
			if (r.admitted()) {
				held.add(r.admission());
			}
		}
		long elapsed = System.nanoTime() - start;
		double perAdmissionMicros = (double) elapsed / ADMISSION_SAMPLES / 1000.0;
		System.out.printf("[D7][capacity] 准入开销: samples=%d totalMs=%.1f perAdmission=%.2fus admitted=%d%n",
				ADMISSION_SAMPLES, elapsed / 1_000_000.0, perAdmissionMicros, held.size());
		// 只钉住量级：准入不得退化成毫秒级串行瓶颈（真实 p95 仍由 D7 冻结记录决定）。
		assertTrue(perAdmissionMicros < 500.0, "单次准入 " + perAdmissionMicros + "µs 已进入毫秒级，需重新测量");
		assertEquals((long) ledger.snapshot().get("admittedTasks"), (long) held.size(), "每个准入都有对应持有者");
		held.forEach(PushCapacityLedger.Admission::release);
		assertEquals(0L, number(ledger.snapshot().get("payloadBytes")), "释放后无残留");
	}

	@Test
	@DisplayName("D7：关闭后拒绝不泄漏额度（在途按原路径结算）")
	void closeSettlesWithoutLeaking() {
		PushCapacityLedger ledger = new PushCapacityLedger(4, 16, 4, 1 << 20, 8);
		PushCapacityLedger.Admission held = ledger.reserve("a", "x").admission();
		assertNull(ledger.snapshot().get("enabled"), "账本快照不冒充 PushService 的 enabled 键");
		ledger.close();
		assertEquals(PushCapacityLedger.Reject.CLOSED, ledger.reserve("b", "y").reject());
		assertEquals(PushCapacityLedger.Reject.CLOSED, ledger.reserveRetry("delay").reject());
		held.release();
		held.release(); // 幂等：重复结算不得虚增容量
		assertEquals(1L, number(ledger.snapshot().get("releasedTasks")));
		System.out.println("[D7][capacity] 关闭结算: closed=" + ledger.snapshot().get("closed")
				+ " releasedTasks=" + ledger.snapshot().get("releasedTasks")
				+ " rejections=" + JsonUtil.toJson(ledger.snapshot().get("rejections")));
	}
}
