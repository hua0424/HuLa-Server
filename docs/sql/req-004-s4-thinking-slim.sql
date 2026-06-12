-- REQ-004 [S4] thinking 协议精简
-- 1. content 由 TEXT 升级为 MEDIUMTEXT（END 携带全文，最大 200KB 截断仍需更大容量余量）
ALTER TABLE im_aiclaw_thinking MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT '完整思考文本';
-- 2. status 增加 4=超长截断 状态
ALTER TABLE im_aiclaw_thinking MODIFY COLUMN status TINYINT DEFAULT '0' COMMENT '状态：0=进行中 1=成功 2=错误 3=超时 4=超长截断';
