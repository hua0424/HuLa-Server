package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohuo.flex.im.domain.entity.AiclawCommandPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * aichatoverview#504 Agent 命令调用权限 mapper。
 */
@Mapper
public interface AiclawCommandPolicyMapper extends BaseMapper<AiclawCommandPolicy> {

	/**
	 * #504: 乐观锁更新——只有版本号匹配才生效，调用方按影响行数判定并发冲突。
	 *
	 * @return 影响行数（0=版本失配，并发冲突）
	 */
	@Update("UPDATE im_aiclaw_command_policy SET state = #{state}, mode = #{mode}, allowed_uids = #{allowedUids},"
			+ " version = version + 1, update_time = CURRENT_TIMESTAMP"
			+ " WHERE id = #{id} AND version = #{version} AND is_del = 0")
	int updateStateCas(@Param("id") Long id, @Param("version") Integer version,
			@Param("state") String state, @Param("mode") String mode, @Param("allowedUids") String allowedUids);
}
