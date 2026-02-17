-- AI审批审计日志表
-- 用于记录 AI 审批的所有操作（approve/reject/timeout）
CREATE TABLE IF NOT EXISTS `im_ai_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `request_id` VARCHAR(64) NOT NULL COMMENT '审批请求ID',
    `ai_user_id` BIGINT NOT NULL COMMENT 'AI用户ID',
    `owner_uid` BIGINT NOT NULL COMMENT 'Owner用户ID',
    `requester_uid` BIGINT NOT NULL COMMENT '请求者用户ID',
    `original_text` TEXT COMMENT '用户原始请求文本',
    `final_text` TEXT COMMENT 'Owner改写后的文本',
    `action` VARCHAR(16) NOT NULL COMMENT '操作类型: approve/reject/timeout',
    `role` VARCHAR(16) COMMENT '审批角色: viewer/admin',
    `reason` VARCHAR(256) COMMENT '拒绝原因或备注',
    `created_at` BIGINT NOT NULL COMMENT '创建时间戳',
    `tenant_id` BIGINT COMMENT '租户ID',
    PRIMARY KEY (`id`),
    KEY `idx_request_id` (`request_id`),
    KEY `idx_ai_user_id` (`ai_user_id`),
    KEY `idx_owner_uid` (`owner_uid`),
    KEY `idx_requester_uid` (`requester_uid`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI审批审计日志表';
