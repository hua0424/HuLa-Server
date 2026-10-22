package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.basic.utils.ShutdownDrainClock;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.router.NacosRouterService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 消息推送升级版 [结合消息路由]
 * <p>
 * #451 有限排空：停止外部准入后新提交明确拒绝；已接收任务及其内部重试在统一单调时钟
 * 截止前继续（受原 maxRetryCount 上限约束，不续期）；截止后不再开始排队任务或再调度
 * 重试，未开始记未执行、已开始未获结果记结果不确定；已完成的 Future 不追溯改写；
 * 不响应中断的调用停等后明示资源未完全退出。
 *
 * @author 乾乾
 */
@Slf4j
@Service
public class PushService {
    @Resource
    private MQProducer mqProducer;
	@Resource
	private NacosRouterService routerService;
	@Resource
	private CachePlusOps cachePlusOps;

	@Value("${luohuo.thread.retry.max-count:3}")
	private Integer maxRetryCount;

	@Value("${luohuo.thread.retry.delay-seconds:3}")
	private Integer delaySeconds;

	/**
	 * 有限排空预算（秒）。默认 30s 对齐 Spring {@code spring.lifecycle.timeout-per-shutdown-phase=30s}
	 *（Nacos dev-runtime common.yml 有效值，见 #450 §2）；该 30s 在当前容器下不可达（Java 收不到
	 * SIGTERM，有效窗口为 0），仅为排空机制占位——数值预算 BLOCK 维持，待 §6 适配 + V3 实测后重冻，
	 * 未对齐前实际停止验收不通过。
	 */
	@Value("${luohuo.push.shutdown-budget-seconds:30}")
	private long shutdownBudgetSeconds;

	// 延迟任务线程池
	private ScheduledExecutorService retryScheduler;

	/**
	 * 外部准入开关：destroy 关闭后新提交明确拒绝。内部重试经 sendAsyncInternal 绕行准入门，
	 * 仍受同一单调时钟截止约束（截止前可继续，不续期）。
	 */
	private final AtomicBoolean admissionOpen = new AtomicBoolean(true);

	/** 统一单调时钟截止（System.nanoTime 基准）；未关闭前为 Long.MAX_VALUE 即无截止。 */
	private volatile long drainDeadlineNanos = Long.MAX_VALUE;

	/** 在途提交跟踪：future → 记录。排空截止时逐个明确结束，不永久悬挂。 */
	private final Map<CompletableFuture<Void>, TrackedTask> inFlight = new ConcurrentHashMap<>();

	/** 已调度未触发的重试包装任务；destroy 丢弃时逐个记未执行。 */
	private final Set<Runnable> pendingRetries = ConcurrentHashMap.newKeySet();

	private final AtomicLong admittedSends = new AtomicLong();
	private final AtomicLong completedSends = new AtomicLong();
	private final AtomicLong rejectedSubmits = new AtomicLong();

	/** 停止期拒绝 / 排空结算异常：Future 明确失败，不静默、不悬挂。 */
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

	@PostConstruct
	public void init() {
		retryScheduler = Executors.newScheduledThreadPool(2,
				new ThreadFactoryBuilder()
						.setNameFormat("push-retry-scheduler")
						.setDaemon(true)
						.build());

		log.info("初始化推送延迟调度器");
	}

	private static PushDrainException rejected(String what) {
		return new PushDrainException("推送已停止外部准入，拒绝" + what + "（#451 有限排空）");
	}

	private boolean pastDeadline() {
		return System.nanoTime() - drainDeadlineNanos >= 0;
	}

	/**
	 * 统一发送入口
	 * @param msg 消息体
	 * @param uids 接收消息的用户
	 * @param cuid 操作人
	 * @param retry 延迟推送 [延迟二次推送判断在途消息是否已经ack]
	 * @return
	 */
	public CompletableFuture<Void> sendAsync(WsBaseResp<?> msg, List<Long> uids, Long hashId, Long cuid, Boolean retry) {
		if (!admissionOpen.get()) {
			// 停止外部准入后新提交明确拒绝：Future 入口失败完成，不阻塞、不假成功。
			CompletableFuture<Void> refused = new CompletableFuture<>();
			PushDrainException e = rejected("新提交");
			List<Long> targetUids = uids == null ? List.of() : new ArrayList<>(uids);
			log.warn("MQ推送拒绝新提交: type={}, uids={}, retry={}, reason={}",
					msg.getType(), targetUids, retry, e.getMessage());
			rejectedSubmits.incrementAndGet();
			refused.completeExceptionally(e);
			return refused;
		}
		return sendAsyncInternal(msg, uids, hashId, cuid, retry);
	}

	/** 内部重试通道：绕行准入门（已接收任务的重试在截止前可继续），仍受统一截止约束。 */
	private CompletableFuture<Void> sendAsyncInternal(WsBaseResp<?> msg, List<Long> uids, Long hashId, Long cuid, Boolean retry) {
		// 1. 构建三级映射: 节点 → 设备指纹 → 用户ID
		Map<String, Map<String, Long>> nodeDeviceUser  = routerService.findNodeDeviceUser(uids);

		// 2. 按节点+设备批量推送
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		nodeDeviceUser.forEach((nodeId, deviceUserMap) ->
				futures.add(submitNodeSend(msg, deviceUserMap, hashId, cuid, retry, nodeId)));

		// 3. 执行任务
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
	}

	private CompletableFuture<Void> submitNodeSend(WsBaseResp<?> msg, Map<String, Long> deviceUserMap,
			Long hashId, Long cuid, Boolean retry, String nodeId) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		ExecutorService exec;
		try {
			exec = getExecutorForNode(nodeId);
		} catch (RuntimeException e) {
			settleRejected(future, nodeId, msg, e);
			return future;
		}
		NodeSendTask task = new NodeSendTask(future, nodeId, msg.getType(),
				() -> doNodeSend(msg, deviceUserMap, hashId, cuid, retry, nodeId));
		inFlight.put(future, new TrackedTask(task, "send", nodeId));
		future.whenComplete((r, t) -> inFlight.remove(future));
		try {
			exec.execute(task);
			// #468：接收计数只在提交成功后累加，被拒提交仅计入「拒绝」，汇总口径互斥。
			admittedSends.incrementAndGet();
		} catch (RejectedExecutionException e) {
			// 与 destroy 并发：执行器已关闭，该提交明确属于未接收被拒绝，不悬挂。
			settleRejected(future, nodeId, msg, e);
		}
		return future;
	}

	private void settleRejected(CompletableFuture<Void> future, String nodeId, WsBaseResp<?> msg, RuntimeException e) {
		rejectedSubmits.incrementAndGet();
		if (future.completeExceptionally(e instanceof PushDrainException drain ? drain : rejected("提交"))) {
			log.warn("MQ推送拒绝提交: nodeId={}, type={}, reason={}", nodeId, msg.getType(), e.getMessage());
		}
	}

	private void doNodeSend(WsBaseResp<?> msg, Map<String, Long> deviceUserMap,
			Long hashId, Long cuid, Boolean retry, String nodeId) {
		try {
			if (retry) {
				mqProducer.sendMsgWithDelay(MqConstant.PUSH_DELAY_TOPIC, new NodePushDTO(msg, deviceUserMap, hashId, cuid), delaySeconds);
			} else {
				mqProducer.sendMsg(MqConstant.PUSH_TOPIC + nodeId, new NodePushDTO(msg, deviceUserMap, hashId, cuid));
			}
		} catch (Exception e) {
			// #220：MQ 发送失败不再静默丢弃——升级 WARN 告警
			List<Long> targetUids = new ArrayList<>(deviceUserMap.values());
			log.warn("MQ发送失败: nodeId={}, type={}, uids={}, retry={}, reason={}",
					nodeId, msg.getType(), targetUids, retry, e.getMessage(), e);
			if (retry) {
				// 延迟重试链路本身失败：抛回给 executeRetry 的 whenComplete 按 retryCount+1 继续
				// （受 scheduleDelayRetry 的 maxRetryCount 上限约束，不会无限递归）
				throw e instanceof RuntimeException re ? re : new RuntimeException(e);
			}
			// P1-2（PR #76）：不走 PUSH_DELAY_TOPIC（persona 类推送 hashId=0 不在 in-flight 集合，
			// RetryPushConsumer 对它是 no-op，重试空转）——改为有界直发重发 PUSH_TOPIC+nodeId
			scheduleDirectResend(nodeId, msg, deviceUserMap, hashId, cuid, 1, "MQ发送失败: " + e.getMessage());
		}
	}

	/** 节点发送包装任务：启动时统一截止检查 + 结算归属，不让 Future 悬挂。 */
	private final class NodeSendTask implements Runnable {
		private final CompletableFuture<Void> future;
		private final String nodeId;
		private final String msgType;
		private final Runnable body;

		NodeSendTask(CompletableFuture<Void> future, String nodeId, String msgType, Runnable body) {
			this.future = future;
			this.nodeId = nodeId;
			this.msgType = msgType;
			this.body = body;
		}

		@Override
		public void run() {
			// 排空器已结算（未执行/结果不确定）则不再重跑；已完成的 Future 不追溯改写。
			if (future.isDone()) {
				return;
			}
			if (pastDeadline()) {
				settleUnexecuted(future, nodeId, "节点发送");
				return;
			}
			try {
				body.run();
				if (future.complete(null)) {
					completedSends.incrementAndGet();
				}
			} catch (Throwable t) {
				// #468：fire-and-forget 路径无人接 Future，兜底异常原本零痕迹；留 warn 便于定位。
				log.warn("IM推送节点任务异常: nodeId={}, type={}", nodeId, msgType, t);
				future.completeExceptionally(t);
			}
		}
	}

	private void settleUnexecuted(CompletableFuture<Void> future, String nodeId, String what) {
		if (future.completeExceptionally(
				new PushDrainException("推送" + what + "超统一截止未开始，记未执行（#451 有限排空）"))) {
			log.warn("推送任务超截止未执行: nodeId={}, kind={}", nodeId, what);
		}
	}

	/**
	 * 根据节点名称选择线程池, hash 确保节点分布均匀
	 * broadcast：特殊节点标识
	 * <p>
	 * #451：与 destroy 并发时，不让新建执行器逃过关闭管理——若在放入映射后发现准入已关，
	 * 立即摘除并关闭新建池，该提交走拒绝路径。
	 */
	private final Map<String, ExecutorService> nodeExecutors = new ConcurrentHashMap<>();
	private ExecutorService getExecutorForNode(String nodeId) {
		ExecutorService exec = nodeExecutors.get(nodeId);
		if (exec != null) {
			return exec;
		}
		if (!admissionOpen.get()) {
			throw rejected("新节点执行器");
		}
		ExecutorService created = Executors.newSingleThreadExecutor(
				new ThreadFactoryBuilder()
						.setNameFormat("push-" + nodeId + "-%d")
						.build()
		);
		ExecutorService prev = nodeExecutors.putIfAbsent(nodeId, created);
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

	/**
	 * 直接发送，单个用户直接推送
	 */
	public void sendPushMsg(WsBaseResp<?> msg, Long uid, Long cuid) {
		sendPushMsg(msg, Arrays.asList(uid), cuid);
	}

	/**
	 * 批量异步发送给 uidList
	 * TODO 这里hashId 需要重新生成
	 */
	public void sendPushMsg(WsBaseResp<?> msg, List<Long> uidList, Long cuid) {
		// void 包装保留签名：停止期拒绝经现有 exceptionally 错误处理记录，不阻塞、不假成功。
		sendAsync(msg, uidList, 0L, cuid, false)
				.exceptionally(e -> {
					log.error("批量推送失败", e);
					return null;
				});
	}

	/**
	 * 将消息推送给跟我相关的所有房间
	 * @param uid 推送给这个uid相关的所有人员
	 * @param type 前端监听的类型
	 * @param data 推送的数据
	 */
	public void pushRoom(Long uid, String type, Object data) {
		// #451：停止期拒绝新提交（void 签名无法失败 Future，记数 + 明示，不静默丢弃）。
		if (!admissionOpen.get()) {
			rejectedSubmits.incrementAndGet();
			log.warn("MQ推送拒绝房间推送新提交: uid={}, type={}（#451 有限排空）", uid, type);
			return;
		}
		// 1. 获取用户所在群聊ID列表
		CacheKey ugKey = PresenceCacheKeyBuilder.userGroupsKey(uid);
		Set<Long> roomIds = cachePlusOps.sMembers(ugKey).parallelStream().map(obj -> Long.parseLong(obj.toString())).collect(Collectors.toSet());

		if (roomIds.isEmpty()) {
			return;
		}

		// 2. 创建通用响应对象
		WsBaseResp commonResp = new WsBaseResp();
		commonResp.setType(type);
		commonResp.setData(data);

		// 3. 获取所有群聊的在线成员并去重
		Set<Long> allMemberIds = new ConcurrentHashSet<>();

		// 并行处理每个群聊的在线成员
		roomIds.parallelStream().forEach(roomId -> {
			CacheKey groupKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomId);
			// 检查群是否在线
			if (cachePlusOps.sCard(groupKey) < 1L) return;

			// 获取群成员并添加到总集合中
			cachePlusOps.sMembers(groupKey).stream()
					.map(obj -> Long.parseLong(obj.toString()))
					.forEach(allMemberIds::add);
		});

		// 4. 移除自己，避免给自己发送消息
//		allMemberIds.remove(uid);

		// 5. 分批推送
		int pageSize = 200;
		List<Long> memberIdList = new ArrayList<>(allMemberIds);

		Lists.partition(memberIdList, pageSize).forEach(batch -> sendPushMsg(commonResp, batch, uid));
	}

    /** Enter via this Spring bean proxy only after the RocketMQ DTO tenant context has been restored. */
    @Transactional(rollbackFor = Exception.class)
    public void withMessageTransaction(Runnable work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("message consumer requires an active Spring DB transaction");
        }
        work.run();
    }

	/** Only the message consumer uses this transactional second hop; other push callers keep legacy semantics. */
	public void sendReliablePushMsg(WsBaseResp<?> msg, List<Long> uids, Long msgId, Long cuid) {
		// #451：截止后不新启重放——停止期拒绝新的可靠发送。在途 @SecureInvoke 重放由 DB 记录驱动，
		// 属共享基础设施（#447 持久化恢复范畴），排空不取消已提交重放，此处仅拒绝新提交。
		if (!admissionOpen.get()) {
			throw new IllegalStateException("推送已停止外部准入，可靠发送拒绝接收（#451 有限排空）");
		}
		// @SecureInvoke silently falls back to a direct MQ send outside a transaction.
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("message push requires an active Spring DB transaction");
		}
		if (uids == null || uids.isEmpty()) return; // Offline recipients fetch their persisted message history.
		Map<String, Map<String, Long>> routes = routerService.findNodeDeviceUser(uids);
		if (routes == null) throw new IllegalStateException("message push routing unavailable");
		if (routes.isEmpty()) {
			// Redis still reports online recipients: a missing node route may be a Nacos outage.
			throw new IllegalStateException("message push has no node route for online recipients: " + msgId);
		}
		for (Map.Entry<String, Map<String, Long>> route : routes.entrySet()) {
			if (route.getKey() == null || route.getKey().isBlank()
					|| route.getValue() == null || route.getValue().isEmpty()) {
				throw new IllegalStateException("message push has invalid node/device route");
			}
			NodePushDTO intent = new NodePushDTO(msg, route.getValue(), msgId, cuid);
			// JsonUtil returns "" rather than throwing on failed serialization; never persist an unusable intent.
			if (JsonUtil.toJson(intent).isEmpty()) throw new IllegalStateException("message push is not serializable");
			mqProducer.sendSecureMsg(MqConstant.PUSH_TOPIC + route.getKey(),
					intent, msgId + ":" + route.getKey());
		}
	}

	/**
	 * #451 有限排空：关准入 → 统一单调截止前放行在途与内部重试 → 截止后停等、中断、结算、汇总。
	 * 全部等待按同一截止重算剩余，不逐池累加；有界等待，不无限期阻塞。
	 */
	@PreDestroy
	public void destroy() {
		if (!admissionOpen.compareAndSet(true, false)) {
			log.info("推送有限排空已在进行，跳过重复关闭");
			return;
		}
		// #461：共享截止时钟幂等首启（本 JVM 其他关停参与者沿用同一截止）；本地截止与之一致。
		ShutdownDrainClock.beginDrain(shutdownBudgetSeconds);
		drainDeadlineNanos = ShutdownDrainClock.deadlineNanos();
		log.info("开始推送有限排空：已停止外部准入，统一截止预算={}s", shutdownBudgetSeconds);

		// 截止前：在途任务与内部重试继续（受原 maxRetryCount 上限与同一截止约束，不续期）。
		// 事件驱动等待在途 Future 集合排空，避免固定 sleep 探知完成。
		while (true) {
			long remaining = drainDeadlineNanos - System.nanoTime();
			if (remaining <= 0 || inFlight.isEmpty()) {
				break;
			}
			CompletableFuture<?>[] snapshot = inFlight.keySet().toArray(new CompletableFuture[0]);
			try {
				CompletableFuture.allOf(snapshot).get(remaining, TimeUnit.NANOSECONDS);
			} catch (TimeoutException e) {
				break;
			} catch (ExecutionException e) {
				// 单个任务失败属预期（已由各自 exceptionally/重试链记录），继续排空其余。
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		// 截止后：不再等待，尝试中断在途，丢弃未开始，逐个结算，汇总明示退出状态。
		List<ExecutorService> pools = new ArrayList<>(nodeExecutors.values());
		if (retryScheduler != null) {
			pools.add(retryScheduler);
		}
		List<Runnable> dropped = new ArrayList<>();
		for (ExecutorService pool : pools) {
			dropped.addAll(pool.shutdownNow());
		}
		// 竞争窗口兜底：快照后新建的池（如 getExecutorForNode 摘除失败的残留）一律关闭。
		nodeExecutors.values().forEach(ExecutorService::shutdownNow);

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
						"推送" + task.kind + "超统一截止未开始，记未执行（#451 有限排空）"))) {
					log.warn("推送任务超截止未执行: nodeId={}, kind={}", task.nodeId, task.kind);
				}
			} else {
				uncertain++;
				if (future.completeExceptionally(new PushDrainException(
						"推送" + task.kind + "已开始但截止时未获结果，已尝试中断，记结果不确定；"
								+ "不能断言 broker 未接收或已送达（#451 有限排空）"))) {
					log.warn("推送任务结果不确定: nodeId={}, kind={}，已尝试中断", task.nodeId, task.kind);
				}
			}
		}
		for (Runnable runnable : dropped) {
			if (pendingRetries.remove(runnable)) {
				unexecuted++;
				log.warn("推送重试超截止未触发，记未执行");
			}
		}
		inFlight.clear();
		pendingRetries.clear();

		// #461：@SecureInvoke 重放结算（共享时钟计数；重放跑在 ForkJoinPool.commonPool，不在本类池内，
		// 此处仅如实汇报，无法也不尝试收池——记录已持久化，下次启动由 retry() 重放）
		long replaysSkipped = ShutdownDrainClock.skippedReplays();
		long replaysActive = ShutdownDrainClock.activeReplays();
		if (replaysSkipped > 0 || replaysActive > 0) {
			log.warn("@SecureInvoke 排空结算：跳过重放（未执行）={}，在途（结果不确定）={}；记录保留待下次启动重放",
					replaysSkipped, replaysActive);
		}

		if (unexecuted == 0 && uncertain == 0) {
			log.info("推送有限排空完成：接收={}，完成={}，拒绝={}，资源已完全退出",
					admittedSends.get(), completedSends.get(), rejectedSubmits.get());
		} else {
			// 不响应中断的底层调用：停等后明确报告资源未完全退出，不虚报关闭成功。
			log.warn("推送资源未完全退出：接收={}，完成={}，拒绝={}，未执行={}，结果不确定={}；"
							+ "线程/连接未证退出，不虚报关闭成功",
					admittedSends.get(), completedSends.get(), rejectedSubmits.get(), unexecuted, uncertain);
		}
	}

	// ============ 重试 (统一截止) ============

	/**
	 * 批量推送方法 [重试专用]
	 */
	public void sendPushMsgWithRetry(WsBaseResp<?> msg, List<Long> uidList, Long hashId, Long cuid) {
		sendAsyncWithRetry(msg, uidList, hashId, cuid)
				.exceptionally(e -> {
					log.error("重试的批量推送最终失败", e);
					return null;
				});
	}

	/**
	 * 单用户推送方法 [重试专用]
	 */
	public void sendPushMsgWithRetry(WsBaseResp<?> msg, Long uid, Long hashId, Long cuid) {
		sendPushMsgWithRetry(msg, Arrays.asList(uid), hashId, cuid);
	}

	/**
	 * 发送带重试机制的推送消息。
	 * #451：首次失败的延迟重试经 scheduleDelayRetry 统一截止门（截止后拒绝、不续期）。
	 */
	public CompletableFuture<Void> sendAsyncWithRetry(WsBaseResp<?> msg, List<Long> uids, Long hashId, Long cuid) {
		return sendAsync(msg, uids, hashId, cuid, true)
				.exceptionally(throwable -> {
					// 首次推送失败，触发延迟重试
					log.warn("消息首次推送失败，触发延迟重试。uidList: {}, Reason: {}", uids, throwable.getMessage());
					scheduleDelayRetry(msg, uids, hashId, cuid, 1, "首次推送失败: " + throwable.getMessage());
					return null;
				});
	}

	/**
	 * 无差别重试：延迟再次推送
	 * <p>
	 * #451：截止后不新启重放，不再调度新重试（不续期）。排队重试返回的 Future：
	 * 触发前被 destroy 丢弃则记未执行，不悬挂、不假成功。
	 */
	public CompletableFuture<Void> scheduleDelayRetry(WsBaseResp<?> msg, List<Long> uids, Long hashId, Long cuid,
													  Integer retryCount, String reason) {
		if (!admissionOpen.get() || pastDeadline()) {
			CompletableFuture<Void> refused = new CompletableFuture<>();
			PushDrainException e = new PushDrainException(
					"推送延迟重试超统一截止未开始，记未执行（#451 有限排空），reason=" + reason);
			rejectedSubmits.incrementAndGet();
			log.warn("MQ推送延迟重试拒绝调度: type={}, uids={}, retryCount={}, reason={}",
					msg.getType(), uids, retryCount, e.getMessage());
			refused.completeExceptionally(e);
			return refused;
		}
		if (retryCount > maxRetryCount) {
			log.error("消息重试次数超过最大限制，不再进行延迟重试: type={}, uids={}, retryCount={}", msg.getType(), uids, retryCount);
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Void> result = new CompletableFuture<>();
		RetryTask task = new RetryTask(result, msg, uids, hashId, cuid, retryCount, reason);
		pendingRetries.add(task);
		inFlight.put(result, new TrackedTask(task, "延迟重试", "retryScheduler"));
		result.whenComplete((r, t) -> {
			inFlight.remove(result);
			pendingRetries.remove(task);
		});
		try {
			retryScheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			// destroy 已关闭调度器（或 shutdownNow 丢弃）：明确失败，不悬挂。
			pendingRetries.remove(task);
			rejectedSubmits.incrementAndGet();
			result.completeExceptionally(rejected("延迟重试调度"));
		}
		return result;
	}

	/** 延迟重试包装任务：触发时统一截止检查 + 自我重调度；结算归属唯一。 */
	private final class RetryTask implements Runnable {
		private final CompletableFuture<Void> result;
		private final WsBaseResp<?> msg;
		private final List<Long> uids;
		private final Long hashId;
		private final Long cuid;
		private final Integer retryCount;
		private final String reason;

		RetryTask(CompletableFuture<Void> result, WsBaseResp<?> msg, List<Long> uids,
				Long hashId, Long cuid, Integer retryCount, String reason) {
			this.result = result;
			this.msg = msg;
			this.uids = uids;
			this.hashId = hashId;
			this.cuid = cuid;
			this.retryCount = retryCount;
			this.reason = reason;
		}

		@Override
		public void run() {
			// destroy 丢弃（shutdownNow）且任务未开始：记未执行。
			if (result.isDone()) {
				return;
			}
			if (pastDeadline()) {
				if (result.completeExceptionally(new PushDrainException(
						"推送延迟重试超统一截止未开始，记未执行（#451 有限排空）"))) {
					log.warn("推送延迟重试超截止未执行: type={}, retryCount={}", msg.getType(), retryCount);
				}
				return;
			}
			executeRetry(msg, uids, hashId, cuid, retryCount, reason).whenComplete((r, t) -> {
				if (t != null) {
					result.completeExceptionally(t);
				} else {
					result.complete(null);
				}
			});
		}
	}

    /**
     * @see RetryPushConsumer
     * 延迟推送执行
     */
    public CompletableFuture<Void> executeRetry(WsBaseResp<?> msg, List<Long> uids, Long hashId, Long cuid,
												Integer retryCount, String reason) {
        if (uids == null || uids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

		Integer nextRetry = retryCount + 1;
		if (pastDeadline()) {
			CompletableFuture<Void> refused = new CompletableFuture<>();
			refused.completeExceptionally(new PushDrainException(
					"推送延迟重试超统一截止不继续，记未执行（#451 有限排空），reason=" + reason));
			return refused;
		}

        // R1：恢复 base 语义 retry=true，重入 PUSH_DELAY_TOPIC 经 RetryPushConsumer
        // ACK/in-flight 去重；统一截止门保留在前。
        try {
            return sendAsync(msg, uids, hashId, cuid, true).handle((sendResult, throwable) -> {
			if (throwable == null) {
				return CompletableFuture.<Void>completedFuture(null);
			}
			if (!(throwable instanceof RuntimeException re)) {
				throw new RuntimeException(throwable);
			}
			log.warn("MQ推送延迟重试触发，重试链继续: type={}, uids={}, retryCount={}/{}, reason={}",
					msg.getType(), uids, nextRetry, maxRetryCount, reason);
			return scheduleDelayRetry(msg, uids, hashId, cuid, nextRetry,
					"MQ推送延迟重试续期: " + (reason == null ? "" : reason));
		}).thenCompose(stage -> stage);
        } catch (Exception e) {
            // 同步段路由异常不被调度器吞：记一次失败并按重试链继续（受门槛与截止约束）。
            log.error("推送延迟重试同步段异常，转重试链: type={}, retryCount={}", msg.getType(), nextRetry, e);
            return scheduleDelayRetry(msg, uids, hashId, cuid, nextRetry, "执行异常: " + e.getMessage());
        }
    }

	/**
	 * 有界直发重发（P1-2，语义同旧代码直发 executeAsync 分支，但不再内部切执行器）：
	 * 截止后不续期；排队任务触发前被 destroy 丢弃记未执行；Future 永不悬挂。
	 */
	private void scheduleDirectResend(String nodeId, WsBaseResp<?> msg, Map<String, Long> deviceUserMap,
									  Long hashId, Long cuid, int attempt, String reason) {
		if (!admissionOpen.get() || pastDeadline() || attempt > maxRetryCount) {
			log.warn("MQ推送直发重发终止: nodeId={}, type={}, attempt={}/{}, reason={}（#451 有限排空）",
					nodeId, msg.getType(), attempt, maxRetryCount, reason);
			return;
		}
		CompletableFuture<Void> future = new CompletableFuture<>();
		DirectResendTask task = new DirectResendTask(future, nodeId, msg, deviceUserMap,
				hashId, cuid, attempt, reason);
		inFlight.put(future, new TrackedTask(task, "直发重发", nodeId));
		future.whenComplete((r, t) -> {
			inFlight.remove(future);
			pendingRetries.remove(task);
		});
		pendingRetries.add(task);
		try {
			retryScheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			pendingRetries.remove(task);
			inFlight.remove(future);
			log.warn("MQ推送直发重发调度被拒绝: nodeId={}, type={}, attempt={}, reason={}",
					nodeId, msg.getType(), attempt, e.getMessage());
		}
	}

	/** 直发重发包装任务：触发时统一截止检查；失败按原语义递归有界续期。 */
	private final class DirectResendTask implements Runnable {
		private final CompletableFuture<Void> future;
		private final String nodeId;
		private final WsBaseResp<?> msg;
		private final Map<String, Long> deviceUserMap;
		private final Long hashId;
		private final Long cuid;
		private final int attempt;
		private final String reason;

		DirectResendTask(CompletableFuture<Void> future, String nodeId, WsBaseResp<?> msg,
						 Map<String, Long> deviceUserMap, Long hashId, Long cuid, int attempt, String reason) {
			this.future = future;
			this.nodeId = nodeId;
			this.msg = msg;
			this.deviceUserMap = deviceUserMap;
			this.hashId = hashId;
			this.cuid = cuid;
			this.attempt = attempt;
			this.reason = reason;
		}

		@Override
		public void run() {
			if (future.isDone()) {
				return;
			}
			if (pastDeadline()) {
				if (future.completeExceptionally(new PushDrainException(
						"推送直发重发超统一截止未开始，记未执行（#451 有限排空）"))) {
					log.warn("推送直发重发超截止未执行: nodeId={}, type={}, attempt={}",
							nodeId, msg.getType(), attempt);
				}
				return;
			}
			try {
				mqProducer.sendMsg(MqConstant.PUSH_TOPIC + nodeId,
						new NodePushDTO(msg, deviceUserMap, hashId, cuid));
				if (future.complete(null)) {
					completedSends.incrementAndGet();
				}
			} catch (Exception e) {
				log.warn("MQ推送直发重发出错: nodeId={}, type={}, attempt={}/{}, reason={}",
						nodeId, msg.getType(), attempt, maxRetryCount, e.getMessage(), e);
				scheduleDirectResend(nodeId, msg, deviceUserMap, hashId, cuid, attempt + 1,
						"MQ推送直发重发续期: " + e.getMessage());
				future.complete(null);
			}
		}
	}

}
