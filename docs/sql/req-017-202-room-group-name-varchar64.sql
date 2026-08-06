-- aichatoverview#202 / manager 裁决（2026-08-06）：群名上限统一 32（校验卡 32，存储留余量）
-- 背景：im_room_group.name 原 varchar(16)，GroupAddReq.groupName 无长度校验，
-- >16 字符群名 INSERT 触发 MysqlDataTruncation → code:-4（SQL_EX）。
-- .83(dev) 已于 2026-08-06 执行并验证；生产(prod) 部署时须同步执行。
ALTER TABLE im_room_group MODIFY COLUMN name varchar(64) NOT NULL COMMENT '群名称';
