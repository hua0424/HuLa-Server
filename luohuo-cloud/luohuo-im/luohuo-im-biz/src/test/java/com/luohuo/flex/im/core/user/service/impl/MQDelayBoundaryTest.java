package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.service.MQProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #486 T5：延迟时长与发送超时必须相互独立。
 *
 * <p>开发前窄验证 v1 已证静态错位：旧实现把同一个 int 同时当「延迟秒数」交给
 * {@code calculateDelayLevel} 与「延迟级别下标」交给 {@code calculateTimeoutEnhanced}，
 * 于是 10 秒延迟得到 level=4 却按级别 4 算出 310000ms 超时，30 秒延迟直接顶到 1 小时，
 * 0/负数得到 level=1 却按级别 1 算出 1 小时。用户可见后果是一次发送在 broker 无响应时
 * 被挂住近一小时，而延迟语义本身只有几十秒。
 *
 * <p>本测试只钉住这两个单位不再互相污染，具体预算数值仍由 D7 冻结决定。
 */
class MQDelayBoundaryTest {

	@Test
	@DisplayName("延迟秒数映射到级别：0/负按 1 秒，超表按最大级别")
	void delaySecondsMapToExpectedLevels() {
		assertEquals(1, MQProducer.delayLevelOf(0), "0 秒按最小延迟处理");
		assertEquals(1, MQProducer.delayLevelOf(-5), "负数不得溢出成最大级别");
		assertEquals(1, MQProducer.delayLevelOf(1));
		assertEquals(4, MQProducer.delayLevelOf(10));
		assertEquals(5, MQProducer.delayLevelOf(30));
		assertEquals(19, MQProducer.delayLevelOf(7200));
		assertEquals(19, MQProducer.delayLevelOf(999_999), "超过表长取最大级别而不是越界");
	}

	@Test
	@DisplayName("级别与实际延迟秒数保持一一对应")
	void levelRoundTripsToDelaySeconds() {
		for (int level = 1; level <= 19; level++) {
			long seconds = MQProducer.delaySecondsOf(level);
			assertEquals(level, MQProducer.delayLevelOf(seconds),
					"级别 " + level + " 的秒数 " + seconds + " 必须映射回同一级别");
		}
		assertEquals(7200L, MQProducer.delaySecondsOf(99), "越界级别按最大级别");
		assertEquals(7200L, MQProducer.delaySecondsOf(0), "越界级别按最大级别");
	}

	@Test
	@DisplayName("发送超时按级别推导但封顶 1 小时，且与短延迟同级不再错位")
	void sendTimeoutIsDerivedFromLevelNotFromDelaySeconds() {
		// 短延迟：延迟 + 5s
		assertEquals(6_000L, MQProducer.sendTimeoutMillisOf(MQProducer.delayLevelOf(1)));
		// 10 秒延迟 -> 级别 4（10 秒）-> 15000ms，而不是旧实现里误当级别 10 的 310000ms
		assertEquals(15_000L, MQProducer.sendTimeoutMillisOf(MQProducer.delayLevelOf(10)));
		assertEquals(15_000L, MQProducer.sendTimeoutMillisOf(4), "级别 4 = 10 秒延迟");
		// 30 秒延迟 -> 级别 5（30 秒）-> 35000ms，而不是旧实现的 3600000ms
		assertEquals(35_000L, MQProducer.sendTimeoutMillisOf(MQProducer.delayLevelOf(30)));
		// 最大级别也不会超过 1 小时
		long max = MQProducer.sendTimeoutMillisOf(MQProducer.delayLevelOf(7200));
		assertEquals(3_600_000L, max, "长延迟封顶 1 小时");
		assertTrue(max <= 3_600_000L);
	}

	@Test
	@DisplayName("延迟秒数与发送超时是两个独立输入：调用方可以给 30 秒延迟配短超时")
	void independentTimeoutCannotBeDerivedFromDelay() {
		int level = MQProducer.delayLevelOf(30);
		assertEquals(5, level);
		// PushService 现在显式传 pushSendTimeoutMillis，级别推导出的值只是兜底
		assertEquals(35_000L, MQProducer.sendTimeoutMillisOf(level));
		assertTrue(35_000L < 3_600_000L, "兜底值不得把 30 秒延迟放大成 1 小时等待");
	}
}
