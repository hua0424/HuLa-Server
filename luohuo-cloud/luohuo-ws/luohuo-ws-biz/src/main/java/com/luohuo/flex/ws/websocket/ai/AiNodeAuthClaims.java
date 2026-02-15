package com.luohuo.flex.ws.websocket.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 节点 JWT 鉴权信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiNodeAuthClaims {
	private String nodeId;
	private Long ownerId;
	private String mode;
	private Long aiUserId;
	private Long exp;
	private String jti;
}
