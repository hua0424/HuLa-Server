package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 * aiclaw thinking 记录表 Mapper 接口
 * </p>
 *
 * @author server-dev
 */
@Repository
public interface AiclawThinkingMapper extends BaseMapper<AiclawThinking> {

	@Insert("INSERT INTO im_aiclaw_thinking (id, tenant_id, aiclaw_uid, room_id, trigger_msg_id, "
			+ "content, has_response, status, is_del) VALUES (#{id}, #{tenantId}, #{actor}, #{roomId}, "
			+ "#{triggerMsgId}, '', 0, 0, 0)")
	int insertThinking(@Param("id") Long id, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("triggerMsgId") Long triggerMsgId);

	@Insert("INSERT INTO im_aiclaw_thinking (id, tenant_id, aiclaw_uid, room_id, trigger_msg_id, "
			+ "client_run_id, content, has_response, status, is_del) VALUES (#{id}, #{tenantId}, "
			+ "#{actor}, #{roomId}, #{triggerMsgId}, #{clientRunId}, '', 0, 0, 0)")
	int insertWithRun(@Param("id") Long id, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("triggerMsgId") Long triggerMsgId, @Param("clientRunId") String clientRunId);

	@Select("SELECT * FROM im_aiclaw_thinking WHERE tenant_id = #{tenantId} "
			+ "AND aiclaw_uid = #{actor} AND client_run_id = #{clientRunId} LIMIT 1")
	AiclawThinking selectByRun(@Param("tenantId") Long tenantId, @Param("actor") Long actor,
			@Param("clientRunId") String clientRunId);

	@Update("UPDATE im_aiclaw_thinking SET start_ready = 1 WHERE id = #{id} AND tenant_id = #{tenantId} "
			+ "AND aiclaw_uid = #{actor} AND room_id = #{roomId} AND client_run_id = #{clientRunId} "
			+ "AND status = 0 AND start_ready = 0 AND is_del = 0")
	int markStartReady(@Param("id") Long id, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("clientRunId") String clientRunId);

	/**
	 * 仅更新当前租户、actor、room 的 thinking.has_response 字段。
	 * @return 影响行数
	 */
	@Update("UPDATE im_aiclaw_thinking SET has_response = 1 WHERE id = #{thinkingId} "
			+ "AND tenant_id = #{tenantId} AND aiclaw_uid = #{actor} AND room_id = #{roomId} AND is_del = 0")
	int markHasResponse(@Param("thinkingId") Long thinkingId, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId);

	@Select("SELECT * FROM im_aiclaw_thinking WHERE id = #{id} AND tenant_id = #{tenantId} "
			+ "AND aiclaw_uid = #{actor} AND room_id = #{roomId} AND is_del = 0")
	AiclawThinking selectOwned(@Param("id") Long id, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId);

	@Select("SELECT * FROM im_aiclaw_thinking WHERE id = #{id} AND tenant_id = #{tenantId} AND is_del = 0")
	AiclawThinking selectInTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

	@Update("UPDATE im_aiclaw_thinking SET content = #{content}, duration_ms = #{duration}, "
			+ "status = #{status}, error_code = #{error} WHERE id = #{id} AND tenant_id = #{tenantId} "
			+ "AND aiclaw_uid = #{actor} AND room_id = #{roomId} AND status = 0 AND is_del = 0")
	int finalizeActive(@Param("id") Long id, @Param("tenantId") Long tenantId,
			@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("content") String content, @Param("duration") Integer duration,
			@Param("status") Integer status, @Param("error") String error);

	@Select("SELECT 1 FROM im_room r WHERE r.id = #{roomId} AND r.tenant_id = #{tenantId} "
			+ "AND r.is_del = 0 AND ((r.type = 1 AND EXISTS (SELECT 1 FROM im_room_group rg "
			+ "JOIN im_group_member gm ON gm.group_id = rg.id AND gm.uid = #{actor} AND gm.is_del = 0 "
			+ "WHERE rg.room_id = r.id AND rg.tenant_id = r.tenant_id "
			+ "AND gm.tenant_id = r.tenant_id AND rg.is_del = 0)) "
			+ "OR (r.type = 2 AND EXISTS (SELECT 1 FROM im_room_friend rf "
			+ "WHERE rf.room_id = r.id AND rf.tenant_id = r.tenant_id AND rf.is_del = 0 "
			+ "AND (rf.uid1 = #{actor} OR rf.uid2 = #{actor})))) LIMIT 1")
	Integer isCurrentMember(@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("tenantId") Long tenantId);

	/** Cross-tenant service scan: every returned row carries its own tenant; the update still uses tenant-bound CAS. */
	@InterceptorIgnore(tenantLine = "true")
	@Select("SELECT t.id, t.tenant_id, t.aiclaw_uid, t.room_id, t.create_time "
			+ "FROM im_aiclaw_thinking t "
			+ "LEFT JOIN im_user u ON u.id = t.aiclaw_uid AND u.tenant_id = t.tenant_id AND u.is_del = 0 "
			+ "LEFT JOIN im_aiclaw a ON a.uid = t.aiclaw_uid AND a.tenant_id = t.tenant_id AND a.is_del = 0 "
			+ "LEFT JOIN im_room r ON r.id = t.room_id AND r.tenant_id = t.tenant_id AND r.is_del = 0 "
			+ "WHERE t.status = 0 AND t.is_del = 0 AND t.id > #{afterId} AND (t.create_time <= #{cutoff} "
			+ "OR u.id IS NULL OR u.user_type <> 4 OR u.state IS NULL OR u.state <> 0 "
			+ "OR a.id IS NULL OR a.auth_status <> 1 OR a.deactivated_at IS NOT NULL "
			+ "OR r.id IS NULL OR NOT ("
			+ "(r.type = 1 AND EXISTS (SELECT 1 FROM im_room_group rg "
			+ "JOIN im_group_member gm ON gm.group_id = rg.id AND gm.is_del = 0 "
			+ "WHERE rg.room_id = t.room_id AND rg.tenant_id = t.tenant_id "
			+ "AND gm.tenant_id = t.tenant_id AND gm.uid = t.aiclaw_uid AND rg.is_del = 0)) "
			+ "OR (r.type = 2 AND EXISTS (SELECT 1 FROM im_room_friend rf WHERE rf.room_id = t.room_id "
			+ "AND rf.tenant_id = t.tenant_id AND rf.is_del = 0 "
			+ "AND (rf.uid1 = t.aiclaw_uid OR rf.uid2 = t.aiclaw_uid))))) "
			+ "ORDER BY t.id LIMIT 100")
	List<AiclawThinking> selectCleanupCandidates(@Param("cutoff") java.time.LocalDateTime cutoff,
			@Param("afterId") Long afterId);

	/** Current room members only; filter deleted/disabled identities and cross-tenant rows at DB read time. */
	@Select("SELECT gm.uid FROM im_room r JOIN im_room_group rg ON rg.room_id = r.id "
			+ "AND rg.tenant_id = r.tenant_id AND rg.is_del = 0 "
			+ "JOIN im_group_member gm ON gm.group_id = rg.id AND gm.tenant_id = r.tenant_id AND gm.is_del = 0 "
			+ "JOIN im_user u ON u.id = gm.uid AND u.tenant_id = r.tenant_id AND u.is_del = 0 AND u.state = 0 "
			+ "WHERE r.id = #{roomId} AND r.tenant_id = #{tenantId} AND r.is_del = 0 AND r.type = 1 "
			+ "AND (u.user_type <> 4 OR EXISTS (SELECT 1 FROM im_aiclaw a WHERE a.uid = u.id "
			+ "AND a.tenant_id = r.tenant_id AND a.is_del = 0 AND a.auth_status = 1 AND a.deactivated_at IS NULL)) "
			+ "UNION SELECT u.id FROM im_room r JOIN im_room_friend rf ON rf.room_id = r.id "
			+ "AND rf.tenant_id = r.tenant_id AND rf.is_del = 0 "
			+ "JOIN im_user u ON u.id IN (rf.uid1, rf.uid2) AND u.tenant_id = r.tenant_id "
			+ "AND u.is_del = 0 AND u.state = 0 "
			+ "WHERE r.id = #{roomId} AND r.tenant_id = #{tenantId} AND r.is_del = 0 AND r.type = 2 "
			+ "AND (u.user_type <> 4 OR EXISTS (SELECT 1 FROM im_aiclaw a WHERE a.uid = u.id "
			+ "AND a.tenant_id = r.tenant_id AND a.is_del = 0 AND a.auth_status = 1 AND a.deactivated_at IS NULL))")
	List<Long> selectCurrentMemberUids(@Param("roomId") Long roomId, @Param("tenantId") Long tenantId);

	/**
	 * #182: 解散群聊时逻辑删除该房间的全部 thinking 记录（保留审计）。
	 *
	 * @param roomId 群聊 room_id
	 * @return 更新行数
	 */
	@Update("UPDATE im_aiclaw_thinking SET is_del = 1 WHERE room_id = #{roomId} AND is_del = 0")
	int logicDeleteByRoomId(@Param("roomId") Long roomId);

	/**
	 * 按触发消息 ID 批量反查指定房间的 thinking 元数据（metadata only）。
	 *
	 * <p>供客户端一次性反查一批已渲染消息各自对应的 thinking 元数据。<b>刻意不返回
	 * content 给调用方</b>（单行可达 200KB），只回元数据；全文由 {@code reviewThinking} 按需拉取。
	 * 结果按 id ASC 排序（雪花 ID 近似时间序，便于前端按消息顺序对齐）。
	 * {@code idx_trigger_msg} 索引已存在，无需 DDL。
	 * aichatoverview#351：附带查出 content 仅用于服务端计算 bodyETag（SHA-256），
	 * 不进入响应体；ETag 与 detail 由同一正文字节算出。</p>
	 *
	 * @param roomId        房间 ID
	 * @param triggerMsgIds 触发消息 ID 列表（非空，service 层限制上限 100）
	 * @return 元数据行（content 仅供 ETag 计算，不外发），按 id 升序
	 */
	@Select("<script>SELECT id, aiclaw_uid, trigger_msg_id, duration_ms, has_response, status, create_time, content "
			+ "FROM im_aiclaw_thinking WHERE room_id = #{roomId} AND is_del = 0 "
			+ "AND trigger_msg_id IN "
			+ "<foreach item='mid' collection='triggerMsgIds' open='(' separator=',' close=')'>#{mid}</foreach> "
			+ "ORDER BY id ASC</script>")
	List<AiclawThinking> selectThinkingListByTriggerMsgIds(@Param("roomId") Long roomId,
			@Param("triggerMsgIds") List<Long> triggerMsgIds);
}
