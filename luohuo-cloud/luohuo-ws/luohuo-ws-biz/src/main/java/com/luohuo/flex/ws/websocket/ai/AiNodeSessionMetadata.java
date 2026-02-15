package com.luohuo.flex.ws.websocket.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 节点会话元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiNodeSessionMetadata implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	private String nodeId;
	private Long ownerId;
	private String mode;
	private Long aiUserId;

	private String serverInstanceId;
	private String connectionId;
	private Long connectedAt;
	private Long lastSeen;

	private String clientVersion;
	private String capabilities;
}
