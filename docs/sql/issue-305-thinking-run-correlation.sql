-- Issue #305 / T14; apply to each IM database before deploying server code.
-- Observed hula.im_aiclaw_thinking (SHOW CREATE TABLE): InnoDB utf8mb4, id PK,
-- tenant_id/aiclaw_uid/room_id/trigger_msg_id already present; no run column.
-- Nullable for old STARTs; MySQL UNIQUE permits multiple NULL legacy records.
-- hula had 790 rows at inspection; production volume not checked. Unique-index creation takes
-- metadata locks and may rebuild large tables; schedule off-peak with backups and DB monitoring.
-- Rollback after reverting server code: DROP INDEX uk_thinking_tenant_actor_run,
-- then DROP COLUMN start_ready and client_run_id (loses correlation receipts; only with approval).
-- Schema migration requires separately authorized scheduling/backup; NOT executed by this change.
ALTER TABLE im_aiclaw_thinking
  ADD COLUMN client_run_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
    COMMENT 'opaque case-sensitive agent run correlation, not authorization',
  ADD COLUMN start_ready TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'new-run START acknowledged only after first room push scheduling',
  ADD UNIQUE KEY uk_thinking_tenant_actor_run (tenant_id, aiclaw_uid, client_run_id);
