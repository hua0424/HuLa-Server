-- aichatoverview#504 主人命令权限：默认规则 + 群覆盖 + 指定用户名单
-- 创建日期：2026-11-06
--
-- 数据合同（对应 #504 冻结合同 v1 + 补充裁定 issuecomment-6677394062）：
--   state —— open=允许调用 / closed=不开放 / approval_required=需主人审批（本票拒绝终态，主人自调=自批放行）
--   room_id —— 0=默认规则（私聊及未覆盖群继承）；>0=群覆盖（存在即进入覆盖语义）
--   mode/allowed_uids —— 仅群覆盖行有意义：all=全体成员 / list=allowed_uids 内；
--     名单外用户在该群内=不可调用（拒绝），不回落继承默认；私聊忽略名单。
--   version —— 乐观锁；写操作携带读到版本，失配拒绝。
--   默认规则（代码常量，不落库）：skill:*→open；session.reset/session.compact/model.list/model.set→open；其余 command→closed。
--   无存量数据，无需 grandfather 回填。
--
-- ===========================================================================
-- 部署顺序（务必按序执行）：
--   1) DDL（CREATE TABLE IF NOT EXISTS，幂等，可重跑）
--   2) FLUSH Redis 键 `im:aiclaw:command:policy:*`（升级前若有的话；首发一般为空）
--   3) 再启动启用门控的服务
-- ===========================================================================

CREATE TABLE IF NOT EXISTS `im_aiclaw_command_policy`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花）',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 1 COMMENT '租户',
    `aiclaw_uid`    BIGINT       NOT NULL COMMENT 'Agent 的 uid',
    `room_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '#504: 0=默认规则；>0=群 room_id 覆盖',
    `capability_id` VARCHAR(128) NOT NULL COMMENT '#504: 能力标识（精确 id，如 skill:xxx / session.reset）',
    `state`         VARCHAR(32)  NOT NULL COMMENT '#504: open=允许调用，closed=不开放，approval_required=需主人审批',
    `mode`          VARCHAR(16)  NULL DEFAULT NULL COMMENT '#504: 群覆盖名单模式：all=全体成员，list=指定名单；默认规则行忽略',
    `allowed_uids`  TEXT         NULL DEFAULT NULL COMMENT '#504: mode=list 时的 uid JSON 数组；其余情况忽略',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '#504: 乐观锁版本，写时 +1',
    `is_del`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除（0=有效，1=已删除）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_aiclaw_room_cap` (`aiclaw_uid`, `room_id`, `capability_id`),
    KEY `idx_aiclaw_room` (`aiclaw_uid`, `room_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'aichatoverview#504 Agent 命令调用权限（默认规则+群覆盖+指定名单）';

-- 验证（部署后 DB 断言）：
--   SELECT COUNT(*) FROM im_aiclaw_command_policy WHERE is_del = 0;  -- 首发期望 0（默认规则为代码常量）
--   SHOW INDEX FROM im_aiclaw_command_policy;  -- 期望 uk_aiclaw_room_cap 存在
