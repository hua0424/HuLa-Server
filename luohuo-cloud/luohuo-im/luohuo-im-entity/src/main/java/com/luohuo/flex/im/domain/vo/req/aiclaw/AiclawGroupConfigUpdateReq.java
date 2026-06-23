package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Range;

/**
 * aiclaw 群配置更新请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw 群配置更新请求")
public class AiclawGroupConfigUpdateReq {

	@NotNull
	@Schema(description = "aiclaw 的 uid")
	private Long aiclawUid;

	@NotNull
	@Schema(description = "群聊 room_id")
	private Long roomId;

	@Min(0)
	@Schema(description = "频率限制（条/分钟），0=无限制")
	private Integer rateLimitPerMinute;

	@Range(min = 0, max = 1)
	@Schema(description = "是否需要 @ 触发：0=否，1=是")
	private Integer mentionRequired;

	@Min(0)
	@Schema(description = "每日发言上限")
	private Integer dailyLimit;

	@Range(min = 0, max = 1)
	@Schema(description = "是否响应其他 aiclaw：0=否，1=是")
	private Integer respondToAi;

	@Range(min = 0, max = 1)
	@Schema(description = "REQ-009#82: 是否已批准在该群响应：0=未批准/沉默，1=已批准（仅群主可设置）")
	private Integer approved;

	@Schema(description = "REQ-009#82: 工作目录（仅群主可设置；不传=不变）")
	private String workspaceDir;
}
