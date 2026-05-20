package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luohuo.basic.base.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * aiclaw 群聊配置表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("im_aiclaw_group_config")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw 群聊配置表")
public class AiclawGroupConfig extends Entity<Long> {

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
	 * 短回复字符阈值
	 */
	@TableField("short_reply_threshold")
	private Integer shortReplyThreshold;

	/**
	 * 短回复检查最近 N 条
	 */
	@TableField("short_reply_lookback")
	private Integer shortReplyLookback;
}
