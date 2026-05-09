package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI助理机器码变更授权请求推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSAiclawAuthRequest {
	@Schema(description = "AI助理UID")
	private Long aiclawUid;
	@Schema(description = "AI助理名称")
	private String aiclawName;
	@Schema(description = "旧机器码")
	private String oldMachineCode;
	@Schema(description = "新机器码")
	private String newMachineCode;
}
