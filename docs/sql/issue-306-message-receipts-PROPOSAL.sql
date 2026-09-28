-- PROPOSAL ONLY for shared hula (issue #306); executed only in a disposable task-isolated MySQL5.7 rehearsal.
-- Observed live hula MySQL 5.7 (read-only): im_message InnoDB PK(id), tenant_id BIGINT
-- DEFAULT 1, room_id/from_uid/content/type/extra JSON; no request uniqueness.
-- secure_invoke_record InnoDB holds pending MQ calls. Verify target schema/database,
-- grants, backup, replica lag and deployment order before applying in a maintenance window.
-- Install before deploying code that advertises requestId-v1 (otherwise new requests fail closed).
CREATE TABLE im_message_receipt (
    tenant_id BIGINT NOT NULL,
    actor_uid BIGINT NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    msg_id BIGINT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (tenant_id, actor_uid, request_id),
    KEY idx_receipt_created (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='requestId idempotent message receipts';
-- MySQL 5.7 InnoDB unique key locks serialize concurrent INSERT IGNORE; same transaction
-- inserts im_message, fills msg_id and SecureInvoke record before commit. An empty msg_id
-- must never commit: treat it as unknown; investigate manually, never auto-reuse the key.
-- Application cleanup deletes committed receipts older than 8 days in <=1000-row batches
-- every minute. Within 7 days the key is protected; after 7 days clients MUST NOT
-- automatically retry an old requestId. Once cleanup runs, reuse can send a NEW message.
-- Rolling back code while table exists is safe; DO NOT drop table during the protection
-- window. No foreign key: old messages may be purged independently, rechecks reject them.
-- Envelope: committed -> R.success(ChatMessageResp.message.id); conflict -> R.code 43061
-- (rejected); receipt/response unknown -> 43062 (unknown/pending). Other auth/validation
-- rejection uses existing error codes. Probe GET /chat/msg/receipt-capability before retries.
-- Message MQ publication uses existing secure_invoke_record: 200 attempts (64-minute
-- backoff ceiling, >8 days), then FAIL with the saved invocation for diagnosed,
-- operator-approved replay; permanent failures must be monitored. The IM consumer
-- records each routed node's second-hop intent in this same existing table before
-- ACKing the first MQ message. MQ/WS may redeliver; clients dedupe by message.id.
-- No broker enqueue or WS write proves a client's ACK or live end-to-end acceptance.
