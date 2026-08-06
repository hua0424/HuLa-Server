package com.luohuo.flex.ws.websocket;

import com.luohuo.flex.ws.ReactiveContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试（aichatoverview#206）：WS 鉴权失败（uid==null）必须以专属关闭码
 * 4001 关闭连接，客户端据此区分「token 无效/过期」与普通数据错误（1007）。
 *
 * <p>背景：网关 TokenContextFilter 对失效 token 静默放行（不注入 uid 头），
 * 原实现以 {@link CloseStatus#BAD_DATA}(1007) 关闭，客户端无法区分鉴权失败，
 * 会按普通异常重连而非走刷新激活流程。修复前本测试 RED、修复后 GREEN。
 */
class ReactiveWebSocketHandlerTest {

	private ReactiveWebSocketHandler handler;
	private SessionManager sessionManager;
	private WebSocketMessageService messageService;

	@BeforeEach
	void setUp() {
		ReactiveContextUtil.remove();
		handler = new ReactiveWebSocketHandler();
		sessionManager = mock(SessionManager.class);
		messageService = mock(WebSocketMessageService.class);
		ReflectionTestUtils.setField(handler, "sessionManager", sessionManager);
		ReflectionTestUtils.setField(handler, "messageService", messageService);
		when(sessionManager.isAcceptingNewConnections()).thenReturn(true);
	}

	@AfterEach
	void tearDown() {
		ReactiveContextUtil.remove();
	}

	private WebSocketSession mockSession() {
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		when(handshakeInfo.getHeaders()).thenReturn(new HttpHeaders());
		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/ws"));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(session.getId()).thenReturn("test-session");
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
		return session;
	}

	@Test
	@DisplayName("uid==null（鉴权失败）必须以 4001 'token invalid or expired' 关闭，且不注册会话")
	void handleClosesWith4001WhenUidIsNull() {
		// given: TTL 上下文中无 uid（网关未注入 uid 头）
		WebSocketSession session = mockSession();

		// when
		handler.handle(session).block();

		// then: 以专属 4001 关闭，客户端可区分鉴权失败
		ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
		verify(session).close(captor.capture());
		CloseStatus status = captor.getValue();
		assertThat(status.getCode())
				.as("鉴权失败必须用专属关闭码 4001，而非 1007 BAD_DATA，客户端才能区分 token 失效")
				.isEqualTo(4001);
		assertThat(status.getReason()).isEqualTo("token invalid or expired");

		// 未通过鉴权的连接不得注册进会话管理
		verify(sessionManager, never()).registerSession(any(), any(), any());
	}

	@Test
	@DisplayName("uid 正常分支不受影响：注册会话，不 close")
	void handleRegistersSessionWhenUidPresent() {
		// given: TTL 上下文中有合法 uid
		Long uid = 12345L;
		ReactiveContextUtil.setUid(uid);
		WebSocketSession session = mockSession();
		when(session.receive()).thenReturn(Flux.empty());
		when(session.isOpen()).thenReturn(false);

		// when
		handler.handle(session).block();

		// then: 正常注册会话，且未发生任何关闭
		verify(sessionManager).registerSession(eq(session), isNull(), eq(uid));
		verify(session, never()).close(any(CloseStatus.class));
	}
}
