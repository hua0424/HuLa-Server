package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.utils.ShutdownDrainClock;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.router.NacosRouterService;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #486 T5：{@link PushService} 公共入口的有界推进行为。
 *
 * <p>边界：容量策略默认关闭时语义与既有完全一致；启用后超限必须产生**真实逐目标拒绝**
 * （不是静默丢、不是假装接收），健康节点不受其他节点超限牵连，重试通道有独立额度，
 * 关闭后不再接收新工作。默认值来自 #486 设计 v1 §4 的占位预算，数值冻结由 D7 决定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushServiceCapacityTest {

	@Mock private MQProducer mqProducer;
	@Mock private NacosRouterService routerService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private ScheduledExecutorService retryScheduler;
	@Mock private ExecutorService nodeExecutor;

	private PushService pushService;

	@BeforeEach
	void setUp() {
		ShutdownDrainClock.reset();
		pushService = new PushService();
		ReflectionTestUtils.setField(pushService, "mqProducer", mqProducer);
		ReflectionTestUtils.setField(pushService, "routerService", routerService);
		ReflectionTestUtils.setField(pushService, "cachePlusOps", cachePlusOps);
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 30L);
		ReflectionTestUtils.setField(pushService, "maxRetryCount", 3);
		ReflectionTestUtils.setField(pushService, "delaySeconds", 1);
		ReflectionTestUtils.setField(pushService, "pushSendTimeoutMillis", 10_000L);
		// 默认关闭：与既有语义对照
		ReflectionTestUtils.setField(pushService, "capacityEnabled", false);
		ReflectionTestUtils.setField(pushService, "capacityMaxActiveNodes", 8);
		ReflectionTestUtils.setField(pushService, "capacityMaxTotalTasks", 8);
		ReflectionTestUtils.setField(pushService, "capacityMaxTasksPerNode", 1);
		ReflectionTestUtils.setField(pushService, "capacityMaxPayloadBytes", 1 << 20);
		ReflectionTestUtils.setField(pushService, "capacityMaxConcurrentRetries", 2);
		ReflectionTestUtils.setField(pushService, "capacityMaxExpandRooms", 4);
		ReflectionTestUtils.setField(pushService, "capacityMaxExpandTargets", 8);
		ReflectionTestUtils.setField(pushService, "capacityExpandBudgetMillis", 2_000L);
		pushService.init();
		ReflectionTestUtils.setField(pushService, "retryScheduler", retryScheduler);
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> nodeExecutors =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-1", nodeExecutor);
		nodeExecutors.put("node-2", nodeExecutor);
		doAnswer(inv -> {
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(nodeExecutor).execute(any());
	}

	@AfterEach
	void tearDown() {
		ShutdownDrainClock.reset();
	}

	private WsBaseResp<?> msg() {
		WsBaseResp<?> msg = new WsBaseResp<>();
		msg.setType("test-type");
		return msg;
	}

	private void enableCapacity() {
		ReflectionTestUtils.setField(pushService, "capacityEnabled", true);
		pushService.init();
		ReflectionTestUtils.setField(pushService, "retryScheduler", retryScheduler);
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> nodeExecutors =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-1", nodeExecutor);
		nodeExecutors.put("node-2", nodeExecutor);
	}

	private void routeTo(Map<String, Map<String, Long>> routes) {
		when(routerService.findNodeDeviceUser(anyList(), any())).thenAnswer(inv -> {
			Object conflicts = inv.getArgument(1);
			if (conflicts instanceof java.util.Set<?> set) {
				@SuppressWarnings("unchecked")
				java.util.Set<String> typed = (java.util.Set<String>) set;
				typed.clear();
			}
			return routes;
		});
		when(routerService.findNodeDeviceUser(anyList())).thenReturn(routes);
	}

	/** 默认关闭：不因容量拒绝任何提交，快照如实报告未启用。 */
	@Test
	@DisplayName("默认关闭：容量策略不介入，快照标记未启用")
	void disabledByDefaultKeepsExistingSemantics() {
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));

		PushSubmission submission = pushService.submitPush(msg(), List.of(100L), 0L);

		assertTrue(submission.isFullyAdmitted(), "未启用时提交应全部接收");
		assertEquals(false, pushService.capacitySnapshot().get("enabled"));
		verify(mqProducer).sendMsg(anyString(), any());
	}

	/** 单节点任务额度用尽：同节点第二条被真实拒绝，健康节点不受牵连。 */
	@Test
	@DisplayName("启用：单节点额度用尽产生逐目标拒绝，健康节点照常接收")
	void perNodeLimitRejectsOnlyThatNode() {
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		// 占住唯一一个 node-1 额度（任务不执行：executor 换成"只登记不运行"）
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		ExecutorService parked = org.mockito.Mockito.mock(ExecutorService.class);
		pools.put("node-1", parked);
		pushService.submitPush(msg(), List.of(100L), 0L);

		PushSubmission rejected = pushService.submitPush(msg(), List.of(100L), 0L);

		assertFalse(rejected.isFullyAdmitted(), "超额提交不得报全部接收");
		assertEquals(1, rejected.getRejectedTargets().size());
		assertEquals(PushCapacityLedger.Reject.NODE_QUEUE_FULL.reason(),
				rejected.getRejectedTargets().get(0).getReason());
		assertNotNull(rejected.getAdmission(), "聚合结论只覆盖真实提交集合");

		// 健康节点仍有自己的额度
		routeTo(Map.of("node-2", Map.of("client-2", 200L)));
		PushSubmission healthy = pushService.submitPush(msg(), List.of(200L), 0L);
		assertTrue(healthy.isFullyAdmitted(), "另一节点不应被单节点超限牵连");
	}

	/** 全局额度用尽：任何节点都拒绝，且是明确拒绝而非静默丢弃。 */
	@Test
	@DisplayName("启用：全局额度用尽后所有节点拒绝，拒绝原因可区分")
	void globalLimitRejectsEveryNode() {
		ReflectionTestUtils.setField(pushService, "capacityMaxTasksPerNode", 8);
		ReflectionTestUtils.setField(pushService, "capacityMaxTotalTasks", 1);
		enableCapacity();
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-1", org.mockito.Mockito.mock(ExecutorService.class));
		pools.put("node-2", org.mockito.Mockito.mock(ExecutorService.class));
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		pushService.submitPush(msg(), List.of(100L), 0L);

		routeTo(Map.of("node-2", Map.of("client-2", 200L)));
		PushSubmission rejected = pushService.submitPush(msg(), List.of(200L), 0L);

		assertFalse(rejected.isFullyAdmitted());
		assertEquals(PushCapacityLedger.Reject.GLOBAL_TASK_LIMIT.reason(),
				rejected.getRejectedTargets().get(0).getReason());
	}

	/** 载荷预算：超大入参整节点不接收，且不占住其他维度额度。 */
	@Test
	@DisplayName("启用：载荷超预算时拒绝且不泄漏其他额度")
	void payloadBudgetRefusesWithoutLeakingCapacity() {
		// 预算 64B，载荷约 4KB：整次拒绝
		ReflectionTestUtils.setField(pushService, "capacityMaxPayloadBytes", 64);
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		WsBaseResp big = new WsBaseResp();
		big.setData("x".repeat(4096));

		PushSubmission submission = pushService.submitPush(big, List.of(100L), 0L);

		assertFalse(submission.isFullyAdmitted());
		assertEquals(PushCapacityLedger.Reject.PAYLOAD_LIMIT.reason(),
				submission.getRejectedTargets().get(0).getReason());
		Map<String, Object> snapshot = pushService.capacitySnapshot();
		assertEquals(0L, ((Number) snapshot.get("payloadBytes")).longValue(), "被拒载荷不得留在账本里");
		assertEquals(8L, ((Number) snapshot.get("globalFreePermits")).longValue(), "被拒载荷不得占住任务额度");
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	/** 直接重发通道：额度用尽时放弃本轮重排，不再无限自排队。 */
	@Test
	@DisplayName("启用：直发重试额度用尽即放弃本轮，不再重复排期")
	void directResendGivesUpWhenRetryBudgetIsSpent() {
		ReflectionTestUtils.setField(pushService, "capacityMaxConcurrentRetries", 0);
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		org.mockito.Mockito.doThrow(new RuntimeException("syncSend timeout"))
				.when(mqProducer).sendMsg(anyString(), any());

		pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false).join();

		verify(mqProducer).sendMsg(anyString(), any());
		verify(retryScheduler, never()).schedule(any(Runnable.class), anyLong(),
				any(java.util.concurrent.TimeUnit.class));
	}

	/** 延迟重试通道：额度用尽时结果是明确失败，不是静默成功。 */
	@Test
	@DisplayName("启用：延迟重试额度用尽时以明确拒绝结束，不伪装成已排队")
	void delayRetryRefusedIsAnExplicitFailure() {
		ReflectionTestUtils.setField(pushService, "capacityMaxConcurrentRetries", 0);
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));

		CompletionException error = assertThrows(CompletionException.class,
				() -> pushService.scheduleDelayRetry(msg(), List.of(100L), 0L, 100L, 1, "c1").join());

		assertTrue(error.getCause() instanceof PushService.PushDrainException);
		assertTrue(error.getCause().getMessage().startsWith(PushCapacityLedger.Reject.RETRY_LIMIT.reason()));
		verify(retryScheduler, never()).schedule(any(Runnable.class), anyLong(),
				any(java.util.concurrent.TimeUnit.class));
	}

	/** 设备身份冲突：冲突设备不得被算成已接收（无法唯一归属）。 */
	@Test
	@DisplayName("设备冲突：同一 deviceId 归属多个 uid 时如实记为拒绝，不冒充已接收")
	void deviceIdentityConflictIsRejectedNotAccepted() {
		ReflectionTestUtils.setField(pushService, "capacityEnabled", false);
		pushService.init();
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-1", nodeExecutor);
		when(routerService.findNodeDeviceUser(anyList(), any())).thenAnswer(inv -> {
			@SuppressWarnings("unchecked")
			java.util.Set<String> conflicts = (java.util.Set<String>) inv.getArgument(1);
			conflicts.add("node-1|client-shared");
			return Map.of("node-1", Map.of("client-shared", 100L));
		});

		PushSubmission submission = pushService.submitPush(msg(), List.of(100L, 200L), 0L);

		assertFalse(submission.isFullyAdmitted());
		assertTrue(submission.getRejectedTargets().stream()
						.allMatch(t -> "device_identity_conflict".equals(t.getReason())),
				"冲突设备必须带明确拒绝原因");
	}

	/** 关闭：停止后不再接收新工作，在途额度结算如实反映。 */
	@Test
	@DisplayName("关闭：容量账本关闭后新提交明确拒绝")
	void closeStopsAcceptingNewWork() {
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-1", org.mockito.Mockito.mock(ExecutorService.class));
		pushService.submitPush(msg(), List.of(100L), 0L);

		// 排空预算压到 1s：本用例关心额度结算与拒绝语义，不重复测 30s 排空时长
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 1L);
		pushService.destroy();

		@SuppressWarnings("unchecked")
		Map<String, Object> snapshot = pushService.capacitySnapshot();
		assertEquals(true, snapshot.get("closed"));
		assertNull(snapshot.get("enabled"), "启用后快照不再带 enabled 标记");
		PushSubmission afterClose = pushService.submitPush(msg(), List.of(100L), 0L);
		assertFalse(afterClose.isFullyAdmitted());
	}

	/** 关闭后仍能结算在途额度（不泄漏）。 */
	@Test
	@DisplayName("关闭后：既有在途任务结算后额度如实归还")
	void outstandingAdmissionsStillSettleAfterClose() {
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-1", org.mockito.Mockito.mock(ExecutorService.class));
		PushSubmission admitted = pushService.submitPush(msg(), List.of(100L), 0L);
		assertTrue(admitted.isFullyAdmitted());

		// 排空预算压到 1s：本用例关心额度结算与拒绝语义，不重复测 30s 排空时长
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 1L);
		pushService.destroy();

		@SuppressWarnings("unchecked")
		Map<String, Object> snapshot = pushService.capacitySnapshot();
		assertTrue(((Number) snapshot.get("releasedTasks")).longValue() >= 1L,
				"关闭必须结算在途任务，不得悬挂额度");
	}

	/** #486 T5：房间广播先做有界展开，超预算整次不接收，而不是先全量聚合再截断。 */
	@Test
	@DisplayName("广播：房间数超过展开预算时整次不接收，按「目标范围未知」报告")
	void roomBroadcastOverExpansionBudgetIsNotAccepted() {
		enableCapacity();
		// 5 个房间 > capacityMaxExpandRooms=4
		when(cachePlusOps.sMembers(any())).thenReturn(new java.util.LinkedHashSet<>(
				List.of("1", "2", "3", "4", "5")));

		PushSubmission submission = pushService.submitPushRoom(100L, "userStateChange", Map.of("id", 1L));

		assertFalse(submission.isFullyAdmitted());
		assertTrue(submission.getRouteFailure().startsWith("push_expand_over_budget"),
				"超展开预算必须带明确原因, got=" + submission.getRouteFailure());
		assertTrue(submission.getRouteFailure().contains("roomCount=5"));
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	/** #486 T5：展开出的目标数超预算同样整次失败，不截断成「部分已推送」。 */
	@Test
	@DisplayName("广播：目标数超过展开预算时整次不接收，不截断成部分接收")
	void roomBroadcastOverTargetBudgetIsNotAccepted() {
		enableCapacity();
		when(cachePlusOps.sMembers(any())).thenReturn(new java.util.LinkedHashSet<>(List.of("1")));
		when(cachePlusOps.sCard(any())).thenReturn(9L);
		java.util.Set<Object> targets = new java.util.LinkedHashSet<>();
		for (long uid = 1; uid <= 9; uid++) {
			targets.add(String.valueOf(uid));
		}
		// 第一次调用读房间列表，之后读在线成员；目标 9 > capacityMaxExpandTargets=8
		when(cachePlusOps.sMembers(any())).thenAnswer(inv -> {
			com.luohuo.basic.model.cache.CacheKey key = inv.getArgument(0);
			return key.getKey() != null && key.getKey().contains("group_members_online")
					? targets
					: new java.util.LinkedHashSet<>(List.of("1"));
		});

		PushSubmission submission = pushService.submitPushRoom(100L, "userStateChange", Map.of("id", 1L));

		assertFalse(submission.isFullyAdmitted());
		assertTrue(submission.getRouteFailure().contains("targetCount=9"),
				"必须报告真实目标数, got=" + submission.getRouteFailure());
		assertTrue(submission.getRouteFailure().contains("limit=8"));
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	/** #486 T5：预算内的广播仍按原语义派发（有界不等于禁用）。 */
	@Test
	@DisplayName("广播：预算内的展开照常派发，与既有语义一致")
	void roomBroadcastWithinBudgetStillDispatches() {
		enableCapacity();
		routeTo(Map.of("node-1", Map.of("client-1", 100L)));
		when(cachePlusOps.sCard(any())).thenReturn(2L);
		when(cachePlusOps.sMembers(any())).thenAnswer(inv -> {
			com.luohuo.basic.model.cache.CacheKey key = inv.getArgument(0);
			return key.getKey() != null && key.getKey().contains("group_members_online")
					? new java.util.LinkedHashSet<>(List.of("200", "300"))
					: new java.util.LinkedHashSet<>(List.of("1"));
		});

		PushSubmission submission = pushService.submitPushRoom(100L, "userStateChange", Map.of("id", 1L));

		assertTrue(submission.isFullyAdmitted(), "预算内不得额外拒绝, got=" + submission.getRouteFailure());
		verify(mqProducer).sendMsg(anyString(), any());
	}
}