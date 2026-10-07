package com.luohuo.flex.ws.websocket;

import com.luohuo.flex.ws.websocket.processor.MessageHandlerChain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * 独立消息处理器
 */
@Slf4j
@Service
public class WebSocketMessageService {
	private final MessageHandlerChain handlerChain;
	private final SessionManager sessionManager;

	public WebSocketMessageService(MessageHandlerChain handlerChain, SessionManager sessionManager) {
		this.handlerChain = handlerChain;
		this.sessionManager = sessionManager;
	}

	/**
	 * 消息处理
	 * <p>aichatoverview#345：任意消息到达即刷新在线心跳（存活证明），使残留回收能区分
	 * 健康长连接与失效连接。
	 * @param session 当前会话
	 * @param uid 当前uid
	 * @param message 消息实体
	 */
	public void handleMessage(WebSocketSession session, Long uid, WebSocketMessage message) {
		sessionManager.touchPresence(session, uid);
		handlerChain.handleMessage(session, uid, message.getPayloadAsText());
	}
}