-- 兼容 AI 审计日志实体字段（SuperEntity/TenantEntity）
-- 目标：补齐 create_by / update_by / is_del / create_time / update_time，
-- 避免查询与插入命中 Unknown column 错误。
ALTER TABLE `im_ai_audit_log`
    ADD COLUMN IF NOT EXISTS `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    ADD COLUMN IF NOT EXISTS `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
    ADD COLUMN IF NOT EXISTS `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD COLUMN IF NOT EXISTS `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
    ADD COLUMN IF NOT EXISTS `is_del` bit(1) DEFAULT b'0' COMMENT '逻辑删除';
