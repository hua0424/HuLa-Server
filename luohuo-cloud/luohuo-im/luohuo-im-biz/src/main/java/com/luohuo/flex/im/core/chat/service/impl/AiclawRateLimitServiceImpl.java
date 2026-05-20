package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.core.chat.service.AiclawRateLimitService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * aiclaw 限流服务实现（Redis 滑动窗口）
 */
@Slf4j
@Service
@AllArgsConstructor
public class AiclawRateLimitServiceImpl implements AiclawRateLimitService {

	private final StringRedisTemplate stringRedisTemplate;

	private static final String RATE_KEY_PREFIX = "im:aiclaw:rate:";
	private static final String DAILY_KEY_PREFIX = "im:aiclaw:daily:";

	/** 默认频率限制：10 条/分钟 */
	private static final int DEFAULT_RATE_LIMIT = 10;
	/** 默认日限：1000 条 */
	private static final int DEFAULT_DAILY_LIMIT = 1000;
	/** 频率桶 TTL：2 分钟 */
	private static final Duration RATE_TTL = Duration.ofMinutes(2);
	/** 日限桶 TTL：25 小时 */
	private static final Duration DAILY_TTL = Duration.ofHours(25);

	private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

	@Override
	public LimitResult checkRateLimit(Long aiclawUid, Long roomId) {
		// 1. 检查日限（更严格的先检查）
		String dailyKey = buildDailyKey(aiclawUid, roomId);
		String dailyCountStr = stringRedisTemplate.opsForValue().get(dailyKey);
		int dailyCount = dailyCountStr != null ? Integer.parseInt(dailyCountStr) : 0;
		if (dailyCount >= DEFAULT_DAILY_LIMIT) {
			log.warn("aiclaw daily limit exceeded: aiclawUid={}, roomId={}, count={}", aiclawUid, roomId, dailyCount);
			return LimitResult.DAILY_LIMITED;
		}

		// 2. 检查频率限制（滑动窗口：当前分钟 + 上一分钟）
		LocalDateTime now = LocalDateTime.now();
		String currentMinute = now.format(MINUTE_FORMATTER);
		String prevMinute = now.minusMinutes(1).format(MINUTE_FORMATTER);

		String currentKey = buildRateKey(aiclawUid, roomId, currentMinute);
		String prevKey = buildRateKey(aiclawUid, roomId, prevMinute);

		String currentCountStr = stringRedisTemplate.opsForValue().get(currentKey);
		String prevCountStr = stringRedisTemplate.opsForValue().get(prevKey);

		int currentCount = currentCountStr != null ? Integer.parseInt(currentCountStr) : 0;
		int prevCount = prevCountStr != null ? Integer.parseInt(prevCountStr) : 0;

		// 滑动窗口：上一分钟的计数按时间比例衰减（简化处理：直接累加最近2分钟）
		int totalRate = currentCount + prevCount;
		if (totalRate >= DEFAULT_RATE_LIMIT) {
			log.warn("aiclaw rate limit exceeded: aiclawUid={}, roomId={}, current={}, prev={}, total={}",
					aiclawUid, roomId, currentCount, prevCount, totalRate);
			return LimitResult.RATE_LIMITED;
		}

		return LimitResult.ALLOWED;
	}

	@Override
	public void recordMessage(Long aiclawUid, Long roomId) {
		LocalDateTime now = LocalDateTime.now();
		String minute = now.format(MINUTE_FORMATTER);
		String day = now.format(DAY_FORMATTER);

		// 频率计数器 INCR
		String rateKey = buildRateKey(aiclawUid, roomId, minute);
		Long rateCount = stringRedisTemplate.opsForValue().increment(rateKey);
		if (rateCount != null && rateCount == 1) {
			stringRedisTemplate.expire(rateKey, RATE_TTL);
		}

		// 日限计数器 INCR
		String dailyKey = buildDailyKey(aiclawUid, roomId);
		Long dailyCount = stringRedisTemplate.opsForValue().increment(dailyKey);
		if (dailyCount != null && dailyCount == 1) {
			stringRedisTemplate.expire(dailyKey, DAILY_TTL);
		}
	}

	private String buildRateKey(Long aiclawUid, Long roomId, String minute) {
		return RATE_KEY_PREFIX + aiclawUid + ":" + roomId + ":" + minute;
	}

	private String buildDailyKey(Long aiclawUid, Long roomId) {
		String day = LocalDateTime.now().format(DAY_FORMATTER);
		return DAILY_KEY_PREFIX + aiclawUid + ":" + roomId + ":" + day;
	}
}
