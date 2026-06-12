package com.luohuo.flex.ws.websocket;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.time.Duration;
import java.util.Map;

/**
 * netty + WebFlux 混合配置
 * 功能：配置WebSocket路由、Netty参数调优、Servlet容器兼容
 * @author 乾乾
 * @date 2025-06-08
 */
@Slf4j
@Configuration
public class NettyServerConfig {

	private  ReactiveWebSocketHandler webSocketHandler;

	public NettyServerConfig(ReactiveWebSocketHandler webSocketHandler) {
		this.webSocketHandler = webSocketHandler;
	}

	/**
	 * WebSocket 帧上限：1 MB（默认仅 64KB）。
	 *
	 * <p>背景（REQ-004 S4）：THINKING_END 单帧携带完整思考全文，>64KB 会被
	 * Reactor Netty 传输层直接拒绝，思考无法 finalize（行 status 卡在 0），
	 * 200KB 截断逻辑永远到不了。这里把握手帧上限抬到 1 MB。
	 *
	 * <p>注意：maxFramePayloadLength 是 <b>全局握手级</b>限制——WebFlux 无法按
	 * 路径区分，因此这会抬高 <b>所有</b> WS 客户端（含前端聊天 client）的帧上限。
	 * 选 1 MB 是因为 JSON 转义可能把 200KB 思考全文最多膨胀约 2 倍；DoS 风险可接受，
	 * 因为所有 WS 客户端都已在网关完成鉴权。
	 *
	 * <p>注意：此处只配置帧上限，子协议（aiclaw-v1）仍由 ReactiveWebSocketHandler
	 * 的 getSubProtocols() 在握手时注入（见 buildSpec(subProtocol)），不受影响。
	 */
	@Bean
	public WebSocketHandlerAdapter webSocketHandlerAdapter() {
		RequestUpgradeStrategy upgradeStrategy = new ReactorNettyRequestUpgradeStrategy(
				() -> WebsocketServerSpec.builder().maxFramePayloadLength(1024 * 1024));
		HandshakeWebSocketService webSocketService = new HandshakeWebSocketService(upgradeStrategy);
		return new WebSocketHandlerAdapter(webSocketService);
	}

	@Bean
	public HandlerMapping webSocketMapping() {
		// 直接注册真实处理器 ReactiveWebSocketHandler，而非 lambda 包装。
		// lambda (session -> webSocketHandler.handle(session)) 只委托 handle()，
		// 会继承 WebSocketHandler 默认空的 getSubProtocols()，导致 WebFlux
		// HandshakeWebSocketService 协商不出子协议，握手响应缺失 Sec-WebSocket-Protocol，
		// 网关下游 client（请求 aiclaw-v1）以 1002 拒绝并重连。
		// 注册真实 handler 可保留其 getSubProtocols()=[aiclaw-v1]，正常协商子协议。
		Map<String, WebSocketHandler> map = Map.of("/ws", webSocketHandler);
		SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
		handlerMapping.setUrlMap(map);
		handlerMapping.setOrder(-1); // 最高优先级
		return handlerMapping;
	}

	// 添加对 Servlet 网关的支持
	@Bean
	public ServletWebSocketHandlerAdapter servletAdapter() {
		return new ServletWebSocketHandlerAdapter();
	}

	static class ServletWebSocketHandlerAdapter extends WebSocketHandlerAdapter {
		// 空实现，仅用于兼容 Servlet API
	}

	/**
	 * netty 配置调优
	 * @return
	 */
	@Bean
	public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
		NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
		factory.addServerCustomizers(httpServer -> httpServer
				.option(ChannelOption.SO_BACKLOG, 2048) // 连接队列
				.idleTimeout(Duration.ofMinutes(10)) // 10分钟没有任何读写主动关闭链接
				.childOption(ChannelOption.SO_RCVBUF, 4 * 1024)
				.childOption(ChannelOption.SO_SNDBUF, 4 * 1024));
		return factory;
	}

}