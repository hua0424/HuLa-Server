-- aichatoverview#434: im_message.content varchar(1024) -> MEDIUMTEXT (limit 1024 -> 32768 chars).
-- Rationale (ADR-0012): 32768 chars x utf8mb4 worst-case 4 bytes = 128KB exceeds TEXT's 64KB
-- byte ceiling; MEDIUMTEXT (16MB) covers it plus JSON-escape bloat. Same DDL for test (hula)
-- and production (hula-prod); production execution belongs to the deploy ticket, NOT this change.
-- Observed pre-migration (test hula + prod hula-prod): `content` varchar(1024) utf8mb4/utf8mb4_unicode_ci
-- NULL DEFAULT NULL. MODIFY preserves nullability/default/comment; no index touches content.
-- Large-table note: MODIFY rewrites the table under a metadata lock; run off-peak with backup
-- and monitoring. Rollback: re-apply varchar(1024) definition (truncates rows > 1024 chars --
-- back up first; only with approval).
ALTER TABLE im_message
  MODIFY COLUMN `content` MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '消息内容';
