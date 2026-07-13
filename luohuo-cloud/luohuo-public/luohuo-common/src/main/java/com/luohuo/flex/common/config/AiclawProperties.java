package com.luohuo.flex.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * aiclaw 运维参数（限流默认/超时/TTL）。默认值=历史硬编码值，Nacos 无 aiclaw 段时行为不变。
 */
@Data
@ConfigurationProperties(prefix = "aiclaw")
public class AiclawProperties {
    private final Rate rate = new Rate();
    private final Thinking thinking = new Thinking();
    private final Stream stream = new Stream();
    private final Config config = new Config();
    private final Approve approve = new Approve();

    @Data public static class Rate {
        /** 默认频率限制：条/分钟 */
        private int defaultPerMinute = 10;
        /** 默认日限：条/天 */
        private int defaultDaily = 1000;
    }
    @Data public static class Thinking {
        /** thinking 超时（毫秒），默认 5 分钟 */
        private long timeoutMs = 300000L;
    }
    @Data public static class Stream {
        /** 流式超时（毫秒），默认 30 秒 */
        private long timeoutMs = 30000L;
    }
    @Data public static class Config {
        /** 群配置缓存 TTL（分钟） */
        private int cacheTtlMinutes = 30;
    }
    @Data public static class Approve {
        /** 入群待批准通知去重 TTL（小时） */
        private int notifyTtlHours = 24;
    }
}
