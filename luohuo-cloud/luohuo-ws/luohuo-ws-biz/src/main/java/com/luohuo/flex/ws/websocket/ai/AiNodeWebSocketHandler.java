package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * AI 节点专用 WebSocket 处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiNodeWebSocketHandler implements WebSocketHandler {

	private static final CloseStatus INVALID_TOKEN = new CloseStatus(4401, "AI_AUTH_INVALID_TOKEN");
	private static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "AI_AUTH_TOKEN_EXPIRED");
	private static final CloseStatus CLAIM_MISMATCH = new CloseStatus(4403, "AI_AUTH_CLAIM_MISMATCH");
	private static final CloseStatus BAD_PROTOCOL = new CloseStatus(4408, "AI_PROTOCOL_INVALID");

	private final AiNodeTokenVerifier tokenVerifier;
	private final AiNodeSessionManager aiNodeSessionManager;
	private final AiNodeRegistryService aiNodeRegistryService;
	private final AiNodeMessageService aiNodeMessageService;

	@org.springframework.beans.factory.annotation.Value("${luohuo.node-id:ws-node}")
	private String serverInstanceId;

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		AiNodeSessionMetadata metadata;
		try {
			metadata = authenticate(session);
		} catch (IllegalArgumentException ex) {
			return closeByCode(session, ex.getMessage());
		} catch (Exception ex) {
			log.warn("AI节点握手异常: sessionId={}", session.getId(), ex);
			return session.close(BAD_PROTOCOL);
		}

		aiNodeSessionManager.register(session, metadata);
		aiNodeRegistryService.online(metadata);
		log.info("AI节点连接成功: nodeId={}, ownerId={}, mode={}, aiUserId={}, sessionId={}",
				metadata.getNodeId(), metadata.getOwnerId(), metadata.getMode(), metadata.getAiUserId(), metadata.getConnectionId());

		return session.receive()
				.timeout(Duration.ofSeconds(120))
				.doOnNext(msg -> aiNodeMessageService.handleMessage(metadata, msg.getPayloadAsText()))
				.doFinally(signal -> {
					aiNodeSessionManager.cleanup(session.getId());
					aiNodeRegistryService.offline(metadata);
				})
				.then();
	}

	private AiNodeSessionMetadata authenticate(WebSocketSession session) {
		String token = session.getHandshakeInfo().getHeaders().getFirst("x-ai-token");
		AiNodeAuthClaims claims = tokenVerifier.verify(token);
		MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri()).build().getQueryParams();

		String queryNodeId = query.getFirst("nodeId");
		Long queryOwnerId = toLong(query.getFirst("ownerId"));
		String queryMode = query.getFirst("mode");
		Long queryAiUserId = toLong(query.getFirst("aiUserId"));

		if (StrUtil.isNotBlank(queryNodeId) && !StrUtil.equals(queryNodeId, claims.getNodeId())) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
		if (queryOwnerId != null && !queryOwnerId.equals(claims.getOwnerId())) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
		if (StrUtil.isNotBlank(queryMode) && !StrUtil.equalsIgnoreCase(queryMode, claims.getMode())) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
		if (claims.getAiUserId() != null && queryAiUserId != null && !claims.getAiUserId().equals(queryAiUserId)) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}

		Long finalAiUserId = claims.getAiUserId() != null ? claims.getAiUserId() : queryAiUserId;
		long now = System.currentTimeMillis();
		return AiNodeSessionMetadata.builder()
				.nodeId(claims.getNodeId())
				.ownerId(claims.getOwnerId())
				.mode(claims.getMode())
				.aiUserId(finalAiUserId)
				.serverInstanceId(serverInstanceId)
				.connectionId(session.getId())
				.connectedAt(now)
				.lastSeen(now)
				.clientVersion(query.getFirst("clientVersion"))
				.capabilities(query.getFirst("capabilities"))
				.build();
	}

	private Long toLong(String value) {
		if (StrUtil.isBlank(value)) {
			return null;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("AI_PROTOCOL_INVALID");
		}
	}

	private Mono<Void> closeByCode(WebSocketSession session, String errorCode) {
		return switch (errorCode) {
			case "AI_AUTH_TOKEN_EXPIRED" -> session.close(TOKEN_EXPIRED);
			case "AI_AUTH_CLAIM_MISMATCH" -> session.close(CLAIM_MISMATCH);
			case "AI_PROTOCOL_INVALID" -> session.close(BAD_PROTOCOL);
			default -> session.close(INVALID_TOKEN);
		};
	}
}
