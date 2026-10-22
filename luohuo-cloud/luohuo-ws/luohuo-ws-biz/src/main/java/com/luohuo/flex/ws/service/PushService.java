package com.luohuo.flex.ws.service;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.luohuo.basic.utils.ShutdownDrainClock;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.router.NacosRouterService;
import com.luohuo.flex.ws.config.ThreadPoolProperties;
import com.luohuo.flex.ws.websocket.SessionManager;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 消息推送升级版 [结合消息路由] ws服务专用
 * @author 乾乾
 *
 * <p>#461 有限排空：对齐 IM PushService（#451）生命周期语义——外部准入门 +
 * ShutdownDrainClock 统一单调截止 + 事件驱动等在途 + 截止结算（未执行/结果不确定）+ 如实汇总。
 */
@Slf4j
@Service
public class PushService {
	@Resource
	private ThreadPoolProperties threadPoolProperties;
	@Resource
	private RocketMQTemplate rocketMQTemplate;
	@Resource
	private NacosRouterService routerService;
	@Resource@Lazy
	private SessionManager sessionManager;

	@Value("${luohuo.node-id}")
	private String nodeId;

	/**
	 * 有限排空预算（秒），与 IM PushService 同 key。默认 30s 对齐
	 * {@code spring.lifecycle.timeout-per-shutdown-phase=30s}（#450 §2）；
	 * #461 V3 实测后随容器 stop_grace_period 一并重冻。
	 */
	@Value("${luohuo.push.shutdown-budget-seconds:30}")
	private long shutdownBudgetSeconds;

	// 本地推送调度器
	private final Scheduler localPushScheduler = Schedulers.newBoundedElastic(50, 1000, "local-push");

	/** 外部准入开关：destroy 关闭后新提交明确拒绝。 */
	private final java.util.concurrent.atomic.AtomicBoolean admissionOpen =
			new java.util.concurrent.atomic.AtomicBoolean(true);

	/** 在途提交跟踪：future → 记录。排空截止时逐个明确结束，不永久悬挂。 */
	private final Map<CompletableFuture<Void>, TrackedTask> inFlight = new ConcurrentHashMap<>();

	private final java.util.concurrent.atomic.AtomicLong admittedSends = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong completedSends = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong rejectedSubmits = new java.util.concurrent.atomic.AtomicLong();

	/** 停止期拒绝异常：Future 明确失败，不静默、不悬挂。 */
	public static class PushDrainException extends IllegalStateException {
		public PushDrainException(String message) {
			super(message);
		}
	}

	/** 在途任务记录：包装任务用于区分 shutdownNow 丢弃的未开始任务与已开始任务。 */
	private static final class TrackedTask {
		final Runnable body;
		final String kind;
		final String nodeId;

		TrackedTask(Runnable body, String kind, String nodeId) {
			this.body = body;
			this.kind = kind;
			this.nodeId = nodeId;
		}
	}

	private static PushDrainException rejected(String what) {
		return new PushDrainException("WS推送已停止外部准入，拒绝" + what + "（#461 有限排空）");
	}

	/**
	 * 单用户推送
	 * @param msg 消息内容
	 * @param uid 目标用户ID
	 * @param cuid 操作人ID
	 */
	public void sendPushMsg(WsBaseResp<?> msg, Long uid, Long cuid) {
		sendPushMsg(msg, java.util.Arrays.asList(uid), cuid);
	}

	/**
	 * 将消息推送到对应的用户
	 * @param msg 消息内容
	 * @param uidList 目标用户ID列表
	 * @param cuid 操作人ID
	 */
	public void sendPushMsg(WsBaseResp<?> msg, List<Long> uidList, Long cuid) {
		sendAsync(msg, uidList, cuid).exceptionally(ex -> {
			log.error("批量推送失败: uids={}", uidList, ex);
			return null;
		});
	}

	/**
	 * 统一发送入口
	 * @param msg 消息体
	 * @param uids 接收消息的用户
	 * @param cuid 操作人ID
	 */
	public CompletableFuture<Void> sendAsync(WsBaseResp<?> msg, List<Long> uids, Long cuid) {
		if (!admissionOpen.get()) {
			// 停止外部准入后新提交明确拒绝：Future 入口失败完成，不阻塞、不假成功。
			CompletableFuture<Void> refused = new CompletableFuture<>();
			PushDrainException e = rejected("新提交");
			List<Long> targetUids = uids == null ? List.of() : new ArrayList<>(uids);
			log.warn("WS推送拒绝新提交: uids={}, reason={}", targetUids, e.getMessage());
			rejectedSubmits.incrementAndGet();
			refused.completeExceptionally(e);
			return refused;
		}

		// 1. 构建三级映射: 节点 → 设备指纹 → 用户ID
		Map<String, Map<String, Long>> nodeDeviceUser  = routerService.findNodeDeviceUser(uids);

		// 2. 并行发送到所有节点
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		nodeDeviceUser.forEach((targetNode, deviceUserMap) ->
				futures.add(submitNodeSend(msg, deviceUserMap, cuid, targetNode)));

		// 3. 执行
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
	}

	private CompletableFuture<Void> submitNodeSend(WsBaseResp<?> msg, Map<String, Long> deviceUserMap,
			Long cuid, String targetNode) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		ExecutorService exec;
		try {
			exec = getExecutorForNode(targetNode);
		} catch (RuntimeException e) {
			settleRejected(future, targetNode, msg, e);
			return future;
		}
		NodeSendTask task = new NodeSendTask(future, targetNode,
				() -> doNodeSend(msg, deviceUserMap, cuid, targetNode));
		inFlight.put(future, new TrackedTask(task, "send", targetNode));
		future.whenComplete((r, t) -> inFlight.remove(future));
		admittedSends.incrementAndGet();
		try {
			exec.execute(task);
		} catch (RejectedExecutionException e) {
			// 与 destroy 并发：执行器已关闭，该提交明确属于未接收被拒绝，不悬挂。
			settleRejected(future, targetNode, msg, e);
		}
		return future;
	}

	private void settleRejected(CompletableFuture<Void> future, String targetNode, WsBaseResp<?> msg, RuntimeException e) {
		rejectedSubmits.incrementAndGet();
		if (future.completeExceptionally(e instanceof PushDrainException drain ? drain : rejected("提交"))) {
			log.warn("WS推送拒绝提交: nodeId={}, type={}, reason={}", targetNode, msg.getType(), e.getMessage());
		}
	}

	private void doNodeSend(WsBaseResp<?> msg, Map<String, Long> deviceUserMap, Long cuid, String targetNode) {
		if (this.nodeId.equals(targetNode)) {
			// 本地节点直接推送
			localPush(deviceUserMap, msg);
		} else {
			// 远程节点通过MQ转发
			sendToNodeViaMQ(targetNode, msg, deviceUserMap, cuid);
		}
	}

	/**
	 * 本地节点直接推送
	 * @param deviceUserMap 指纹与用户数据
	 */
	private void localPush(Map<String, Long> deviceUserMap, WsBaseResp<?> msg) {
		// 1. 按设备数动态调整并行度, 最大32 线程并发
		int parallelism = Math.min(deviceUserMap.size(), 32);

		// #461：subscribe（fire-and-forget）改为 block——节点池任务完成即代表本地写完成，
		// 排空等待 allOf(inFlight) 才有真实语义。设备级 5s 超时限制单设备时延；
		// 整体上限 30s 兜底（parallelism 满载排队时最坏 N×5s/32，ponytail: 已知上限，
		// 超出由统一截止结算为未执行/结果不确定）。
		try {
			Flux.fromIterable(deviceUserMap.entrySet())
					.parallel(parallelism)
					.runOn(localPushScheduler)
					.flatMap(entry ->
							// 2. 增加超时中断机制
							sessionManager.sendToDevice(entry.getValue(), entry.getKey(), msg)
									.timeout(Duration.ofSeconds(5))
									.onErrorResume(e -> {
										log.error("设备推送超时: {}", entry.getKey());return Mono.empty();})
					).sequential()
					.then()
					.block(Duration.ofSeconds(30));
		} catch (RuntimeException e) {
			log.error("本地批量推送失败: 设备数={}", deviceUserMap.size(), e);
		}
	}

	/**
	 * 将消息推送到指定节点
	 * @param nodeId 节点信息
	 * @param deviceUserMap 指纹与用户数据
	 * @param msg 消息内容
	 * @param cuid 操作人ID
	 */
	private void sendToNodeViaMQ(String nodeId, WsBaseResp<?> msg, Map<String, Long> deviceUserMap, Long cuid) {
		try {
			// TODO 这里要解决一下唯一标识的问题
			rocketMQTemplate.send(MqConstant.PUSH_TOPIC + nodeId, MessageBuilder.withPayload(new NodePushDTO(msg, deviceUserMap, cuid, cuid)).build());
		} catch (Exception e) {
			log.error("MQ转发失败: node={}, devices={}", nodeId, deviceUserMap.keySet(), e);
		}
	}

	private final Map<String, ThreadPoolExecutor> nodeExecutors = new ConcurrentHashMap<>();
	/**
	 * 根据节点ID获取专属线程池
	 * @param nodeId 节点标识
	 * @return 线程池实例、有界队列防OOM
	 *
	 * <p>#461：与 destroy 并发时，不让新建执行器逃过关闭管理——若在放入映射后发现准入已关，
	 * 立即摘除并关闭新建池，该提交走拒绝路径。
	 */
	private ExecutorService getExecutorForNode(String nodeId) {
		ThreadPoolExecutor exec = nodeExecutors.get(nodeId);
		if (exec != null) {
			return exec;
		}
		if (!admissionOpen.get()) {
			throw rejected("新节点执行器");
		}
		ThreadPoolExecutor created = new ThreadPoolExecutor(
				threadPoolProperties.getCoreSize(), // 核心线程数
				threadPoolProperties.getMaxSize(), // 最大线程数
				threadPoolProperties.getKeepAlive(), SECONDS,
				new LinkedBlockingQueue<>(threadPoolProperties.getQueueCapacity()),
				new ThreadFactoryBuilder()
						.setNameFormat("push-" + nodeId + "-%d")
						.setDaemon(true)
						.build(),
				new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时回退到调用线程
		);
		ThreadPoolExecutor prev = nodeExecutors.putIfAbsent(nodeId, created);
		if (prev != null) {
			created.shutdown();
			return prev;
		}
		if (!admissionOpen.get()) {
			if (nodeExecutors.remove(nodeId, created)) {
				created.shutdown();
			}
			throw rejected("新节点执行器");
		}
		return created;
	}

	/** 节点发送包装任务：启动时统一截止检查 + 结算归属，不让 Future 悬挂。 */
	private final class NodeSendTask implements Runnable {
		private final CompletableFuture<Void> future;
		private final String targetNode;
		private final Runnable body;

		NodeSendTask(CompletableFuture<Void> future, String targetNode, Runnable body) {
			this.future = future;
			this.targetNode = targetNode;
			this.body = body;
		}

		@Override
		public void run() {
			// 排空器已结算（未执行/结果不确定）则不再重跑；已完成的 Future 不追溯改写。
			if (future.isDone()) {
				return;
			}
			if (ShutdownDrainClock.pastDeadline()) {
				settleUnexecuted(future, targetNode, "节点发送");
				return;
			}
			try {
				body.run();
				if (future.complete(null)) {
					completedSends.incrementAndGet();
				}
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		}
	}

	private void settleUnexecuted(CompletableFuture<Void> future, String targetNode, String what) {
		if (future.completeExceptionally(
				new PushDrainException("WS推送" + what + "超统一截止未开始，记未执行（#461 有限排空）"))) {
			log.warn("WS推送任务超截止未执行: nodeId={}, kind={}", targetNode, what);
		}
	}

	@Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES)
	void cleanIdleExecutors() {
		if (!admissionOpen.get()) {
			// #461：排空期停跳，避免与 destroy 的关闭管理竞争
			return;
		}
		nodeExecutors.entrySet().removeIf(e ->
				e.getValue().getPoolSize() == 0 &&
						e.getValue().getQueue().isEmpty()
		);
	}

	/**
	 * #461 有限排空（对齐 IM PushService #451 语义）：
	 * 关准入 → ShutdownDrainClock 统一截止（JVM 级共享，幂等首启）→ 事件驱动等在途 →
	 * 截止后停等、中断、结算（未执行/结果不确定）→ 调度器/MQ 模板按序退出 → 如实汇总。
	 * 等待按同一截止重算剩余，不逐池累加；有界等待，不无限期阻塞。
	 */
	@PreDestroy
	public void destroy() {
		if (!admissionOpen.compareAndSet(true, false)) {
			log.info("WS推送有限排空已在进行，跳过重复关闭");
			return;
		}
		boolean initiated = ShutdownDrainClock.beginDrain(shutdownBudgetSeconds);
		log.info("开始WS推送有限排空：已停止外部准入，统一截止预算={}s（本{}开启，JVM 共享截止）",
				shutdownBudgetSeconds, initiated ? "bean" : "沿用他");

		// 1. 停止接收新任务（已提交任务继续，截止前放行）
		nodeExecutors.values().forEach(ExecutorService::shutdown);

		// 2. 截止前：事件驱动等待在途 Future 集合排空，避免固定 sleep 探知完成。
		while (true) {
			long remaining = ShutdownDrainClock.remainingMillis();
			if (remaining <= 0 || inFlight.isEmpty()) {
				break;
			}
			CompletableFuture<?>[] snapshot = inFlight.keySet().toArray(new CompletableFuture[0]);
			try {
				CompletableFuture.allOf(snapshot).get(remaining, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				break;
			} catch (ExecutionException e) {
				// 单个任务失败属预期（已由各自 exceptionally 记录），继续排空其余。
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		// 3. 截止后：不再等待，强制收池，丢弃未开始，逐个结算。
		List<Runnable> dropped = new ArrayList<>();
		for (ExecutorService pool : nodeExecutors.values()) {
			dropped.addAll(pool.shutdownNow());
		}
		Set<Runnable> droppedSet = ConcurrentHashMap.newKeySet();
		droppedSet.addAll(dropped);
		long unexecuted = 0;
		long uncertain = 0;
		for (Map.Entry<CompletableFuture<Void>, TrackedTask> entry : inFlight.entrySet()) {
			CompletableFuture<Void> future = entry.getKey();
			if (future.isDone()) {
				continue;
			}
			TrackedTask task = entry.getValue();
			if (droppedSet.contains(task.body)) {
				unexecuted++;
				if (future.completeExceptionally(new PushDrainException(
						"WS推送" + task.kind + "超统一截止未开始，记未执行（#461 有限排空）"))) {
					log.warn("WS推送任务超截止未执行: nodeId={}, kind={}", task.nodeId, task.kind);
				}
			} else {
				uncertain++;
				if (future.completeExceptionally(new PushDrainException(
						"WS推送" + task.kind + "已开始但截止时未获结果，已尝试中断，记结果不确定（#461 有限排空）"))) {
					log.warn("WS推送任务结果不确定: nodeId={}, kind={}，已尝试中断", task.nodeId, task.kind);
				}
			}
		}
		inFlight.clear();

		// 4. 本地推送调度器最后停（在途本地写在截止窗口内仍可完成；dispose 丢弃的排队任务
		//    无法精确计数，如实记录为不可计数，不虚报）
		localPushScheduler.dispose();

		// 5. MQ 模板最后销毁：截止前在途远程转发仍需可用（#461 修复原实现先毁 MQ 致在途远发必失败）
		try {
			rocketMQTemplate.destroy();
		} catch (Exception e) {
			log.warn("MQ模板销毁异常", e);
		}

		if (unexecuted == 0 && uncertain == 0) {
			log.info("WS推送有限排空完成：接收={}，完成={}，拒绝={}，资源已完全退出",
					admittedSends.get(), completedSends.get(), rejectedSubmits.get());
		} else {
			// 不响应中断的底层调用：停等后明确报告资源未完全退出，不虚报关闭成功。
			log.warn("WS推送资源未完全退出：接收={}，完成={}，拒绝={}，未执行={}，结果不确定={}",
					admittedSends.get(), completedSends.get(), rejectedSubmits.get(), unexecuted, uncertain);
		}
	}
}
