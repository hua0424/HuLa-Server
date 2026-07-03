-- BUGFIX-122: im_aiclaw.machine_code 唯一约束
-- 背景：machine_code 即 WS clientId。两个 aiclaw 曾拿到相同 machine_code，
--       推送路由以 clientId→uid 建映射，二者共用同一 clientId 时静默覆盖，
--       其中一方被永久踢出群消息推送。本迁移把原非唯一索引升级为唯一索引，
--       从 DB 层杜绝重复机器码（服务层激活查重 + 客户端唯一码生成为配套防线）。
-- 创建日期：2026-07-03
--
-- 注意：machine_code 可为 NULL（未激活的 aiclaw）。MySQL 唯一索引下允许多个 NULL 并存，
--       因此未激活的 aiclaw 不受影响，仅约束「已绑定的非空机器码」全局唯一。
--
-- 前置校验：原索引名为 idx_machine（见 im_aiclaw.sql）。若线上索引名不同，请调整 DROP 语句。

ALTER TABLE im_aiclaw DROP INDEX idx_machine;
ALTER TABLE im_aiclaw ADD UNIQUE INDEX uk_machine_code (machine_code);
