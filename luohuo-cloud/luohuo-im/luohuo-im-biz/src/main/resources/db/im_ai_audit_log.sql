-- AI审批审计日志表
CREATE TABLE IF NOT EXISTS `im_ai_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `request_id` varchar(64) DEFAULT NULL COMMENT '审批请求ID',
  `ai_user_id` bigint DEFAULT NULL COMMENT 'AI用户ID',
  `owner_uid` bigint DEFAULT NULL COMMENT 'Owner用户ID',
  `requester_uid` bigint DEFAULT NULL COMMENT '请求者用户ID',
  `original_text` text COMMENT '原始请求文本',
  `final_text` text COMMENT '最终执行的文本',
  `action` varchar(32) DEFAULT NULL COMMENT '操作类型：approve/reject/timeout',
  `role` varchar(32) DEFAULT NULL COMMENT '授权角色：viewer/admin',
  `reason` varchar(512) DEFAULT NULL COMMENT '原因/备注',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_owner_uid` (`owner_uid`),
  KEY `idx_ai_user_id` (`ai_user_id`),
  KEY `idx_request_id` (`request_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI审批审计日志';
