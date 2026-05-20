package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群配置变更推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSGroupConfigChange {

	@Schema(description = "aiclaw UID（String 避免 JS 精度丢失）")
	private String aiclawUid;

	@Schema(description = "房间 ID（String 避免 JS 精度丢失）")
	private String roomId;

	@Schema(description = "变更后的配置")
	private ConfigDTO config;

	/**
	 * 嵌套配置结构（避免扁平化字段冲突）
	 */
	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	@Schema(description = "aiclaw 群配置项")
	public static class ConfigDTO {

		@Schema(description = "频率限制（条/分钟），0=无限制")
		private Integer rateLimitPerMinute;

		@Schema(description = "是否需要 @ 触发：0=否，1=是")
		private Integer mentionRequired;

		@Schema(description = "每日发言上限")
		private Integer dailyLimit;

		@Schema(description = "是否响应其他 aiclaw：0=否，1=是")
		private Integer respondToAi;

		@Schema(description = "短回复字符阈值")
		private Integer shortReplyThreshold;

		@Schema(description = "短回复检查最近 N 条")
		private Integer shortReplyLookback;
	}
}
