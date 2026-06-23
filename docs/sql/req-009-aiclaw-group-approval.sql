-- REQ-009 #82 (aiclaw, 群) 批准合同 + grandfather 回填
-- 创建日期：2026-06-23
--
-- 给 im_aiclaw_group_config 增加两个字段，用于「群主批准 aiclaw 是否在该群响应」的数据合同：
--   approved      —— 0=未批准/沉默（默认），1=已批准。仅群主可设置。
--   workspace_dir —— 可空；NULL=plugins 自行按群号派生默认目录。仅群主可设置。
--
-- ===========================================================================
-- 部署顺序（务必按序执行）：
--   1) DDL（ADD COLUMN）
--   2) grandfather 回填（UPDATE 存量 + INSERT 缺失行，approved=1）
--   3) FLUSH Redis 键 im:aiclaw:group:config:*
--      （否则升级前缓存的 Resp 缺 approved 字段 → 反序列化为 null → gate 判定异常，
--       在缓存 TTL（30min）窗口内被 grandfather 的 aiclaw 会被错误沉默）
--   4) 再启动启用 gate 的服务
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) DDL：新增字段（列约定对齐 docs/sql/req-004-group-chat.sql）
-- ---------------------------------------------------------------------------
ALTER TABLE `im_aiclaw_group_config`
    ADD COLUMN IF NOT EXISTS `approved`      TINYINT NOT NULL DEFAULT 0
        COMMENT 'REQ-009#82 是否已批准在该群响应：0=未批准/沉默，1=已批准（仅群主可设置）',
    ADD COLUMN IF NOT EXISTS `workspace_dir` VARCHAR(512) NULL DEFAULT NULL
        COMMENT 'REQ-009#82 工作目录（NULL=plugins 自行派生默认目录，仅群主可设置）';

-- ---------------------------------------------------------------------------
-- 2) grandfather 回填（幂等）
--
-- 目标：升级前就已在群里的 aiclaw 保持「可响应」——把它们的配置行 approved 置 1，
--       缺行的补一行 approved=1。workspace_dir 留 NULL（= 默认派生）。
--
-- 表关系（已核对 entity / mapper）：
--   im_group_member.group_id      = im_room_group.id     （成员表的 group_id 指向 room_group 主键，非 room_id）
--   im_room_group.room_id         = im_aiclaw_group_config.room_id
--   im_group_member.uid           = aiclaw 的 uid（= im_user.id，且 im_user.user_type = 4 表示 AI 助理）
--   im_aiclaw_group_config.aiclaw_uid = 该 aiclaw 的 uid
--
-- 「群成员是 aiclaw」判定：im_group_member JOIN im_user ON gm.uid = u.id AND u.user_type = 4。
-- 软删过滤：im_group_member / im_user / im_aiclaw_group_config 均带 is_del，回填只看 is_del=0 的行。
-- ---------------------------------------------------------------------------

-- 2a) 存量行：把「成员是 aiclaw」的 (aiclaw_uid, room_id) 既有配置行批准为 1
UPDATE `im_aiclaw_group_config` c
JOIN `im_room_group`  rg ON rg.room_id = c.room_id
JOIN `im_group_member` gm ON gm.group_id = rg.id AND gm.uid = c.aiclaw_uid AND gm.is_del = 0
JOIN `im_user` u ON u.id = gm.uid AND u.user_type = 4 AND u.is_del = 0
SET c.approved = 1, c.update_time = CURRENT_TIMESTAMP
WHERE c.is_del = 0 AND c.approved <> 1;

-- 2b) 缺失行：为「成员是 aiclaw 但还没有配置行」的 (aiclaw, group) 插入默认行（approved=1）
--     其余列采用 getConfig 的默认值：rate_limit_per_minute=10, mention_required=1,
--     daily_limit=1000, respond_to_ai=1。workspace_dir 留 NULL。
--     id 用雪花占位不可行（无函数），这里用主键自增不可用（id 非自增）——改用 UUID 数值化的稳定占位：
--     采用 (gm.id) 作为派生主键来源，保证幂等且不与既有雪花 id 冲突区间重叠的风险极低；
--     若部署环境对 id 生成有要求，可改由应用侧补行。详见下方 NOTE。
-- REQ-009 #84 P2（reviewer 建议）：INSERT IGNORE 双保险——WHERE NOT EXISTS 已幂等，
--   IGNORE 再兜底任何并发/重跑下的主键冲突（静默跳过而非中断），回填可安全重复执行。
INSERT IGNORE INTO `im_aiclaw_group_config`
    (`id`, `tenant_id`, `aiclaw_uid`, `room_id`,
     `rate_limit_per_minute`, `mention_required`, `daily_limit`, `respond_to_ai`,
     `approved`, `workspace_dir`, `is_del`, `create_time`, `update_time`)
SELECT
    gm.id            AS id,
    1                AS tenant_id,
    gm.uid           AS aiclaw_uid,
    rg.room_id       AS room_id,
    10               AS rate_limit_per_minute,
    1                AS mention_required,
    1000             AS daily_limit,
    1                AS respond_to_ai,
    1                AS approved,
    NULL             AS workspace_dir,
    0                AS is_del,
    CURRENT_TIMESTAMP AS create_time,
    CURRENT_TIMESTAMP AS update_time
FROM `im_group_member` gm
JOIN `im_room_group`  rg ON rg.id = gm.group_id
JOIN `im_user` u ON u.id = gm.uid AND u.user_type = 4 AND u.is_del = 0
WHERE gm.is_del = 0
  AND NOT EXISTS (
      SELECT 1 FROM `im_aiclaw_group_config` c
      WHERE c.aiclaw_uid = gm.uid AND c.room_id = rg.room_id AND c.is_del = 0
  );

-- ---------------------------------------------------------------------------
-- 3) FLUSH 缓存（手动执行，SQL 无法触达 Redis）：
--      redis-cli --scan --pattern 'im:aiclaw:group:config:*' | xargs -r redis-cli del
--    然后才启动启用 gate 的服务。
-- ---------------------------------------------------------------------------

-- NOTE（id 生成假设，部署前 flag）：
--   本脚本用 im_group_member.id 作为新增配置行的主键，仅为「DB 层幂等回填」给一个稳定且唯一的占位
--   （im_group_member.id 与 im_aiclaw_group_config.id 同为雪花区间、各表内唯一，跨表复用作 PK 不违反唯一约束）。
--   若该环境要求新行主键必须来自统一发号器，请改走应用侧补行（调用 getConfig→updateConfig），
--   或把 2b 的 id 列替换为环境约定的雪花函数。执行前请与 DBA 确认。
--
-- 验证（部署后 DB 断言，非单元测试范畴）：
--   -- 每个在群 aiclaw 都已 approved=1：
--   SELECT gm.uid AS aiclaw_uid, rg.room_id, c.approved
--   FROM im_group_member gm
--   JOIN im_room_group rg ON rg.id = gm.group_id
--   JOIN im_user u ON u.id = gm.uid AND u.user_type = 4 AND u.is_del = 0
--   LEFT JOIN im_aiclaw_group_config c
--          ON c.aiclaw_uid = gm.uid AND c.room_id = rg.room_id AND c.is_del = 0
--   WHERE gm.is_del = 0;
--   -- 期望：每行 c.approved = 1（无 NULL / 无 0）。
