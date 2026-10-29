package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.utils.ShutdownDrainClock;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.user.service.dto.PushReceipt;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.router.NacosRouterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * huaaichat/aichatoverview#482：推送提交回执（{@link PushSubmission}）与业务提交点接线回归。
 *
 * <p>锁住四类事实，避免下轮把“提交未全部被接收”读成“送达”或“业务失败”：</p>
 * <ul>
 *   <li>准入聚合只在真实提交集合上成立：ALL / PARTIAL / NONE 之外，无在线目标（NO_TARGET）
 *       与路由失败（FAILED）单独上报，不会被 allMatch 折叠成“全部送达”；</li>
 *   <li>未接收目标可定位（只列被拒设备与路由失败 uid），重投不扩大到已接收目标；</li>
 *   <li>首投结果独立异步回填，只回填一次，也不代表重试/ACK 的最终送达；</li>
 *   <li>提交时点：事务内业务回滚不产生推送任务，提交后才提交；无事务时直接提交。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushSubmissionTest {

	private static final Long UID = 100L;
	private static final Long OTHER_UID = 200L;
	private static final Long ACTOR = 100L;

	@Mock private MQProducer mqProducer;
	@Mock private NacosRouterService routerService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private ScheduledExecutorService retryScheduler;
	@Mock private ExecutorService nodeExecutor;
	@Mock private ExecutorService rejectingExecutor;

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
		pushService.init();
		ReflectionTestUtils.setField(pushService, "retryScheduler", retryScheduler);
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> nodeExecutors =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-1", nodeExecutor);
		nodeExecutors.put("node-2", rejectingExecutor);
		// node-1 同步执行（异常进入 future 而非线程逃逸）；node-2 模拟可控的提交拒绝
		doAnswer(inv -> {
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(nodeExecutor).execute(any());
		doThrow(new RejectedExecutionException("executor closed")).when(rejectingExecutor).execute(any());
	}

	@AfterEach
	void tearDown() {
		ShutdownDrainClock.reset();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	private WsBaseResp<?> msg() {
		WsBaseResp<?> msg = new WsBaseResp<>();
		msg.setType("test-type");
		return msg;
	}

	// ==================== V3：准入聚合与目标定位 ====================

	@Test
	@DisplayName("全部目标被接收：ALL，可称本次提交未被拒，且不产生同步未完成提示")
	void submitPush_allAccepted_admissionAll() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID, "client-2", OTHER_UID)));

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID, OTHER_UID), ACTOR);

		assertEquals(PushSubmission.RouteStatus.RESOLVED, submission.getRouteStatus());
		assertEquals(PushSubmission.Admission.ALL, submission.getAdmission());
		assertTrue(submission.isFullyAdmitted());
		assertFalse(submission.isSyncIncomplete(), "全部被接收时不得再提示同步未完成");
		assertTrue(submission.getUnreceivedUids().isEmpty());
		assertEquals(2, submission.getTargets().size(), "逐设备目标不应被 uid 去重折叠");
	}

	@Test
	@DisplayName("部分被拒：PARTIAL，只定位未接收目标，已接收目标不被列入重投范围")
	void submitPush_partialAdmission_locatesOnlyRejected() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID), "node-2", Map.of("client-2", OTHER_UID)));

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID, OTHER_UID), ACTOR);

		assertEquals(PushSubmission.RouteStatus.RESOLVED, submission.getRouteStatus());
		assertEquals(PushSubmission.Admission.PARTIAL, submission.getAdmission());
		assertTrue(submission.isSyncIncomplete());
		assertFalse(submission.isFullyAdmitted());
		assertEquals(List.of(OTHER_UID), submission.getUnreceivedUids(),
				"只列被拒目标；已接收目标不得进入补偿范围");
		assertEquals(1, submission.getRejectedTargets().size());
		assertEquals("client-2", submission.getRejectedTargets().get(0).getDeviceId());
	}

	@Test
	@DisplayName("路由查询失败：FAILED 单独上报，不伪造设备目标、不被读成 ALL")
	void submitPush_routeFailure_reportsFailed() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenThrow(new IllegalStateException("router unavailable"));

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		assertEquals(PushSubmission.RouteStatus.FAILED, submission.getRouteStatus());
		assertNull(submission.getAdmission(), "路由失败没有准入事实，不能被聚合读成全部成功");
		assertTrue(submission.getTargets().isEmpty(), "不得为未解析 uid 编造设备目标");
		assertEquals(List.of(UID), submission.getUnresolvedUids());
		assertEquals(List.of(UID), submission.getUnreceivedUids());
		assertFalse(submission.isFullyAdmitted());
		assertFalse(submission.isSyncIncomplete(), "路由失败是单独事实，不当成用户端同步失败提示");
		assertNotNull(submission.getRouteFailure());
	}

	@Test
	@DisplayName("无在线目标：NO_TARGET 单独上报，不等于已送达也不等于业务失败")
	void submitPush_noTarget_reportsNoTarget() {
		when(routerService.findNodeDeviceUser(anyList(), any())).thenReturn(Map.of());

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		assertEquals(PushSubmission.RouteStatus.NO_TARGET, submission.getRouteStatus());
		assertNull(submission.getAdmission());
		assertFalse(submission.isFullyAdmitted());
		assertFalse(submission.isSyncIncomplete());
	}

	@Test
	@DisplayName("停止期拒绝：明确拒绝事实被记录，不静默返回成功")
	void submitPush_shutdownRefused_recordsRejection() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		pushService.destroy();

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		assertEquals(PushSubmission.RouteStatus.NO_TARGET, submission.getRouteStatus());
		assertNull(submission.getAdmission(), "停止期未解析目标，不得编造准入聚合");
		assertNotNull(submission.getAdmissionRejection(), "停止期拒绝原因必须随回执返回");
		assertFalse(submission.isFullyAdmitted());
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	@Test
	@DisplayName("批次不被截断：多节点多设备逐目标都进回执，单节点被拒不丢其它目标")
	void submitPush_batchNotTruncated() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID), "node-2", Map.of("client-2", OTHER_UID)));

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID, OTHER_UID), ACTOR);

		assertEquals(2, submission.getTargets().size());
		assertEquals(1, submission.getRejectedTargets().size());
		assertEquals(PushSubmission.Admission.PARTIAL, submission.getAdmission());
		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
		verify(mqProducer, never()).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-2"), any());
	}

	// ==================== V5：首投结果独立回填 ====================

	@Test
	@DisplayName("首投成功：firstAttempt=SUCCESS，仅代表本次 MQ 首投，不承诺重试与 ACK 之后的结果")
	void submitPush_firstAttemptSuccess() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		assertEquals(PushSubmission.FirstAttempt.SUCCESS, submission.getTargets().get(0).getFirstAttempt());
		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
	}

	@Test
	@DisplayName("首投明确失败：firstAttempt=DEFINITE_FAIL，且不改写准入结论")
	void submitPush_firstAttemptDefiniteFail() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		doThrow(new RuntimeException("broker rejected")).when(mqProducer).sendMsg(anyString(), any());

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		PushSubmission.Target target = submission.getTargets().get(0);
		assertEquals(PushSubmission.TargetState.ACCEPTED, target.getState(), "首投失败不改写准入结论");
		assertEquals(PushSubmission.FirstAttempt.DEFINITE_FAIL, target.getFirstAttempt());
		// 重试链存在不等于首投结果可被当成最终送达
		verify(retryScheduler, times(1)).schedule(ArgumentMatchers.<Runnable>any(), eq(1L), any());
	}

	@Test
	@DisplayName("首投超时：firstAttempt=UNCERTAIN——broker 可能已接收，不能表述为确定失败")
	void submitPush_firstAttemptTimeoutIsUncertain() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		doThrow(new RuntimeException(new TimeoutException("syncSend timeout")))
				.when(mqProducer).sendMsg(anyString(), any());

		PushSubmission submission = pushService.submitPush(msg(), List.of(UID), ACTOR);

		assertEquals(PushSubmission.FirstAttempt.UNCERTAIN, submission.getTargets().get(0).getFirstAttempt(),
				"超时可能已被 broker 接收，不能记成确定失败");
	}

	@Test
	@DisplayName("首投结果只回填一次：后续重试结果不覆盖已定稿的首投事实")
	void firstAttemptIsWriteOnce() {
		PushSubmission submission =
				PushSubmission.resolved(PushSubmission.targetsOf("node-1", Map.of("client-1", UID)));
		PushSubmission.Target target = submission.getTargets().get(0);

		target.markFirstAttempt(PushSubmission.FirstAttempt.SUCCESS, null);
		target.markFirstAttempt(PushSubmission.FirstAttempt.DEFINITE_FAIL, "late retry failed");

		assertEquals(PushSubmission.FirstAttempt.SUCCESS, target.getFirstAttempt());
		assertNull(target.getFirstAttemptReason());
	}

	// ==================== V2：提交时点 ====================

	@Test
	@DisplayName("事务内提交：真实提交后才产生推送任务")
	void submitAfterCommit_insideTx_waitsForCommit() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		PushReceipt receipt = new PushReceipt();

		pushService.submitAfterCommit(receipt, msg(), List.of(UID), ACTOR);

		verify(mqProducer, never()).sendMsg(anyString(), any());
		assertTrue(receipt.isEmpty(), "提交回调尚未执行时不得写入回执");

		TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);

		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
		assertFalse(receipt.isEmpty());
		assertTrue(receipt.snapshot().isFullyAdmitted());
	}

	@Test
	@DisplayName("事务回滚：不产生推送任务，回执保持空——未提交业务不得表述为已同步")
	void submitAfterCommit_rollback_producesNothing() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		PushReceipt receipt = new PushReceipt();

		pushService.submitAfterCommit(receipt, msg(), List.of(UID), ACTOR);
		TransactionSynchronizationManager.getSynchronizations()
				.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		verify(mqProducer, never()).sendMsg(anyString(), any());
		assertTrue(receipt.isEmpty());
	}

	@Test
	@DisplayName("无事务提交点：直接提交，不注册永不执行的提交回调")
	void submitAfterCommit_noTx_submitsDirectly() {
		when(routerService.findNodeDeviceUser(anyList(), any()))
				.thenReturn(Map.of("node-1", Map.of("client-1", UID)));
		PushReceipt receipt = new PushReceipt();

		pushService.submitAfterCommit(receipt, msg(), List.of(UID), ACTOR);

		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
		assertTrue(receipt.snapshot().isFullyAdmitted());
	}

	// ==================== 空回执判读与房间推送 ====================

	@Test
	@DisplayName("空快照不是“全部被接收”：none() 供无推送路径安全消费，不用 null 表达")
	void noneSnapshotIsNotEmpty() {
		assertFalse(PushSubmission.none().isFullyAdmitted(), "空快照不能被读成全部送达");
		assertNull(PushSubmission.none().getAdmission());
		assertTrue(PushSubmission.resolved(
				PushSubmission.targetsOf("node-1", Map.of("client-1", UID))).isFullyAdmitted());
	}

	@Test
	@DisplayName("房间推送回执：停止期拒绝同样返回明确拒绝事实，不静默返回")
	void submitPushRoom_shutdownRefused() {
		pushService.destroy();

		PushSubmission submission = pushService.submitPushRoom(UID, "userStateChange", Map.of("id", 1L));

		assertEquals(PushSubmission.RouteStatus.NO_TARGET, submission.getRouteStatus());
		assertNotNull(submission.getAdmissionRejection());
		assertFalse(submission.isFullyAdmitted());
	}

	@Test
	@DisplayName("房间推送回执：无在线群成员时 NO_TARGET，不误报为成功送达")
	void submitPushRoom_noTarget() {
		when(cachePlusOps.sMembers(any())).thenReturn(Set.of());

		PushSubmission submission = pushService.submitPushRoom(UID, "userStateChange", Map.of("id", 1L));

		assertEquals(PushSubmission.RouteStatus.NO_TARGET, submission.getRouteStatus());
		assertFalse(submission.isFullyAdmitted());
	}
}
