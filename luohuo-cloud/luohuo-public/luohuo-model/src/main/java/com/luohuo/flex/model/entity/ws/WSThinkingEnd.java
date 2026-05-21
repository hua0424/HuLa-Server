package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * thinking 结束推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSThinkingEnd {

	@Schema(description = "thinking 记录 ID（String 避免 JS 精度丢失）")
	private String thinkingId;

	@Schema(description = "处理耗时（毫秒）")
	private Integer durationMs;

	@Schema(description = "状态: complete=正常, error=失败")
	private String status;

	@Schema(description = "错误信息，正常结束为空")
	private String error;

	@Schema(description = "房间 ID（String 避免 JS 精度丢失）")
	private String roomId;
}
