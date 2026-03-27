package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luohuo.basic.base.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "im_aiclaw_friend_ext", autoResultMap = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw 好友扩展（关系说明）")
public class AiclawFriendExt extends Entity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@TableField("aiclaw_uid")
	private Long aiclawUid;

	@TableField("friend_uid")
	private Long friendUid;

	@TableField("relation_desc")
	private String relationDesc;

	private Long tenantId;
}
