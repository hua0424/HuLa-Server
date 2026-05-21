package com.luohuo.flex.ws.websocket;

import com.luohuo.flex.ws.ReactiveContextUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 非阻塞式+响应式消息处理入口
 * 1. 会话注册/清理 2. 心跳超时控制  3. 消息委托给MessageHandlerChain
 */
@Slf4j
@Component
public class ReactiveWebSocketHandler implements WebSocketHandler {
	// 心跳超时时间（秒）
	public static final long HEARTBEAT_TIMEOUT = 30;
	// 自定义连接超市状态码
	public static final CloseStatus SESSION_NOT_RELIABLE = new CloseStatus(4000, "会话关闭");

	@Resource
	private WebSocketMessageService messageService;
	@Resource
	private SessionManager sessionManager;

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		// 0. 检查服务状态
		if (!sessionManager.isAcceptingNewConnections()) {
			return session.close(CloseStatus.SERVICE_RESTARTED);
		}

		// 1. 处理消息用户id、指纹信息
		String clientId = extractClientId(session);
		Long uid = ReactiveContextUtil.getUid();
		if(uid == null){
			return session.close(CloseStatus.BAD_DATA);
		}

		// 2. 注册会话
		sessionManager.registerSession(session, clientId, uid);

		// 3. 处理响应式消息
		return session.receive()
				.timeout(Duration.ofSeconds(HEARTBEAT_TIMEOUT))
				.onBackpressureBuffer(1000, // 消息背压策略，缓冲1000条消息
				buffer -> log.warn("消息堆积超过阈值: {}", buffer))
//				.mergeWith(Flux.interval(Duration.ofSeconds(10)) // 双心跳机制，先关闭
//						.map(i -> session.textMessage("{\"type\":\"2\"}")))
				.doOnNext(msg -> messageService.handleMessage(session, uid, msg))
				.doFinally(signal -> {
					sessionManager.cleanupSession(session);
					if (session.isOpen()) {
						session.close(SESSION_NOT_RELIABLE).subscribe();
					}
				})
				.then();
	}

	/**
	 * CR-M1: 从 Sec-WebSocket-Protocol header 读取 clientId，兼容期 fallback 到 URL query
	 */
	private String extractClientId(WebSocketSession session) {
		// 1. 优先从 Sec-WebSocket-Protocol 读取（格式: clientId_{fingerprint}）
		List<String> protocols = session.getHandshakeInfo().getHeaders()
				.getOrDefault("Sec-WebSocket-Protocol", Collections.emptyList());
		for (String proto : protocols) {
			for (String part : proto.split(",")) {
				String trimmed = part.trim();
				if (trimmed.startsWith("clientId_")) {
					return trimmed.substring("clientId_".length());
				}
			}
		}

		// 2. 兼容期 fallback：从 URL query 读取（deprecated）
		String queryClientId = UriComponentsBuilder.fromUriString(
					session.getHandshakeInfo().getUri().toString())
				.build().getQueryParams().getFirst("clientId");
		if (queryClientId != null) {
			log.warn("[CR-M1] clientId from URL query is deprecated, use Sec-WebSocket-Protocol: session={}",
					session.getId());
		}
		return queryClientId;
	}
}
