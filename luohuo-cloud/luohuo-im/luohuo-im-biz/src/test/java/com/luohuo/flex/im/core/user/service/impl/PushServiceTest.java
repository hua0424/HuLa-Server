package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.router.NacosRouterService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#220: {@link PushService#sendAsync} MQ 发送失败延迟重试 回归测试。
 *
 * <p>覆盖两点：
 * <ul>
 *   <li><b>重试</b>——{@code mqProducer.sendMsg}（PUSH_TOPIC 正常推送）抛异常时，内部捕获并升级
 *       log.warn + 调用 {@code scheduleDelayRetry} 复用 PUSH_DELAY_TOPIC 延迟重试机制；</li>
 *   <li><b>防递归</b>——retry=true（PUSH_DELAY_TOPIC 延迟发送本身）失败时抛回异常交由上层
 *       {@code executeRetry} 的 whenComplete 按 retryCount 递增重试（受 maxRetryCount 上限约束），
 *       不在内部自调 scheduleDelayRetry，避免无限递归；</li>
 *   <li><b>对照</b>——MQ 发送成功时不触发延迟重试。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushServiceTest {

	@Mock private MQProducer mqProducer;
	@Mock private NacosRouterService routerService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private ScheduledExecutorService retryScheduler;
	@Mock private ExecutorService nodeExecutor;

	private PushService pushService;

	@BeforeEach
	void setUp() {
		pushService = new PushService();
		ReflectionTestUtils.setField(pushService, "mqProducer", mqProducer);
		ReflectionTestUtils.setField(pushService, "routerService", routerService);
		ReflectionTestUtils.setField(pushService, "cachePlusOps", cachePlusOps);
		// @Value 配置（Nacos 配置默认值同源；shutdown-budget-seconds 为 #451 占位预算，见 #450 §2）
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 30L);
		ReflectionTestUtils.setField(pushService, "maxRetryCount", 3);
		ReflectionTestUtils.setField(pushService, "delaySeconds", 1);
		pushService.init();
		// 用 mock 替换延迟调度器与节点执行器（避免真实线程阻塞测试 JVM 退出）
		ReflectionTestUtils.setField(pushService, "retryScheduler", retryScheduler);
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> nodeExecutors =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		nodeExecutors.put("node-1", nodeExecutor);
		// 同步执行：runAsync 的任务在调用线程直接运行，异常进入 future 而非线程逃逸
		doAnswer(inv -> {
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(nodeExecutor).execute(any());
	}

	private WsBaseResp<?> msg() {
		WsBaseResp<?> msg = new WsBaseResp<>();
		msg.setType("test-type");
		return msg;
	}

	@Test
	@DisplayName("重试：sendAsync 内 MQ 发送失败 → 捕获并调度有界直发重试（PUSH_TOPIC 直接重发，不走 PUSH_DELAY_TOPIC）")
	void sendAsync_schedulesDirectResendWhenMqSendFails() {
		// given: 路由命中 node-1；MQ 发送抛异常
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-1", Map.of("client-1", 100L)));
		doThrow(new RuntimeException("syncSend timeout")).when(mqProducer).sendMsg(anyString(), any());

		// when
		pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false).join();

		// then: 触发直发重试调度（delaySeconds=1s）
		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
		verify(retryScheduler).schedule(ArgumentMatchers.<Runnable>any(), eq(1L), eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("有界直发：MQ 持续失败时重试计数递增，到 maxRetryCount 停手，不无限循环")
	void sendAsync_directResendIsBoundedByMaxRetryCount() {
		// given: 路由命中 node-1；MQ 发送持续抛异常；调度任务同步执行（重试递归在同一调用栈展开）
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-1", Map.of("client-1", 100L)));
		doThrow(new RuntimeException("syncSend timeout")).when(mqProducer).sendMsg(anyString(), any());
		doAnswer(inv -> {
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(retryScheduler).schedule(ArgumentMatchers.<Runnable>any(), anyLong(), any());

		// when
		pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false).join();

		// then: 初始 1 次 + 重试 3 次共 4 次发送尝试；第 4 次重试请求超过 maxRetryCount=3 停手（log.error）
		verify(mqProducer, times(4)).sendMsg(anyString(), any());
		verify(retryScheduler, times(3)).schedule(ArgumentMatchers.<Runnable>any(), anyLong(), any());
	}

	@Test
	@DisplayName("防递归：retry=true（PUSH_DELAY_TOPIC 延迟发送本身）失败时抛回上层按 retryCount 递增重试，内部不自调 scheduleDelayRetry")
	void sendAsync_retryBranchRethrowsWithoutSelfScheduling() {
		// given: 路由命中 node-1；延迟发送抛异常
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-1", Map.of("client-1", 100L)));
		doThrow(new RuntimeException("syncSend timeout")).when(mqProducer).sendMsgWithDelay(anyString(), any(), anyInt());

		// when: future 异常完成（由 executeRetry.whenComplete 按 retryCount+1 继续，受 maxRetryCount 约束）
		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L, 100L, true);

		// then
		assertThrows(CompletionException.class, future::join);
		verify(retryScheduler, never()).schedule(ArgumentMatchers.<Runnable>any(), anyLong(), any());
	}

	@Test
	@DisplayName("对照：MQ 发送成功时不触发延迟重试")
	void sendAsync_noRetryWhenMqSendSucceeds() {
		// given: 路由命中 node-1；MQ 发送正常
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-1", Map.of("client-1", 100L)));

		// when
		pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false).join();

		// then: 无延迟重试调度
		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-1"), any());
		verify(retryScheduler, never()).schedule(ArgumentMatchers.<Runnable>any(), anyLong(), any());
	}

	@Test
	@DisplayName("#451：停止期外部发送被准入拒绝——失败 Future，不阻塞、不假成功")
	void sendAsync_refusedAfterDestroy() {
		// given: 已停止（空排空，直接完成）
		pushService.destroy();

		// when
		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false);

		// then: 明确失败，不投递 MQ
		CompletionException e = assertThrows(CompletionException.class, future::join);
		assertTrue(e.getCause() instanceof PushService.PushDrainException);
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	@Test
	@DisplayName("#451：排空不丢已接纳——在途任务完成后再退出，Future 正常结束")
	void destroy_waitsForInFlight() throws Exception {
		// given: 真实单线程池 + 阻塞型 MQ，任务已开始但未完成
		ExecutorService real = Executors.newSingleThreadExecutor();
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-block", real);
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-block", Map.of("client-1", 100L)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(inv -> {
			entered.countDown();
			release.await();
			return null;
		}).when(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-block"), any());

		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		// when: 另一线程 destroy，排空应等待而非丢弃；在途完成后 destroy 返回
		CompletableFuture<Void> draining = CompletableFuture.runAsync(pushService::destroy);
		Thread.sleep(200);
		assertTrue(!draining.isDone());
		release.countDown();
		draining.get(10, TimeUnit.SECONDS);

		// then: 在途正常完成，恰发送一次
		future.join();
		verify(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-block"), any());
	}

	@Test
	@DisplayName("#451：截止后未开始任务记未执行——shutdownNow 丢弃的排队任务明确失败")
	void destroy_unstartedTaskSettledAsUnexecuted() throws Exception {
		// given: 预算 0，单线程池被占位任务卡住，节点发送排队未开始
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 0L);
		ExecutorService real = Executors.newSingleThreadExecutor();
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-q", real);
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-q", Map.of("client-1", 100L)));
		CountDownLatch occupantRunning = new CountDownLatch(1);
		real.execute(() -> {
			occupantRunning.countDown();
			try {
				Thread.sleep(30_000);
			} catch (InterruptedException expected) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(occupantRunning.await(5, TimeUnit.SECONDS));
		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false);

		// when
		pushService.destroy();

		// then: 未执行，且从未投递 MQ
		CompletionException e = assertThrows(CompletionException.class, future::join);
		assertTrue(e.getCause() instanceof PushService.PushDrainException);
		assertTrue(e.getCause().getMessage().contains("未执行"));
		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	@Test
	@DisplayName("#451：截止时已开始未获结果记结果不确定——不响应中断的调用停等后明示")
	void destroy_startedButUnresponsiveTaskSettledAsUncertain() throws Exception {
		// given: 预算 0；MQ 调用吞中断、永不返回（released 兜底放行防泄漏）
		ReflectionTestUtils.setField(pushService, "shutdownBudgetSeconds", 0L);
		ExecutorService real = Executors.newSingleThreadExecutor();
		@SuppressWarnings("unchecked")
		Map<String, ExecutorService> pools =
				(Map<String, ExecutorService>) ReflectionTestUtils.getField(pushService, "nodeExecutors");
		pools.put("node-r", real);
		when(routerService.findNodeDeviceUser(anyList()))
				.thenReturn(Map.of("node-r", Map.of("client-1", 100L)));
		AtomicBoolean released = new AtomicBoolean(false);
		CountDownLatch entered = new CountDownLatch(1);
		doAnswer(inv -> {
			entered.countDown();
			while (!released.get()) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ignored) {
					// 吞中断：模拟不响应中断的底层调用
				}
			}
			return null;
		}).when(mqProducer).sendMsg(eq(MqConstant.PUSH_TOPIC + "node-r"), any());

		CompletableFuture<Void> future = pushService.sendAsync(msg(), List.of(100L), 0L, 100L, false);
		assertTrue(entered.await(5, TimeUnit.SECONDS));

		// when
		try {
			pushService.destroy();

			// then
			CompletionException e = assertThrows(CompletionException.class, future::join);
			assertTrue(e.getCause() instanceof PushService.PushDrainException);
			assertTrue(e.getCause().getMessage().contains("结果不确定"));
		} finally {
			released.set(true);
			real.shutdownNow();
		}
	}
}
