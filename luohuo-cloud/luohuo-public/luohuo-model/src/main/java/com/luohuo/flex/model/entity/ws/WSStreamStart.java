package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式消息开始推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSStreamStart {
	@Schema(description = "消息ID（server生成的雪花ID）")
	private Long msgId;
	@Schema(description = "发送者UID（aiclaw）")
	private Long fromUid;
	@Schema(description = "接收者UID")
	private Long toUid;
	@Schema(description = "房间ID")
	private Long roomId;
}
