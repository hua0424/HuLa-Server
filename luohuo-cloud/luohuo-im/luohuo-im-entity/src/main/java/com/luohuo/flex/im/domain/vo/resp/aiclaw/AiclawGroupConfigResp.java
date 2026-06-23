package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * aiclaw 群配置响应
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw 群配置响应")
public class AiclawGroupConfigResp {

	@Schema(description = "aiclaw 的 uid")
	private Long aiclawUid;

	@Schema(description = "群聊 room_id")
	private Long roomId;

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

	@Schema(description = "REQ-009#82: 群的可读群号（plugins 据此派生工作目录 groupkey）")
	private String account;
}
