package com.luohuo.flex.ws.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：保证 WebFlux HandlerMapping 中注册的 "/ws" 处理器
 * 保留 {@link WebSocketHandler#getSubProtocols()}（aiclaw-v1）。
 *
 * <p>背景（aichatoverview#13）：原实现把 lambda
 * {@code session -> webSocketHandler.handle(session)} 注册进 HandlerMapping，
 * lambda 只委托 handle()，继承默认空的 getSubProtocols()，导致 WebFlux
 * HandshakeWebSocketService 协商不出子协议，握手响应缺失 Sec-WebSocket-Protocol，
 * 网关下游 client 以 1002 拒绝并重连。本测试在修复前 RED、修复后 GREEN。
 */
class NettyServerConfigTest {

	private static final String WS_PATH = "/ws";
	private static final List<String> EXPECTED_SUBPROTOCOLS = List.of("aiclaw-v1");

	@Test
	@DisplayName("webSocketMapping 注册的 /ws 处理器必须保留 getSubProtocols()=[aiclaw-v1]")
	void webSocketMappingPreservesSubProtocols() {
		// given: 真实处理器声明了 aiclaw-v1 子协议（与 ReactiveWebSocketHandler 一致）
		ReactiveWebSocketHandler realHandler = mock(ReactiveWebSocketHandler.class);
		when(realHandler.getSubProtocols()).thenReturn(EXPECTED_SUBPROTOCOLS);

		NettyServerConfig config = new NettyServerConfig(realHandler);

		// when: 构建 WebFlux 路由映射
		HandlerMapping mapping = config.webSocketMapping();

		// then: 取出 "/ws" 对应的处理器
		assertThat(mapping).isInstanceOf(SimpleUrlHandlerMapping.class);
		Map<String, ?> urlMap = ((SimpleUrlHandlerMapping) mapping).getUrlMap();
		assertThat(urlMap).containsKey(WS_PATH);

		Object handler = urlMap.get(WS_PATH);
		assertThat(handler).isInstanceOf(WebSocketHandler.class);

		// 核心断言：注册进 HandlerMapping 的处理器必须暴露 aiclaw-v1 子协议，
		// 否则握手协商不出子协议（lambda 包装会丢失它）。
		WebSocketHandler registered = (WebSocketHandler) handler;
		assertThat(registered.getSubProtocols())
				.as("HandlerMapping 中的处理器必须保留 getSubProtocols()，用于握手协商 Sec-WebSocket-Protocol")
				.containsExactlyElementsOf(EXPECTED_SUBPROTOCOLS);
	}

	@Test
	@DisplayName("webSocketHandlerAdapter 必须把 WS 帧上限抬到 1 MB（REQ-004 S4：THINKING_END 单帧全文 >64KB）")
	void webSocketHandlerAdapterRaisesMaxFramePayloadLengthTo1Mb() {
		// given
		ReactiveWebSocketHandler realHandler = mock(ReactiveWebSocketHandler.class);
		NettyServerConfig config = new NettyServerConfig(realHandler);

		// when: 构建 WebSocket 适配器
		WebSocketHandlerAdapter adapter = config.webSocketHandlerAdapter();

		// then: 适配器经 HandshakeWebSocketService 装配了配置 1MB 的 ReactorNettyRequestUpgradeStrategy
		assertThat(adapter.getWebSocketService()).isInstanceOf(HandshakeWebSocketService.class);
		HandshakeWebSocketService service = (HandshakeWebSocketService) adapter.getWebSocketService();
		assertThat(service.getUpgradeStrategy()).isInstanceOf(ReactorNettyRequestUpgradeStrategy.class);
		ReactorNettyRequestUpgradeStrategy strategy =
				(ReactorNettyRequestUpgradeStrategy) service.getUpgradeStrategy();

		// 核心断言：握手帧上限 = 1 MB（默认仅 65536）。
		assertThat(strategy.getWebsocketServerSpec().maxFramePayloadLength())
				.as("WS 帧上限必须为 1 MB，否则 THINKING_END 单帧全文 >64KB 会被传输层拒绝")
				.isEqualTo(1024 * 1024);
	}
}
