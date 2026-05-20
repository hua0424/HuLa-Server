-- REQ-004 aiclaw 群聊扩展表
-- 创建日期：2026-05-20

-- aiclaw 群聊配置表
CREATE TABLE IF NOT EXISTS `im_aiclaw_group_config` (
  `id`                    BIGINT NOT NULL COMMENT '主键（雪花ID）',
  `tenant_id`             BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
  `aiclaw_uid`            BIGINT NOT NULL COMMENT 'aiclaw 的 uid',
  `room_id`               BIGINT NOT NULL COMMENT '群聊 room_id',
  `rate_limit_per_minute` INT UNSIGNED DEFAULT 10 COMMENT '频率限制（条/分钟），0=无限制',
  `mention_required`      TINYINT UNSIGNED DEFAULT 0 COMMENT '是否需要 @ 触发：0=否，1=是',
  `daily_limit`           INT UNSIGNED DEFAULT 1000 COMMENT '每日发言上限',
  `respond_to_ai`         TINYINT UNSIGNED DEFAULT 1 COMMENT '是否响应其他 aiclaw：0=否，1=是',
  `short_reply_threshold` INT UNSIGNED DEFAULT 10 COMMENT '短回复字符阈值',
  `short_reply_lookback`  INT UNSIGNED DEFAULT 3 COMMENT '短回复检查最近 N 条',
  `is_del`                TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=删除',
  `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by`             BIGINT DEFAULT NULL COMMENT '创建人',
  `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `update_by`             BIGINT DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aiclaw_room` (`aiclaw_uid`, `room_id`),
  KEY `idx_room_id` (`room_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='aiclaw 群聊配置表';

-- aiclaw thinking 记录表
CREATE TABLE IF NOT EXISTS `im_aiclaw_thinking` (
  `id`             BIGINT NOT NULL COMMENT '主键（雪花ID）',
  `tenant_id`      BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
  `aiclaw_uid`     BIGINT NOT NULL COMMENT '产生 thinking 的 aiclaw uid',
  `room_id`        BIGINT NOT NULL COMMENT '所属群聊 room_id',
  `trigger_msg_id` BIGINT DEFAULT NULL COMMENT '触发本次 thinking 的消息 ID（im_message.id）',
  `content`        TEXT NOT NULL COMMENT '完整思考文本',
  `duration_ms`    INT DEFAULT NULL COMMENT '处理耗时（毫秒），THINKING_END 时回填',
  `has_response`   TINYINT UNSIGNED DEFAULT 0 COMMENT '是否产生了回复消息：0=否，1=是',
  `is_del`         TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by`      BIGINT DEFAULT NULL COMMENT '创建人',
  PRIMARY KEY (`id`),
  KEY `idx_aiclaw_room` (`aiclaw_uid`, `room_id`),
  KEY `idx_trigger_msg` (`trigger_msg_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_has_response_create` (`has_response`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='aiclaw thinking 记录表';

-- thinking 与回复消息关联表
CREATE TABLE IF NOT EXISTS `im_aiclaw_thinking_msg_rel` (
  `thinking_id` BIGINT NOT NULL COMMENT 'thinking 记录 ID',
  `msg_id`      BIGINT NOT NULL COMMENT '关联的 im_message.id',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联建立时间',
  PRIMARY KEY (`thinking_id`, `msg_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='thinking 与回复消息关联表';

-- M1-fix: 为已创建的表补全 tenant_id（TenantLineInnerInterceptor 要求）
ALTER TABLE `im_aiclaw_thinking` ADD COLUMN IF NOT EXISTS `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID';
ALTER TABLE `im_aiclaw_group_config` ADD COLUMN IF NOT EXISTS `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID';
