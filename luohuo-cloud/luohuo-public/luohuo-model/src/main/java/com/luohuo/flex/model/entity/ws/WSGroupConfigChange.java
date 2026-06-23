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

	@Schema(description = "REQ-009#82: 群的可读群号（plugins 据此派生工作目录 groupkey）")
	private String account;

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

		@Schema(description = "REQ-009#82: 是否已批准在该群响应：0=未批准/沉默，1=已批准")
		private Integer approved;

		@Schema(description = "REQ-009#82: 工作目录（可空；NULL=plugins 自行派生默认目录）")
		private String workspaceDir;
	}
}
