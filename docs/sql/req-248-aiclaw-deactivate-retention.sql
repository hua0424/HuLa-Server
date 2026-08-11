-- aichatoverview#248：aiclaw 停用保留时长配置化（彻底删除的等待时长由 base_config 配置，方便测试调短）
-- 背景：aiclaw 停用后的彻底删除时长原先硬编码 24h（restore 恢复窗口判断 + purgeExpiredDeactivated 扫描 cutoff），
--       现改为读 base_config（type='aiclaw'）的 aiclaw.deactivate.retention.minutes（单位：分钟）。
--       server 侧 AiclawServiceImpl.getDeactivateRetentionMinutes() 读取：缺行/空白/非数字/≤0 一律回退默认 1440（= 现状 24h）。
-- 前置：base_config.config_key 无唯一索引，本脚本用 WHERE NOT EXISTS 按 config_key 防重，可重复执行。
-- 注意：sysConfigService.get 走 Redis 缓存（ConfigCacheKeyBuilder），seed 后需 resetConfigCache() 或重启 im-server 刷新缓存才生效。
--       dev/测试环境调短保留时长（如 1 分钟）由 manager 在 dev 库改 config_value 后走配置失效/重启生效（本脚本默认 1440 = 生产现状）。

INSERT INTO base_config (type, config_name, config_key, config_value, tenant_id)
SELECT 'aiclaw', '{"title":"AI 助理停用保留时长（分钟）","componentType":"text","value":"","configKey":"aiclaw.deactivate.retention.minutes","type":"aiclaw"}', 'aiclaw.deactivate.retention.minutes',
       '1440',
       0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM base_config WHERE config_key = 'aiclaw.deactivate.retention.minutes');
