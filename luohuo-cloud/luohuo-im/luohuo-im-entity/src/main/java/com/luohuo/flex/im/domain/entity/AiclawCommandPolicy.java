package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.luohuo.basic.base.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * aichatoverview#504 Agent 命令调用权限表（默认规则 + 群覆盖 + 指定用户名单）。
 *
 * <p>默认规则为代码常量不落库；本表只存主人的显式覆盖行。
 * 未配置（无行）= 继承默认；明确不开放 = state=closed 的行。两者语义不同，读路径必须区分。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("im_aiclaw_command_policy")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@Schema(description = "aichatoverview#504 Agent 命令调用权限表")
public class AiclawCommandPolicy extends Entity<Long> {

	/** #504: 0=默认规则（私聊及未覆盖群继承）；&gt;0=群 room_id 覆盖 */
	public static final long SCOPE_DEFAULT = 0L;

	/** #504: 允许调用 */
	public static final String STATE_OPEN = "open";
	/** #504: 不开放 */
	public static final String STATE_CLOSED = "closed";
	/** #504: 需主人审批（本票拒绝终态，主人自调=自批放行） */
	public static final String STATE_APPROVAL = "approval_required";

	/** #504: 群覆盖名单模式——全体成员 */
	public static final String MODE_ALL = "all";
	/** #504: 群覆盖名单模式——allowed_uids 指定名单 */
	public static final String MODE_LIST = "list";

	/**
	 * 逻辑删除
	 */
	@Schema(description = "逻辑删除")
	@TableField("is_del")
	@TableLogic(value = "false", delval = "true")
	@JsonIgnore
	private Boolean isDel;

	private static final long serialVersionUID = 1L;

	/**
	 * Agent 的 uid
	 */
	@TableField("aiclaw_uid")
	private Long aiclawUid;

	/**
	 * #504: 0=默认规则；&gt;0=群 room_id 覆盖
	 */
	@TableField("room_id")
	private Long roomId;

	/**
	 * #504: 能力标识（精确 id，如 skill:xxx / session.reset）
	 */
	@TableField("capability_id")
	private String capabilityId;

	/**
	 * #504: open / closed / approval_required
	 */
	@TableField("state")
	private String state;

	/**
	 * #504: 群覆盖名单模式 all / list；默认规则行忽略
	 */
	@TableField("mode")
	private String mode;

	/**
	 * #504: mode=list 时的 uid JSON 数组；其余情况忽略
	 */
	@TableField("allowed_uids")
	private String allowedUids;

	/**
	 * #504: 乐观锁版本，写时 +1
	 */
	@TableField("version")
	private Integer version;
}
