package com.luohuo.flex.im.core.user.service.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #486 T5：容量账本的维度、边界与释放语义。
 *
 * <p>这些用例只验证账本本身（准入/拒绝/归还/关闭竞争），不涉及 MQ；
 * 公共入口的行为由 {@code PushServiceTest} 的容量用例覆盖。
 */
class PushCapacityLedgerTest {

	@Test
	void admitsUntilEachDimensionIsSpentThenReportsThatDimension() {
		// 2 节点、全局 4、每节点 2、载荷 1MB：所有维度都在同一处定稿。
		PushCapacityLedger ledger = new PushCapacityLedger(2, 4, 2, 1_048_576, 1);
		Object payload = Map.of("k", "v");

		PushCapacityLedger.Reservation first = ledger.reserve("node-a", payload);
		PushCapacityLedger.Reservation second = ledger.reserve("node-a", payload);
		assertTrue(first.admitted(), "同节点前两个任务应被接收");
		assertTrue(second.admitted());
		// 第三个同节点任务：节点队列维度先耗尽。
		PushCapacityLedger.Reservation third = ledger.reserve("node-a", payload);
		assertFalse(third.admitted());
		assertEquals(PushCapacityLedger.Reject.NODE_QUEUE_FULL, third.reject());

		// 其他节点仍可用：热点节点不吃掉别人的额度。
		assertTrue(ledger.reserve("node-b", payload).admitted());
		assertTrue(ledger.reserve("node-b", payload).admitted());
		// 全局 4 已满。
		PushCapacityLedger.Reservation global = ledger.reserve("node-b", payload);
		assertFalse(global.admitted());
		assertEquals(PushCapacityLedger.Reject.GLOBAL_TASK_LIMIT, global.reject());

		first.admission().release();
		second.admission().release();
		third = ledger.reserve("node-a", payload);
		assertTrue(third.admitted(), "释放后额度应可再次使用");
	}

	@Test
	void sharedPayloadCountsOnceAndIsReclaimedOnlyAfterAllHoldersRelease() {
		// 载荷 12 字节预算 + 4 个任务额度；同一 payload 只要计一次（不是两份 24）。
		Object payload = "0123456789";
		int bytes = 12; // "0123456789" 序列化后带引号
		PushCapacityLedger ledger = new PushCapacityLedger(4, 8, 4, bytes, 4);

		PushCapacityLedger.Reservation a = ledger.reserve("n1", payload);
		PushCapacityLedger.Reservation b = ledger.reserve("n2", payload);
		assertTrue(a.admitted() && b.admitted());
		assertEquals(bytes, ledger.snapshot().get("payloadBytes"), "同对象只按 wire 字节计一次");

		// 不同对象（同内容）是不同载荷：预算不足时明确拒绝，而不是覆盖。
		PushCapacityLedger.Reservation other = ledger.reserve("n3", new String("0123456789"));
		assertFalse(other.admitted());
		assertEquals(PushCapacityLedger.Reject.PAYLOAD_LIMIT, other.reject());

		a.admission().release();
		assertEquals(bytes, ledger.snapshot().get("payloadBytes"), "仍有持有者时不得扣回字节");
		b.admission().release();
		assertEquals(0, ledger.snapshot().get("payloadBytes"));
		assertTrue(ledger.reserve("n3", payload).admitted(), "全部释放后字节额度可复用");
	}

	@Test
	void overPayloadBudgetIsRefusedWithoutLeakingOtherDimensions() {
		PushCapacityLedger ledger = new PushCapacityLedger(2, 2, 2, 8, 2);
		PushCapacityLedger.Reservation tooBig = ledger.reserve("n1", "0123456789012345678901234567890123456789");
		assertFalse(tooBig.admitted());
		assertEquals(PushCapacityLedger.Reject.PAYLOAD_LIMIT, tooBig.reject());
		// 拒绝不得留下半占：全局 permit、节点计数、载荷字节都应回滚。
		assertEquals(2, ledger.snapshot().get("globalFreePermits"));
		assertEquals(0, ledger.snapshot().get("payloadBytes"));
		assertEquals(0L, ((Map<?, ?>) ledger.snapshot().get("nodeTasks")).size(), "被拒不得留下节点 slot");
	}

	@Test
	void closedLedgerRefusesNewWorkButStillSettlesOutstanding() {
		PushCapacityLedger ledger = new PushCapacityLedger(2, 2, 2, 1024, 2);
		PushCapacityLedger.Reservation outstanding = ledger.reserve("n1", "x");
		assertTrue(outstanding.admitted());

		ledger.close();
		PushCapacityLedger.Reservation afterClose = ledger.reserve("n1", "x");
		assertFalse(afterClose.admitted());
		assertEquals(PushCapacityLedger.Reject.CLOSED, afterClose.reject());
		assertEquals(PushCapacityLedger.Reject.CLOSED, ledger.reserveRetry("delay").reject());

		// 在途任务照常结算：关闭不等于提前释放物理占用。
		outstanding.admission().release();
		assertEquals(1L, ledger.snapshot().get("releasedTasks"));
	}

	@Test
	void retrySlotsAreBoundedAndIndependentFromNodeSlots() {
		PushCapacityLedger ledger = new PushCapacityLedger(2, 10, 10, 1024, 1);
		PushCapacityLedger.Reservation retry = ledger.reserveRetry("delay");
		assertTrue(retry.admitted());
		assertEquals(PushCapacityLedger.Reject.RETRY_LIMIT, ledger.reserveRetry("direct").reject());
		// 节点任务不受重试额度影响。
		assertTrue(ledger.reserve("n1", null).admitted());
		retry.admission().release();
		assertTrue(ledger.reserveRetry("direct").admitted());
	}

	@Test
	void activeNodeCapReclaimsSlotsOnceQueuesAreEmpty() {
		PushCapacityLedger ledger = new PushCapacityLedger(1, 4, 1, 1024, 1);
		PushCapacityLedger.Reservation only = ledger.reserve("n1", null);
		assertTrue(only.admitted());
		assertEquals(PushCapacityLedger.Reject.NODE_LIMIT, ledger.reserve("n2", null).reject(),
				"活跃节点数超上限时明确拒绝，而不是默默共享执行器");

		only.admission().release();
		assertTrue(ledger.reserve("n2", null).admitted(), "节点队列清空后 slot 应被回收，churn 不会只涨不落");
	}

	@Test
	void releaseIsIdempotentSoDoubleSettlementCannotInflateCapacity() {
		PushCapacityLedger ledger = new PushCapacityLedger(2, 2, 2, 1024, 1);
		PushCapacityLedger.Admission admission = ledger.reserve("n1", null).admission();
		assertNotNull(admission);
		admission.release();
		admission.release();
		assertEquals(1L, ledger.snapshot().get("releasedTasks"));
		// 两次释放只归还一份额度：全局 2 里应只剩「那次准入」被归还，恰好回到 2，不多给。
		assertEquals(2, ledger.snapshot().get("globalFreePermits"));
	}

	@Test
	void rejectionsAreCountedPerReasonForMeasurement() {
		PushCapacityLedger ledger = new PushCapacityLedger(1, 1, 1, 8, 0);
		ledger.reserve("n1", "x");
		ledger.reserveRetry("delay");
		ledger.reserve("n1", "x");
		@SuppressWarnings("unchecked")
		Map<String, Long> rejections = (Map<String, Long>) ledger.snapshot().get("rejections");
		assertEquals(1L, rejections.get("RETRY_LIMIT"));
		assertTrue(rejections.containsKey("NODE_QUEUE_FULL") || rejections.containsKey("GLOBAL_TASK_LIMIT"),
				"拒绝必须按维度可数，供 D7 测量区分原因");
	}

	@Test
	void concurrentReservationsNeverOverAdmit() throws Exception {
		int nodes = 4;
		int perNode = 3;
		PushCapacityLedger ledger = new PushCapacityLedger(nodes, nodes * perNode, perNode, 1 << 20, 8);
		ExecutorService pool = Executors.newFixedThreadPool(16);
		AtomicInteger admitted = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		List<PushCapacityLedger.Admission> held = new ArrayList<>();
		try {
			for (int i = 0; i < 200; i++) {
				String nodeId = "n" + (i % nodes);
				pool.execute(() -> {
					try {
						start.await(2, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					PushCapacityLedger.Reservation reservation = ledger.reserve(nodeId, "payload");
					if (reservation.admitted()) {
						admitted.incrementAndGet();
						synchronized (held) {
							held.add(reservation.admission());
						}
					} else {
						refused.incrementAndGet();
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发准入应在预算内完成");
		} finally {
			pool.shutdownNow();
		}
		assertEquals(nodes * perNode, admitted.get(), "并发下不得超发额度");
		assertEquals(200 - nodes * perNode, refused.get());
		assertEquals(0, ledger.snapshot().get("globalFreePermits"), "满额时全局 permit 应精确耗尽");
		held.forEach(PushCapacityLedger.Admission::release);
		assertEquals(nodes * perNode, ledger.snapshot().get("globalFreePermits"), "全部释放后应完全复位");
	}
}
