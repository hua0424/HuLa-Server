package com.luohuo.flex.im.core.chat.service;

/**
 * aiclaw 限流服务（Redis 滑动窗口）
 */
public interface AiclawRateLimitService {

	/**
	 * 限流检查结果
	 */
	enum LimitResult {
		ALLOWED,           // 允许
		RATE_LIMITED,      // 频率超限
		DAILY_LIMITED      // 日限超限
	}

	/**
	 * 检查 aiclaw 在指定群是否触发限流
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 * @return 限流结果
	 */
	LimitResult checkRateLimit(Long aiclawUid, Long roomId);

	/**
	 * 记录一次发言（INCR 计数器）
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 */
	void recordMessage(Long aiclawUid, Long roomId);
}
