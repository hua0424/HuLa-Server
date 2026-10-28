package com.luohuo.flex.im.core.user.service.dto;

/**
 * #482 请求级推送收据载体。
 * <p>
 * 业务事务（含 {@code @Transactional} 方法和 {@code TransactionTemplate}）在提交后才真正
 * 提交推送，而 HTTP 响应在那之后才生成。因此调用方在事务外创建本载体、传入业务方法，
 * 由提交后回调写入准入结论，方法返回（提交完成）后再读取——不用 ThreadLocal 旁路，
 * 也不在提交回调里等待 MQ。
 */
public final class PushReceipt {

	private volatile PushSubmission submission;

	/** 提交后回调写入；重复写入只保留第一次，避免同一请求出现两个互相矛盾的结论。 */
	public synchronized void record(PushSubmission result) {
		if (result != null && submission == null) {
			submission = result;
		}
	}

	/** 读取快照；从未写入时返回只读空快照（NO_TARGET），不返回 null。 */
	public PushSubmission snapshot() {
		PushSubmission current = submission;
		return current == null ? PushSubmission.none() : current;
	}

	public boolean isEmpty() {
		return submission == null;
	}
}
