package com.luohuo.basic.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM 级停止排空时钟（aichatoverview#461）：为同一 JVM 内多个关停参与者
 * （WS/IM PushService、@SecureInvoke 重放）提供统一的单调时钟截止与结算计数。
 *
 * <p>关停语义（承接 #450 §6.3 / #451）：
 * <ul>
 *   <li>{@link #beginDrain(long)} 幂等，首个调用者设定截止，后续调用者沿用同一截止，不续期；</li>
 *   <li>截止前：已接收任务可继续完成，在途重放允许跑完；</li>
 *   <li>截止后：不再新启（重放记"未执行"），已开始未结束的重放记"结果不确定"；</li>
 *   <li>计数只增不减，供关停汇总日志如实结算，不虚报。</li>
 * </ul>
 */
public final class ShutdownDrainClock {

	private static final AtomicBoolean DRAINING = new AtomicBoolean(false);
	private static volatile long deadlineNanos = Long.MAX_VALUE;

	private static final AtomicLong replaysStarted = new AtomicLong();
	private static final AtomicLong replaysFinished = new AtomicLong();
	private static final AtomicLong replaysSkipped = new AtomicLong();

	private ShutdownDrainClock() {
	}

	/**
	 * 开始排空。CAS 幂等：首个调用者按 budgetSeconds 设定截止，之后的调用（含不同预算）沿用首次截止。
	 *
	 * @return 本次调用是否为开启者（用于汇总日志归属）
	 */
	public static boolean beginDrain(long budgetSeconds) {
		if (DRAINING.compareAndSet(false, true)) {
			deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, budgetSeconds));
			return true;
		}
		return false;
	}

	public static boolean isDraining() {
		return DRAINING.get();
	}

	/** 单调时钟基准（System.nanoTime），未开始排空前为 Long.MAX_VALUE。 */
	public static long deadlineNanos() {
		return deadlineNanos;
	}

	public static boolean pastDeadline() {
		return System.nanoTime() - deadlineNanos >= 0;
	}

	/** 距截止剩余毫秒，未开始排空返回 Long.MAX_VALUE，已过期返回 0。 */
	public static long remainingMillis() {
		long deadline = deadlineNanos;
		if (deadline == Long.MAX_VALUE) {
			return Long.MAX_VALUE;
		}
		long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
		return Math.max(0, remaining);
	}

	// ---- @SecureInvoke 重放结算计数（#461 §6.3）----

	/** 重放实际开始执行时记一次（在途 = started - finished）。 */
	public static void replayStarted() {
		replaysStarted.incrementAndGet();
	}

	/** 重放执行结束（无论成败）记一次。 */
	public static void replayFinished() {
		replaysFinished.incrementAndGet();
	}

	/** 排空期跳过新启重放时记一次（未执行，记录保留待下次启动重放）。 */
	public static void replaySkipped() {
		replaysSkipped.incrementAndGet();
	}

	public static long activeReplays() {
		return replaysStarted.get() - replaysFinished.get();
	}

	public static long skippedReplays() {
		return replaysSkipped.get();
	}

	/** 仅供单测隔离重置静态状态，业务代码不得调用。 */
	public static void reset() {
		DRAINING.set(false);
		deadlineNanos = Long.MAX_VALUE;
		replaysStarted.set(0);
		replaysFinished.set(0);
		replaysSkipped.set(0);
	}
}
