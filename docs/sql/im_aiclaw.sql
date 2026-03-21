-- AIclaw 扩展表（REQ-002）
-- 创建日期：2026-03-17

CREATE TABLE IF NOT EXISTS `im_aiclaw` (
  `id`             BIGINT NOT NULL COMMENT '主键',
  `uid`            BIGINT NOT NULL COMMENT '关联 im_user.id',
  `owner_uid`      BIGINT NOT NULL COMMENT '创建者 im_user.id',
  `token_hash`     VARCHAR(128) NOT NULL COMMENT 'bcrypt hash',
  `token_prefix`   VARCHAR(8) NOT NULL COMMENT 'token前8位，快速匹配',
  `machine_code`   VARCHAR(64) DEFAULT NULL COMMENT 'plugins 设备标识',
  `auth_status`    TINYINT NOT NULL DEFAULT 0 COMMENT '0=未激活 1=已激活 2=已停用',
  `adapter_type`   VARCHAR(32) DEFAULT 'openclaw' COMMENT 'claw 类型',
  `adapter_config` TEXT COMMENT '连接参数 JSON',
  `deactivated_at` DATETIME DEFAULT NULL COMMENT '停用时间',
  `tenant_id`      BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
  `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_by`      BIGINT DEFAULT NULL,
  `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by`      BIGINT DEFAULT NULL,
  `is_del`         TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_uid` (`uid`),
  UNIQUE KEY `uk_token_prefix` (`token_prefix`),
  KEY `idx_owner` (`owner_uid`),
  KEY `idx_machine` (`machine_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI助理扩展表';
