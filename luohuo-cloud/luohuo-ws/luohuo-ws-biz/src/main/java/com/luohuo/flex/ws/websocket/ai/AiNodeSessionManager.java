package com.luohuo.flex.ws.websocket.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 节点会话管理
 */
@Slf4j
@Component
public class AiNodeSessionManager {

	private static final CloseStatus REPLACED_STATUS = new CloseStatus(4001, "connection replaced");

	private final ConcurrentHashMap<String, WebSocketSession> nodeSessionMap = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AiNodeSessionMetadata> nodeMetaMap = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> sessionNodeMap = new ConcurrentHashMap<>();

	public void register(WebSocketSession session, AiNodeSessionMetadata metadata) {
		String nodeId = metadata.getNodeId();
		String newSessionId = session.getId();

		WebSocketSession oldSession = nodeSessionMap.put(nodeId, session);
		nodeMetaMap.put(nodeId, metadata);
		sessionNodeMap.put(newSessionId, nodeId);

		if (oldSession != null && !oldSession.getId().equals(newSessionId)) {
			sessionNodeMap.remove(oldSession.getId());
			if (oldSession.isOpen()) {
				oldSession.close(REPLACED_STATUS).subscribe();
			}
			log.info("AI节点连接被覆盖: nodeId={}, oldSession={}, newSession={}", nodeId, oldSession.getId(), newSessionId);
		}
	}

	public void cleanup(String sessionId) {
		String nodeId = sessionNodeMap.remove(sessionId);
		if (nodeId == null) {
			return;
		}

		WebSocketSession currentSession = nodeSessionMap.get(nodeId);
		if (currentSession != null && sessionId.equals(currentSession.getId())) {
			nodeSessionMap.remove(nodeId);
			nodeMetaMap.remove(nodeId);
		}
	}

	public Optional<WebSocketSession> getSession(String nodeId) {
		return Optional.ofNullable(nodeSessionMap.get(nodeId));
	}

	public Optional<AiNodeSessionMetadata> getMetadata(String nodeId) {
		return Optional.ofNullable(nodeMetaMap.get(nodeId));
	}

	public Optional<AiNodeSessionMetadata> getMetadataBySession(String sessionId) {
		String nodeId = sessionNodeMap.get(sessionId);
		if (nodeId == null) {
			return Optional.empty();
		}
		return getMetadata(nodeId);
	}

	public Mono<Void> sendToNode(String nodeId, String payload) {
		WebSocketSession session = nodeSessionMap.get(nodeId);
		if (session == null || !session.isOpen()) {
			return Mono.empty();
		}
		return session.send(Mono.just(session.textMessage(payload))).then();
	}
}
