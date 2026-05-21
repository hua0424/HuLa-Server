package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * thinking 增量推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSThinkingDelta {

	@Schema(description = "thinking 记录 ID（String 避免 JS 精度丢失）")
	private String thinkingId;

	@Schema(description = "文本片段")
	private String chunk;

	@Schema(description = "序列号")
	private Integer seq;

	@Schema(description = "房间 ID（String 避免 JS 精度丢失）")
	private String roomId;
}
