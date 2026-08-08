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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
		// @Value 配置（Nacos 配置默认值同源）
		ReflectionTestUtils.setField(pushService, "threadMultiplier", 2);
		ReflectionTestUtils.setField(pushService, "minThreads", 4);
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
}
