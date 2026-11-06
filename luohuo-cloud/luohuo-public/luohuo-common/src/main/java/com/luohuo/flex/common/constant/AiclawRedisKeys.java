package com.luohuo.flex.common.constant;

/**
 * aiclaw 相关 Redis key 前缀单一事实源（跨 ws/im 模块共享，改前缀两模块同步生效）。
 * ⚠️ 线上有活跃数据，值不得变。
 */
public final class AiclawRedisKeys {
    private AiclawRedisKeys() {}
    /** aiclaw 群配置缓存 key 前缀 */
    public static final String GROUP_CONFIG_PREFIX = "im:aiclaw:group:config:";
    /** aichatoverview#504 命令权限缓存 key 前缀（值：该 Agent 显式覆盖行 JSON 数组） */
    public static final String COMMAND_POLICY_PREFIX = "im:aiclaw:command:policy:";
}
