package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

/**
 * aichatoverview#485 T4：群执行准入的当前事实读取。
 *
 * <p>所有语句都显式带 tenant/is_del 条件并跳过 tenant 拦截器，避免把「拦截器注入的条件 + 调用方上下文」
 * 当成一致性读证明；加锁语句固定顺序 config → membership → identity，与撤权写入方
 * （配置更新、踢人、退群/解散、身份停用）争用同一批行。</p>
 */
@Repository
@InterceptorIgnore(tenantLine = "true")
public interface GroupRunAdmissionMapper {

	@Select("SELECT id, tenant_id, type, is_del FROM im_room "
			+ "WHERE id = #{roomId} AND tenant_id = #{tenantId} LIMIT 1")
	Room selectRoom(@Param("roomId") Long roomId, @Param("tenantId") Long tenantId);

	/**
	 * 授权锚点：配置行当前读并加锁。唯一键 (aiclaw_uid, room_id) 不含 is_del，软删行仍占位，
	 * 所以这里不过滤 is_del——软删行必须被看见并按「无有效配置」拒绝，而不是当成缺行放行。
	 */
	@Select("SELECT * FROM im_aiclaw_group_config "
			+ "WHERE aiclaw_uid = #{actor} AND room_id = #{roomId} LIMIT 1 FOR UPDATE")
	AiclawGroupConfig lockConfig(@Param("actor") Long actor, @Param("roomId") Long roomId);

	@Select("SELECT * FROM im_aiclaw_group_config "
			+ "WHERE aiclaw_uid = #{actor} AND room_id = #{roomId} LIMIT 1")
	AiclawGroupConfig selectConfig(@Param("actor") Long actor, @Param("roomId") Long roomId);

	/** 成员事实加锁：与踢人/退群的 im_group_member 行更新串行。 */
	@Select("SELECT gm.id FROM im_room_group rg "
			+ "JOIN im_group_member gm ON gm.group_id = rg.id AND gm.uid = #{actor} "
			+ "AND gm.tenant_id = #{tenantId} AND gm.is_del = 0 "
			+ "WHERE rg.room_id = #{roomId} AND rg.tenant_id = #{tenantId} AND rg.is_del = 0 "
			+ "LIMIT 1 FOR UPDATE")
	Long lockMembership(@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("tenantId") Long tenantId);

	@Select("SELECT gm.id FROM im_room_group rg "
			+ "JOIN im_group_member gm ON gm.group_id = rg.id AND gm.uid = #{actor} "
			+ "AND gm.tenant_id = #{tenantId} AND gm.is_del = 0 "
			+ "WHERE rg.room_id = #{roomId} AND rg.tenant_id = #{tenantId} AND rg.is_del = 0 "
			+ "LIMIT 1")
	Long selectMembership(@Param("actor") Long actor, @Param("roomId") Long roomId,
			@Param("tenantId") Long tenantId);

	/** 身份事实加锁：与停用/恢复/删除身份的行更新串行。 */
	@Select("SELECT * FROM im_user WHERE id = #{actor} AND is_del = 0 LIMIT 1 FOR UPDATE")
	User lockUser(@Param("actor") Long actor);

	@Select("SELECT * FROM im_user WHERE id = #{actor} AND is_del = 0 LIMIT 1")
	User selectUser(@Param("actor") Long actor);

	@Select("SELECT * FROM im_aiclaw WHERE uid = #{actor} AND is_del = 0 LIMIT 1 FOR UPDATE")
	Aiclaw lockAiclaw(@Param("actor") Long actor);

	@Select("SELECT * FROM im_aiclaw WHERE uid = #{actor} AND is_del = 0 LIMIT 1")
	Aiclaw selectAiclaw(@Param("actor") Long actor);
}
