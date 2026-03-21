package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式消息结束推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSStreamEnd {
	@Schema(description = "消息ID")
	private Long msgId;
	@Schema(description = "完整消息内容")
	private String fullContent;
	@Schema(description = "状态: complete=正常, error=失败")
	private String status;
}
