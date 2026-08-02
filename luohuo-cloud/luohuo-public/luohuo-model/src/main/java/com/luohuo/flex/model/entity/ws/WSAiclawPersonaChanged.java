package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI助理人设变更推送（#188 F2：低延迟失效通知，丢失由 plugins 重连拉取 self/persona 兜底）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSAiclawPersonaChanged {

	@Schema(description = "人设发生变更的 aiclaw UID（String 避免 JS 精度丢失）")
	private String aiclawUid;
}
