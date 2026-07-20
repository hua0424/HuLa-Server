package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
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

	/**
	 * 更新 thinking 记录的 has_response 字段
	 *
	 * @param thinkingId  thinking ID
	 * @param hasResponse 是否有回复（0=无，1=有）
	 * @return 影响行数
	 */
	@Update("UPDATE im_aiclaw_thinking SET has_response = #{hasResponse} WHERE id = #{thinkingId}")
	int updateHasResponse(@Param("thinkingId") Long thinkingId, @Param("hasResponse") Integer hasResponse);

	/**
	 * 反查指定 aiclaw 在指定房间内最近一条进行中（status=0）的 thinking id
	 *
	 * @param aiclawUid aiclaw uid
	 * @param roomId    房间 ID
	 * @return 最新的进行中 thinking id，无则 null
	 */
	@Select("SELECT id FROM im_aiclaw_thinking WHERE aiclaw_uid = #{aiclawUid} AND room_id = #{roomId} AND status = 0 AND is_del = 0 ORDER BY create_time DESC LIMIT 1")
	Long selectActiveThinkingId(@Param("aiclawUid") Long aiclawUid, @Param("roomId") Long roomId);

	/**
	 * 按触发消息 ID 批量反查指定房间的 thinking 元数据（metadata only）。
	 *
	 * <p>供客户端一次性反查一批已渲染消息各自对应的 thinking 元数据。<b>刻意不 select
	 * content</b>（单行可达 200KB），只回元数据；全文由 {@code reviewThinking} 按需拉取。
	 * 结果按 id ASC 排序（雪花 ID 近似时间序，便于前端按消息顺序对齐）。
	 * {@code idx_trigger_msg} 索引已存在，无需 DDL。</p>
	 *
	 * @param roomId        房间 ID
	 * @param triggerMsgIds 触发消息 ID 列表（非空，service 层限制上限 100）
	 * @return 元数据行（content 恒为 null），按 id 升序
	 */
	@Select("<script>SELECT id, aiclaw_uid, trigger_msg_id, duration_ms, has_response, status, create_time "
			+ "FROM im_aiclaw_thinking WHERE room_id = #{roomId} AND is_del = 0 "
			+ "AND trigger_msg_id IN "
			+ "<foreach item='mid' collection='triggerMsgIds' open='(' separator=',' close=')'>#{mid}</foreach> "
			+ "ORDER BY id ASC</script>")
	List<AiclawThinking> selectThinkingListByTriggerMsgIds(@Param("roomId") Long roomId,
			@Param("triggerMsgIds") List<Long> triggerMsgIds);
}
