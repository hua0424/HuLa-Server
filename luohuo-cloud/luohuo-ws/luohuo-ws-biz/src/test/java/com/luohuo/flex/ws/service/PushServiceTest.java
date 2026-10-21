package com.luohuo.flex.ws.service;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.luohuo.basic.utils.ShutdownDrainClock;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.router.NacosRouterService;
import com.luohuo.flex.ws.config.ThreadPoolProperties;
import com.luohuo.flex.ws.websocket.SessionManager;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #461 WS PushService 有限排空生命周期测试（风格对齐 im PushServiceTest #451）：
 * 准入拒绝 / 未执行 vs 结果不确定结算 / 截止内干净排空 / destroy 幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushServiceTest {

	@Mock
	private ThreadPoolProperties threadPoolProperties;
	@Mock
	private RocketMQTemplate rocketMQTemplate;
	@Mock
	private NacosRouterService routerService;
	@Mock
	private SessionManager sessionManager;

	private PushService pushService;
	private ThreadPoolExecutor nodeExecutor;

	@BeforeEach
	void setUp() {
		// #461：destroy 会开启 JVM 级共享排空时钟，每个用例前后复位防静态污染
		ShutdownDrainClock.reset();
		pushService = new PushService();
		ReflectionTestUtils.setField(pushService, "threadPoolProperties", threadPoolProperties);
		ReflectionTestUtils.setField(pushService, "rocketMQTemplate", rocketMQTemplate);
		ReflectionTestUtils.setField(pushService, "routerService", routerService);
		ReflectionTestUtils.setField(pushService, "sessionManager", sessionManager);
		ReflectionTestUtils.setField(pushService, "nodeId", "self");
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 30L);
		// 预注入单线程池，覆盖懒建路径（destroy 只管理 map 内的池）
		nodeExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(),
				new ThreadFactoryBuilder().setNameFormat("node-1-test-%d").setDaemon(true).build());
		@SuppressWarnings("unchecked")
		Map<String, ThreadPoolExecutor> nodeExecutors =
				(Map<String, ThreadPoolExecutor>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-1", nodeExecutor);
		// 路由到远端 node-1：只走 MQ 路径，绕开本地 reactive 推送与 SessionManager
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-1", Map.of("device-1", 100L)));
	}

	@AfterEach
	void tearDown() {
		ShutdownDrainClock.reset();
		nodeExecutor.shutdownNow();
	}

	private WsBaseResp<?> msg() {
		return new WsBaseResp<>();
	}

	/** 停止外部准入后新提交明确拒绝：Future 入口失败完成，不再触达路由与执行器；MQ 销毁已到达。 */
	@Test
	void rejectsNewSubmissionsAfterDestroy() throws Exception {
		pushService.destroy();

		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L);

		ExecutionException e = assertThrows(ExecutionException.class, future::get);
		assertInstanceOf(PushService.PushDrainException.class, e.getCause());
		verify(routerService, never()).findNodeDeviceUser(anyList());
		verify(rocketMQTemplate).destroy();
	}

	/**
	 * 截止结算：排队未开始任务记未执行；已开始未完成任务记结果不确定。
	 * 模拟不响应中断的底层调用：parkNanos 被中断唤醒后循环重 park，直到测试显式放行。
	 */
	@Test
	void drainSettlesQueuedAsUnexecutedAndRunningAsUncertain() throws Exception {
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 0L);
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean release = new AtomicBoolean(false);
		doAnswer(inv -> {
			started.countDown();
			while (!release.get()) {
				LockSupport.parkNanos(1_000_000L);
			}
			return null;
		}).when(rocketMQTemplate).send(anyString(), any());

		CompletableFuture<Void> running = pushService.sendAsync(msg(), List.of(100L), 0L);
		assertTrue(started.await(2, TimeUnit.SECONDS), "第一个任务应已开始执行");
		CompletableFuture<Void> queued = pushService.sendAsync(msg(), List.of(101L), 0L);

		pushService.destroy();
		release.set(true);

		ExecutionException runningEx = assertThrows(ExecutionException.class, running::get);
		assertInstanceOf(PushService.PushDrainException.class, runningEx.getCause());
		assertTrue(runningEx.getCause().getMessage().contains("结果不确定"),
				() -> "已开始未完成应记结果不确定: " + runningEx.getCause().getMessage());

		ExecutionException queuedEx = assertThrows(ExecutionException.class, queued::get);
		assertInstanceOf(PushService.PushDrainException.class, queuedEx.getCause());
		assertTrue(queuedEx.getCause().getMessage().contains("未执行"),
				() -> "排队未开始应记未执行: " + queuedEx.getCause().getMessage());
	}

	/** 截止预算内在途任务完成：Future 正常完成，排空后准入保持关闭（新提交被拒）。 */
	@Test
	void drainWaitsForInFlightCompletionWithinBudget() throws Exception {
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 5L);
		doAnswer(inv -> null).when(rocketMQTemplate).send(anyString(), any());

		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L);
		pushService.destroy();

		assertTrue(future.isDone(), "截止内任务应完成");
		assertFalse(future.isCompletedExceptionally(), "正常完成的任务不应失败");

		CompletableFuture<Void> refused = pushService.sendAsync(msg(), List.of(100L), 0L);
		assertTrue(refused.isCompletedExceptionally(), "排空后新提交应被拒");
	}

	/** destroy 幂等：第二次调用直接跳过，不重复关池。 */
	@Test
	void destroyIsIdempotentPerPool() {
		ThreadPoolExecutor mockPool = mock(ThreadPoolExecutor.class);
		when(mockPool.shutdownNow()).thenReturn(new ArrayList<>());
		@SuppressWarnings("unchecked")
		Map<String, ThreadPoolExecutor> nodeExecutors =
				(Map<String, ThreadPoolExecutor>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-2", mockPool);

		pushService.destroy();
		pushService.destroy();

		verify(mockPool, times(1)).shutdown();
		verify(mockPool, times(1)).shutdownNow();
	}
}
