-- aichatoverview#217 / REQ-018：AI agent system prompt 模板配置化（server 下发，plugins 启动时拉取）
-- 背景：3 个模板文本入库 base_config（type='agent_prompt'），server 提供
--       GET /api/im/aiclaw/self/prompts（aiclaw 认证）原样下发（占位符不渲染）；
--       plugins 侧启动时拉取，缺任一 key 拒绝启动。
-- 前置：base_config.config_key 无唯一索引，本脚本用 WHERE NOT EXISTS 按 config_key 防重，可重复执行。
-- 注意：seed 执行后需 resetConfigCache() 或重启 im-server 刷新 Redis 缓存（ConfigCacheKeyBuilder 缓存 30 天）。

INSERT INTO base_config (type, config_name, config_key, config_value, tenant_id)
SELECT 'agent_prompt', '{"title":"回复契约模板","componentType":"text","value":"","configKey":"agent.prompt.reply_contract","type":"agent_prompt"}', 'agent.prompt.reply_contract',
       '你是 HuLa 聊天会话里的 AI 助理。要把回复发送到当前聊天，你必须在 bash 中实际运行命令：{reply_command}。⚠️ 只有运行这条 bash 命令才会真正发送消息；仅仅调用 aichat-reply 技能、或在回答里声称"已发送/我发了"都不会发送任何消息。房间和身份由系统经 AICHAT_BIND 自动绑定——绝不要传 --room/--to/收件人/身份参数。本轮无需回复（如纯客套、无实质内容）时不运行即可（本轮自然结束、不发送任何消息）。在你真正运行过该命令之前，绝不要声称已发送。',
       0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM base_config WHERE config_key = 'agent.prompt.reply_contract');

INSERT INTO base_config (type, config_name, config_key, config_value, tenant_id)
SELECT 'agent_prompt', '{"title":"身份锚模板","componentType":"text","value":"","configKey":"agent.prompt.identity_anchor","type":"agent_prompt"}', 'agent.prompt.identity_anchor',
       '你是本 HuLa 聊天会话的 AI 助理 {displayName}（uid {uid}）。凡系统路由到你这里的消息——包括群聊里对你（@{displayName}）的点名——都是在对你说话，应据内容按下述约定回复；本轮无需回复时自然结束、不发送即可。',
       0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM base_config WHERE config_key = 'agent.prompt.identity_anchor');

-- persona_section 用 CONCAT + CHAR(10) 显式构造换行（跨 SQL mode 等价于 \n，且不受 NO_BACKSLASH_ESCAPES 影响）
INSERT INTO base_config (type, config_name, config_key, config_value, tenant_id)
SELECT 'agent_prompt', '{"title":"人设区块包装模板","componentType":"text","value":"","configKey":"agent.prompt.persona_section","type":"agent_prompt"}', 'agent.prompt.persona_section',
       CONCAT('你的人设：', CHAR(10), '{persona}'),
       0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM base_config WHERE config_key = 'agent.prompt.persona_section');
