package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import java.util.Map;

/** The unique index on (tenant_id, actor_uid, request_id) serializes concurrent retries. */
@Repository
public interface MessageReceiptMapper {
    @Insert("INSERT IGNORE INTO im_message_receipt (tenant_id, actor_uid, request_id, fingerprint, create_time) " +
            "VALUES (#{tenant}, #{actor}, #{requestId}, #{fingerprint}, NOW())")
    int reserve(@Param("tenant") Long tenant, @Param("actor") Long actor,
                @Param("requestId") String requestId, @Param("fingerprint") String fingerprint);

    // Locking read observes the winning transaction after INSERT IGNORE waited for its unique-key lock.
    @Select("SELECT fingerprint, msg_id AS msgId FROM im_message_receipt WHERE tenant_id = #{tenant} " +
            "AND actor_uid = #{actor} AND request_id = #{requestId} FOR UPDATE")
    Map<String, Object> lockedReceipt(@Param("tenant") Long tenant, @Param("actor") Long actor,
                                      @Param("requestId") String requestId);

    @Update("UPDATE im_message_receipt SET msg_id = #{msgId} WHERE tenant_id = #{tenant} " +
            "AND actor_uid = #{actor} AND request_id = #{requestId} AND msg_id IS NULL")
    int commit(@Param("tenant") Long tenant, @Param("actor") Long actor,
               @Param("requestId") String requestId, @Param("msgId") Long msgId);

    @Select("SELECT COUNT(*) FROM im_message WHERE id = #{msgId} AND tenant_id = #{tenant} " +
            "AND from_uid = #{actor} AND room_id = #{roomId}")
    int validMessage(@Param("msgId") Long msgId, @Param("tenant") Long tenant,
                     @Param("actor") Long actor, @Param("roomId") Long roomId);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("DELETE FROM im_message_receipt WHERE create_time < DATE_SUB(NOW(), INTERVAL 8 DAY) " +
            "AND msg_id IS NOT NULL LIMIT 1000")
    int cleanupExpired();
}
