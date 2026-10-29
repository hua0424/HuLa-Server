package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.jackson.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * #486 T5：推送资源预算账本（候选 A：有界 per-node 串行 + 全局预算）。
 *
 * <p>只回答一件事：此刻还能不能再接收一个「节点任务」。所有维度在同一处扣减、归还：
 * 全局任务并发、活跃节点数、每节点排队任务、共享载荷字节、并发重试预约。
 * 任一维度拒绝都在入队前定稿，因此拒绝是<b>可处理的逐目标未接收事实</b>，
 * 不会被折成「已推送」或「已送达」。
 *
 * <p>三条不变量：
 * <ul>
 *   <li>额度只在真正入队成功后算接收；入队被拒/关闭竞争都回滚，不泄漏。</li>
 *   <li>载荷按对象身份共享计数：同一份 payload 被多个节点复用只记一次字节数 + 引用数；
 *       所有持有者释放后才扣回。UTF-8 字节不等于 JVM retained heap，这里只记 wire 口径。</li>
 *   <li>{@link #close()} 后不再接收；之后所有 reserve 都返回 {@link Reject#CLOSED}，
 *       已有额度仍可正常释放（排空期在途任务照常结算）。</li>
 * </ul>
 *
 * <p>默认不启用：只有 {@code luohuo.push.capacity.enabled=true} 时 {@link PushService} 才建账本。
 * 未启用时行为与既有实现完全一致（无额外上限，也无额外开销）。
 */
final class PushCapacityLedger {

	/** 拒绝来源；与路由失败、停止期拒绝严格分开，调用方可分别处理。 */
	enum Reject {
		CLOSED("push_capacity_closed"),
		NODE_LIMIT("push_capacity_node_limit"),
		NODE_QUEUE_FULL("push_capacity_node_queue_full"),
		GLOBAL_TASK_LIMIT("push_capacity_global_task_limit"),
		PAYLOAD_LIMIT("push_capacity_payload_limit"),
		RETRY_LIMIT("push_capacity_retry_limit");

		private final String reason;

		Reject(String reason) {
			this.reason = reason;
		}

		String reason() {
			return reason;
		}
	}

	/** 共享载荷的字节与引用计数；只有全部持有者释放后才从账本移除。 */
	private static final class PayloadRef {
		final int bytes;
		/** 创建时即有一位持有者，避免首个持有者释放就把仍在用的载荷销账。 */
		int refs = 1;

		PayloadRef(int bytes) {
			this.bytes = bytes;
		}
	}

	private static final class NodeSlot {
		final String nodeId;
		final AtomicInteger tasks = new AtomicInteger();

		NodeSlot(String nodeId) {
			this.nodeId = nodeId;
		}
	}

	/** 一次准入占用的全部额度；{@link #release()} 幂等，重复调用不会多扣。 */
	static final class Admission {
		private final PushCapacityLedger ledger;
		private final String nodeId;
		private final boolean retry;
		private final Object payload;
		private final int payloadBytes;
		private final AtomicBoolean released = new AtomicBoolean();

		Admission(PushCapacityLedger ledger, String nodeId, boolean retry, Object payload, int payloadBytes) {
			this.ledger = ledger;
			this.nodeId = nodeId;
			this.retry = retry;
			this.payload = payload;
			this.payloadBytes = payloadBytes;
		}

		String getNodeId() {
			return nodeId;
		}

		int getPayloadBytes() {
			return payloadBytes;
		}

		/** 归还节点/全局/载荷/重试额度；幂等。 */
		void release() {
			if (!released.compareAndSet(false, true)) {
				return;
			}
			ledger.releaseAdmission(nodeId, retry, payload);
		}
	}

	/** 准入结论：要么拿到额度，要么给出可处理的拒绝原因。 */
	static final class Reservation {
		private final Admission admission;
		private final Reject reject;

		private Reservation(Admission admission, Reject reject) {
			this.admission = admission;
			this.reject = reject;
		}

		boolean admitted() {
			return admission != null;
		}

		Admission admission() {
			return admission;
		}

		Reject reject() {
			return reject;
		}

		String reason() {
			return reject == null ? null : reject.reason();
		}
	}

	private final int maxActiveNodes;
	private final int maxTasksPerNode;
	private final int maxPayloadBytes;
	private final Semaphore globalTasks;
	private final AtomicInteger retrySlots;
	private final AtomicInteger payloadBytes = new AtomicInteger();
	private final Map<String, NodeSlot> nodes = new ConcurrentHashMap<>();
	private final Map<Object, PayloadRef> payloads = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<String, AtomicLong> rejections = new ConcurrentHashMap<>();
	private final AtomicLong admittedTasks = new AtomicLong();
	private final AtomicLong releasedTasks = new AtomicLong();
	private volatile boolean closed;

	PushCapacityLedger(int maxActiveNodes, int maxTotalTasks, int maxTasksPerNode, int maxPayloadBytes,
			int maxConcurrentRetries) {
		this.maxActiveNodes = Math.max(1, maxActiveNodes);
		this.maxTasksPerNode = Math.max(1, maxTasksPerNode);
		this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
		this.globalTasks = new Semaphore(Math.max(1, maxTotalTasks));
		this.retrySlots = new AtomicInteger(Math.max(0, maxConcurrentRetries));
	}

	/**
	 * 节点任务准入：按固定次序扣减（全局并发 → 节点数 → 节点队列 → 载荷），
	 * 任一步失败都回滚已扣额度，不留半占状态。
	 *
	 * @param payload 共享载荷对象（按身份计字节与引用），可为 null
	 */
	Reservation reserve(String nodeId, Object payload) {
		if (closed) {
			return reject(Reject.CLOSED);
		}
		if (!globalTasks.tryAcquire()) {
			return reject(Reject.GLOBAL_TASK_LIMIT);
		}
		NodeSlot slot = nodeSlot(nodeId);
		if (slot == null) {
			globalTasks.release();
			return reject(Reject.NODE_LIMIT);
		}
		if (slot.tasks.incrementAndGet() > maxTasksPerNode) {
			slot.tasks.decrementAndGet();
			retireIfIdle(slot);
			globalTasks.release();
			return reject(Reject.NODE_QUEUE_FULL);
		}
		int bytes = acquirePayload(payload);
		if (bytes < 0) {
			slot.tasks.decrementAndGet();
			retireIfIdle(slot);
			globalTasks.release();
			return reject(Reject.PAYLOAD_LIMIT);
		}
		admittedTasks.incrementAndGet();
		return new Reservation(new Admission(this, nodeId, false, payload, bytes), null);
	}

	/** 延迟重试/直发重发预约：与节点任务共用同一账本，只单独限制并发重试链数。 */
	Reservation reserveRetry(String reason) {
		if (closed) {
			return reject(Reject.CLOSED);
		}
		if (retrySlots.decrementAndGet() < 0) {
			retrySlots.incrementAndGet();
			return reject(Reject.RETRY_LIMIT);
		}
		admittedTasks.incrementAndGet();
		return new Reservation(new Admission(this, "retry:" + reason, true, null, 0), null);
	}

	/** 关闭：不再接收新任务；在途额度仍按原路径释放。 */
	void close() {
		closed = true;
	}

	boolean isClosed() {
		return closed;
	}

	/** 只读观察点：供 D7 测量与拒绝排查，不暴露可变结构。 */
	Map<String, Object> snapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("closed", closed);
		snapshot.put("activeNodes", nodes.size());
		snapshot.put("maxActiveNodes", maxActiveNodes);
		snapshot.put("maxTasksPerNode", maxTasksPerNode);
		snapshot.put("globalFreePermits", globalTasks.availablePermits());
		snapshot.put("payloadBytes", payloadBytes.get());
		snapshot.put("maxPayloadBytes", maxPayloadBytes);
		snapshot.put("retrySlotsFree", Math.max(0, retrySlots.get()));
		snapshot.put("admittedTasks", admittedTasks.get());
		snapshot.put("releasedTasks", releasedTasks.get());
		Map<String, Long> nodeTasks = new LinkedHashMap<>();
		nodes.forEach((id, slot) -> nodeTasks.put(id, (long) slot.tasks.get()));
		snapshot.put("nodeTasks", nodeTasks);
		Map<String, Long> rejected = new LinkedHashMap<>();
		rejections.forEach((key, value) -> rejected.put(key, value.get()));
		snapshot.put("rejections", rejected);
		return Collections.unmodifiableMap(snapshot);
	}

	private Reservation reject(Reject reject) {
		rejections.computeIfAbsent(reject.name(), key -> new AtomicLong()).incrementAndGet();
		return new Reservation(null, reject);
	}

	private NodeSlot nodeSlot(String nodeId) {
		NodeSlot existing = nodes.get(nodeId);
		if (existing != null) {
			return existing;
		}
		synchronized (nodes) {
			existing = nodes.get(nodeId);
			if (existing != null) {
				return existing;
			}
			if (nodes.size() >= maxActiveNodes) {
				return null;
			}
			NodeSlot created = new NodeSlot(nodeId);
			nodes.put(nodeId, created);
			return created;
		}
	}

	/** 节点队列清空即回收 slot，避免 churn 期间活跃节点数只涨不落。 */
	private void retireIfIdle(NodeSlot slot) {
		if (slot.tasks.get() != 0) {
			return;
		}
		synchronized (nodes) {
			if (slot.tasks.get() == 0) {
				nodes.remove(slot.nodeId, slot);
			}
		}
	}

	/**
	 * @return 本次计入的 wire bytes；超预算返回 -1 且不留下引用。
	 *
	 * <p>判限与累加必须在同一临界段内完成：分别做的话两个并发线程各自看到「还有余量」，
	 * 合计就会越过 {@code maxPayloadBytes}。字节估算留在锁外，避免一次大载荷的序列化
	 * 阻塞所有准入。
	 */
	private int acquirePayload(Object payload) {
		if (payload == null) {
			return 0;
		}
		int bytes = estimatePayloadBytes(payload);
		synchronized (payloads) {
			PayloadRef existing = payloads.get(payload);
			if (existing != null) {
				existing.refs++;
				return existing.bytes;
			}
			if (payloadBytes.get() + bytes > maxPayloadBytes) {
				return -1;
			}
			payloadBytes.addAndGet(bytes);
			payloads.put(payload, new PayloadRef(bytes));
			return bytes;
		}
	}

	private void releaseAdmission(String nodeId, boolean retry, Object payload) {
		releasedTasks.incrementAndGet();
		if (retry) {
			retrySlots.incrementAndGet();
			return;
		}
		NodeSlot slot = nodes.get(nodeId);
		if (slot != null) {
			slot.tasks.decrementAndGet();
			retireIfIdle(slot);
		}
		globalTasks.release();
		if (payload != null) {
			synchronized (payloads) {
				PayloadRef ref = payloads.get(payload);
				if (ref != null && --ref.refs <= 0) {
					payloads.remove(payload);
					payloadBytes.addAndGet(-ref.bytes);
				}
			}
		}
	}

	/**
	 * 一次载荷的 wire 字节（JSON 序列化后的 UTF-8 字节数）。
	 *
	 * <p>必须按 UTF-8 的<b>字节</b>算，不能用 {@code String.length()}：那是 UTF-16 code unit
	 * 计数，中文正文一个字符只算 1，实际 wire 上是 3 字节，会把预算低估约 3 倍，
	 * 也会让 #487/T6 据此冻结的计量单位失真。
	 *
	 * <p>只在首次见到该 payload 时计算一次，之后按引用复用；序列化失败按 0 计并保留引用
	 * （宁可低估也不把一次可发送的推送判成超额）。
	 */
	private static int estimatePayloadBytes(Object payload) {
		String json = JsonUtil.toJson(payload);
		return json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
	}

	/** 供测试与观察：当前所有维度的一句话摘要。 */
	List<String> describe() {
		List<String> lines = new ArrayList<>();
		lines.add("nodes=" + nodes.size() + "/" + maxActiveNodes);
		lines.add("payloadBytes=" + payloadBytes.get() + "/" + maxPayloadBytes);
		lines.add("retrySlotsFree=" + retrySlots.get());
		lines.add("globalFreePermits=" + globalTasks.availablePermits());
		return lines;
	}
}
