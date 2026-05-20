package com.luohuo.flex.ws.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * aiclaw 限流检查器（ws-biz 模块内，用于 THINKING_START 前置校验）
 */
@Slf4j
@Component
public class AiclawRateLimitChecker {

	private final RedisTemplate<String, Object> redisTemplate;

	private static final String RATE_KEY_PREFIX = "im:aiclaw:rate:";
	private static final String DAILY_KEY_PREFIX = "im:aiclaw:daily:";

	private static final int DEFAULT_RATE_LIMIT = 10;
	private static final int DEFAULT_DAILY_LIMIT = 1000;
	private static final Duration RATE_TTL = Duration.ofMinutes(2);
	private static final Duration DAILY_TTL = Duration.ofHours(25);

	private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
	private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

	public AiclawRateLimitChecker(RedisTemplate<String, Object> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public enum LimitResult {
		ALLOWED, RATE_LIMITED, DAILY_LIMITED
	}

	/**
	 * 检查是否触发限流
	 */
	public LimitResult check(Long aiclawUid, Long roomId) {
		// 1. 检查日限
		String dailyKey = buildDailyKey(aiclawUid, roomId);
		Object dailyVal = redisTemplate.opsForValue().get(dailyKey);
		int dailyCount = dailyVal != null ? Integer.parseInt(dailyVal.toString()) : 0;
		if (dailyCount >= DEFAULT_DAILY_LIMIT) {
			log.warn("aiclaw daily limit exceeded: aiclawUid={}, roomId={}, count={}", aiclawUid, roomId, dailyCount);
			return LimitResult.DAILY_LIMITED;
		}

		// 2. 检查频率（滑动窗口：当前分钟 + 上一分钟）
		LocalDateTime now = LocalDateTime.now();
		String currentMinute = now.format(MINUTE_FORMATTER);
		String prevMinute = now.minusMinutes(1).format(MINUTE_FORMATTER);

		String currentKey = buildRateKey(aiclawUid, roomId, currentMinute);
		String prevKey = buildRateKey(aiclawUid, roomId, prevMinute);

		Object currentVal = redisTemplate.opsForValue().get(currentKey);
		Object prevVal = redisTemplate.opsForValue().get(prevKey);

		int currentCount = currentVal != null ? Integer.parseInt(currentVal.toString()) : 0;
		int prevCount = prevVal != null ? Integer.parseInt(prevVal.toString()) : 0;

		int totalRate = currentCount + prevCount;
		if (totalRate >= DEFAULT_RATE_LIMIT) {
			log.warn("aiclaw rate limit exceeded: aiclawUid={}, roomId={}, total={}", aiclawUid, roomId, totalRate);
			return LimitResult.RATE_LIMITED;
		}

		return LimitResult.ALLOWED;
	}

	/**
	 * 记录一次发言（限流通过后才调用）
	 */
	public void record(Long aiclawUid, Long roomId) {
		LocalDateTime now = LocalDateTime.now();
		String minute = now.format(MINUTE_FORMATTER);
		String day = now.format(DAY_FORMATTER);

		String rateKey = buildRateKey(aiclawUid, roomId, minute);
		Long rateCount = redisTemplate.opsForValue().increment(rateKey);
		if (rateCount != null && rateCount == 1) {
			redisTemplate.expire(rateKey, RATE_TTL);
		}

		String dailyKey = buildDailyKey(aiclawUid, roomId);
		Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
		if (dailyCount != null && dailyCount == 1) {
			redisTemplate.expire(dailyKey, DAILY_TTL);
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
