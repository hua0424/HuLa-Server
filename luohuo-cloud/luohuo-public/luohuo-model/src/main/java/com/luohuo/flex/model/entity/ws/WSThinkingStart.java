package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * thinking 开始推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSThinkingStart {

	@Schema(description = "发送者 UID（String 避免 JS 精度丢失）")
	private String fromUid;

	@Schema(description = "房间 ID（String 避免 JS 精度丢失）")
	private String roomId;

	@Schema(description = "触发本次 thinking 的消息 ID（String 避免 JS 精度丢失）")
	private String triggerMsgId;
}
