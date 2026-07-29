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
 * aiclaw 群聊配置表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("im_aiclaw_group_config")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
// #182 P1: 显式声明 isDel 需要 chain setter 与 SuperEntity(@Accessors(chain=true)) 的返回值协变兼容，
// 否则 @Data 生成的 void setIsDel 与父类冲突编译失败。
@Schema(description = "aiclaw 群聊配置表")
public class AiclawGroupConfig extends Entity<Long> {

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
	 * aiclaw 的 uid
	 */
	@TableField("aiclaw_uid")
	private Long aiclawUid;

	/**
	 * 群聊 room_id
	 */
	@TableField("room_id")
	private Long roomId;

	/**
	 * 频率限制（条/分钟），0=无限制
	 */
	@TableField("rate_limit_per_minute")
	private Integer rateLimitPerMinute;

	/**
	 * 是否需要 @ 触发：0=否，1=是
	 */
	@TableField("mention_required")
	private Integer mentionRequired;

	/**
	 * 每日发言上限
	 */
	@TableField("daily_limit")
	private Integer dailyLimit;

	/**
	 * 是否响应其他 aiclaw：0=否，1=是
	 */
	@TableField("respond_to_ai")
	private Integer respondToAi;

	/**
	 * REQ-009#82: 是否已批准在该群响应：0=未批准/沉默（默认），1=已批准。仅群主可设置。
	 */
	@Schema(description = "是否已批准在该群响应：0=未批准/沉默，1=已批准")
	@TableField("approved")
	private Integer approved;

	/**
	 * REQ-009#82: 工作目录（可空；NULL=plugins 自行按群号派生默认目录）。仅群主可设置。
	 */
	@Schema(description = "工作目录（可空；NULL=plugins 自行派生默认目录）")
	@TableField("workspace_dir")
	private String workspaceDir;
}
