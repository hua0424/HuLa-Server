package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式消息片段推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSStreamDelta {
	@Schema(description = "消息ID")
	private Long msgId;
	@Schema(description = "文本片段")
	private String chunk;
	@Schema(description = "序列号")
	private Integer seq;
}
