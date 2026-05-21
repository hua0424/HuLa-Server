package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

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
}
