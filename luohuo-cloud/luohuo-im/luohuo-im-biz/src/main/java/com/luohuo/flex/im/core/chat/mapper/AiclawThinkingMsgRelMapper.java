package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.domain.entity.AiclawThinkingMsgRel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 * thinking 与回复消息关联表 Mapper 接口（复合主键，不继承 BaseMapper）
 * </p>
 *
 * @author server-dev
 */
@Repository
public interface AiclawThinkingMsgRelMapper {

	/**
	 * 插入关联记录（INSERT IGNORE 防重复）
	 */
	@Insert("INSERT IGNORE INTO im_aiclaw_thinking_msg_rel (thinking_id, msg_id, create_time) " +
			"VALUES (#{thinkingId}, #{msgId}, #{createTime})")
	int insertIgnore(AiclawThinkingMsgRel rel);

	/**
	 * 按 thinkingId 查询关联的消息 ID 列表
	 */
	@Select("SELECT msg_id FROM im_aiclaw_thinking_msg_rel WHERE thinking_id = #{thinkingId} ORDER BY create_time")
	List<Long> selectMsgIdsByThinkingId(@Param("thinkingId") Long thinkingId);

	/**
	 * #182: 解散群聊时物理删除该房间 thinking 关联的 msg_rel 记录。
	 *
	 * <p>通过 JOIN im_aiclaw_thinking 按 room_id 定位，避免该表无 room_id 字段。</p>
	 *
	 * @param roomId 群聊 room_id
	 * @return 删除行数
	 */
	@Delete("DELETE r FROM im_aiclaw_thinking_msg_rel r " +
			"INNER JOIN im_aiclaw_thinking t ON r.thinking_id = t.id " +
			"WHERE t.room_id = #{roomId}")
	int deleteByRoomId(@Param("roomId") Long roomId);
}
