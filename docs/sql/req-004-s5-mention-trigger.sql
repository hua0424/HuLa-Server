-- REQ-004 [S5] 群聊 @ 触发 + 惰性积累
-- 群聊默认改为「需要 @ 触发」：mention_required 默认值 0 -> 1，并回填历史数据。
-- 创建日期：2026-06-13
-- 注意：仅 server 侧改动，触发判定在 plugins 端。请在测试库手动执行。

-- 先回填历史数据（含 NULL 与 0），再加 NOT NULL 约束——保证文件自洽可重放，
-- 不依赖执行人先做 NULL 预检；先约束后回填会因存量 NULL 行报错。
UPDATE im_aiclaw_group_config SET mention_required = 1 WHERE mention_required IS NULL OR mention_required = 0;
ALTER TABLE im_aiclaw_group_config MODIFY COLUMN mention_required TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否需要 @ 触发：0=否，1=是';
