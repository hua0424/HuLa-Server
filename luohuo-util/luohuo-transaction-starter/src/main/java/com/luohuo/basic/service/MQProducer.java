package com.luohuo.basic.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import com.luohuo.basic.annotation.SecureInvoke;

/**
 * Description: 发送mq工具类
 * Date: 2023-08-12
 */
@Slf4j
@Component
public class MQProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

	public void sendMsg(String topic, Object body) {
		Message<Object> build = MessageBuilder.withPayload(body).build();
		rocketMQTemplate.send(topic, build);
	}

    /**
     * 发送可靠消息，在事务提交后保证发送成功
     *
     * @param topic
     * @param body
     */
    // 200 attempts with a 64-minute capped delay protect >8 days; permanent faults stay in FAIL
    // for diagnosed, operator-approved replay instead of an infinite retry queue.
    @SecureInvoke(maxRetryTimes = 200)
    public void sendSecureMsg(String topic, Object body, Object key) {
        Message<Object> build = MessageBuilder
                .withPayload(body)
                .setHeader("KEYS", key)
                .build();
        rocketMQTemplate.send(topic, build);
    }

	/**
	 * 发送可靠延迟消息。
	 *
	 * @param topic 主题
	 * @param body 消息体
	 * @param delaySeconds 期望延迟秒数（不是级别下标）
	 */
	public void sendMsgWithDelay(String topic, Object body, int delaySeconds) {
		int level = calculateDelayLevel(delaySeconds);
		rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(body).build(),
				calculateTimeoutEnhanced(level), level);
	}

	/**
	 * 发送可靠延迟消息，显式指定发送超时。
	 *
	 * <p>#486 T5：延迟时长与发送超时是两件事——前者由 broker 的延迟级别决定，
	 * 后者只约束「这一次 syncSend 调用等多久」。调用方给了确定预算时以它为准，
	 * 不能再用延迟级别的秒数推导发送超时（此前两者共用一个 int，导致 30 秒延迟的
	 * 发送调用要等满 1 小时才失败）。
	 *
	 * @param delaySeconds 期望延迟秒数（不是级别下标）
	 * @param sendTimeoutMillis 本次发送超时；&lt;=0 时回落到按级别推导的默认预算
	 */
	public void sendMsgWithDelay(String topic, Object body, int delaySeconds, long sendTimeoutMillis) {
		int level = calculateDelayLevel(delaySeconds);
		long timeout = sendTimeoutMillis > 0 ? sendTimeoutMillis : calculateTimeoutEnhanced(level);
		rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(body).build(), timeout, level);
	}

	// RocketMQ支持的延迟级别映射 对应配置文件里面的!!!
	private static final int[] ROCKETMQ_DELAY_LEVELS = {
			1,    // 1s
			3,    // 3s
			5,    // 5s
			10,   // 10s
			30,   // 30s
			60,   // 1m
			120,  // 2m
			180,  // 3m
			240,  // 4m
			300,  // 5m
			360,  // 6m
			420,  // 7m
			480,  // 8m
			540,  // 9m
			600,  // 10m
			1200, // 20m
			1800, // 30m
			3600, // 1h
			7200  // 2h
	};

	/**
	 * 按延迟秒数计算最接近的 RocketMQ 延迟级别（入参单位：秒）。
	 *
	 * @param delaySeconds 延迟秒数
	 * @return RocketMQ 延迟级别（1-19）
	 */
	public static int delayLevelOf(long delaySeconds) {
		// 如果延迟时间小于1秒，按1秒处理
		if (delaySeconds <= 0) {
			return 1;
		}

		// 查找最接近的延迟级别
		for (int i = 0; i < ROCKETMQ_DELAY_LEVELS.length; i++) {
			if (delaySeconds <= ROCKETMQ_DELAY_LEVELS[i]) {
				return i + 1;
			}
		}

		// 超过最大延迟时间，使用最大级别
		return ROCKETMQ_DELAY_LEVELS.length;
	}

	/**
	 * 按延迟级别计算发送超时预算（毫秒）：短延迟 +5s、中等 +10s、长延迟 +30s 且封顶 1 小时。
	 *
	 * @param level RocketMQ 延迟级别（1-19）；越界按最大级别处理
	 */
	public static long sendTimeoutMillisOf(int level) {
		return calculateTimeoutEnhanced(level);
	}

	/** 级别对应的实际延迟秒数；越界返回最大级别。 */
	public static long delaySecondsOf(int level) {
		int normalized = level < 1 || level > ROCKETMQ_DELAY_LEVELS.length ? ROCKETMQ_DELAY_LEVELS.length : level;
		return ROCKETMQ_DELAY_LEVELS[normalized - 1];
	}

	private int calculateDelayLevel(long delaySeconds) {
		return delayLevelOf(delaySeconds);
	}

	/**
	 * 动态计算延迟消息发送的超时时间
	 * @param delayLevel
	 * @return
	 */
	private static long calculateTimeoutEnhanced(int delayLevel) {
		if (delayLevel < 1 || delayLevel > ROCKETMQ_DELAY_LEVELS.length) {
			delayLevel = ROCKETMQ_DELAY_LEVELS.length;
		}

		long delaySeconds = ROCKETMQ_DELAY_LEVELS[delayLevel - 1];
		long delayMillis = delaySeconds * 1000L;

		// 分段超时策略
		if (delaySeconds <= 30) {
			// 短延迟： + 5秒缓冲
			return delayMillis + 5000L;
		} else if (delaySeconds <= 300) {
			// 中等延迟： + 10秒缓冲
			return delayMillis + 10000L;
		} else {
			// 长延迟：+ 30秒缓冲，但最大不超过1小时
			long maxTimeout = 60 * 60 * 1000L;
			return Math.min(delayMillis + 30000L, maxTimeout);
		}
	}
}
