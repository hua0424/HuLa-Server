package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * <p>
 * aiclaw 群聊配置表 Mapper 接口
 * </p>
 *
 * @author server-dev
 */
@Repository
public interface AiclawGroupConfigMapper extends BaseMapper<AiclawGroupConfig> {

	/**
	 * #182: 解散群聊时物理删除该房间的全部 aiclaw 群配置。
	 *
	 * <p>im_aiclaw_group_config 的 uk_aiclaw_room 是唯一索引，且解散后配置无保留价值；
	 * 走自定义 DELETE 绕过 @TableLogic 软删，避免旧 room 残留。</p>
	 *
	 * @param roomId 群聊 room_id
	 * @return 删除行数
	 */
	@Delete("DELETE FROM im_aiclaw_group_config WHERE room_id = #{roomId}")
	int deleteByRoomId(@Param("roomId") Long roomId);
}
