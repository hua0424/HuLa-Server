package com.luohuo.flex.ws.websocket.processor;

import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSStreamEnd;
import com.sun.net.httpserver.HttpServer;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.socket.CloseStatus;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import com.luohuo.flex.model.ws.WSBaseReq;
import com.luohuo.flex.ws.service.PushService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.WebSocketSession;

import static org.mockito.Mockito.*;

class StreamProcessorAuthTest {
	@Test
	void untrustedWsFrameNeverBroadcastsOrPersistsAStream() {
		StreamProcessor processor = new StreamProcessor();
		ThinkingProcessor identity = mock(ThinkingProcessor.class);
		PushService push = mock(PushService.class);
		ReflectionTestUtils.setField(processor, "thinkingProcessor", identity);
		ReflectionTestUtils.setField(processor, "pushService", push);
		WebSocketSession session = mock(WebSocketSession.class);
		WSBaseReq req = new WSBaseReq();
		req.setType(WSReqTypeEnum.STREAM_START.getType());
		processor.process(session, 101L, req);
		verify(identity).trustedTenant(session, 101L);
		verifyNoInteractions(push);
	}

	@Test
	void trustedActorCannotStreamToNonMemberOrAfterMembershipRevoked() {
		StreamProcessor processor = new StreamProcessor();
		ThinkingProcessor identity = mock(ThinkingProcessor.class);
		PushService push = mock(PushService.class);
		ReflectionTestUtils.setField(processor, "thinkingProcessor", identity);
		ReflectionTestUtils.setField(processor, "pushService", push);
		WebSocketSession session = mock(WebSocketSession.class);
		when(identity.trustedTenant(session, 101L)).thenReturn(9L);
		WSBaseReq start = new WSBaseReq();
		start.setType(WSReqTypeEnum.STREAM_START.getType());
		start.setData("{\"fromUid\":\"999\",\"roomId\":10,\"toUid\":200}");
		when(identity.authorizedMembers(10L, 101L, 9L)).thenReturn(java.util.List.of(101L));
		processor.process(session, 101L, start);
		verify(identity, timeout(2000)).authorizedMembers(10L, 101L, 9L);
		verifyNoInteractions(push);

		when(identity.authorizedMembers(10L, 101L, 9L)).thenReturn(java.util.List.of(101L, 200L), java.util.List.of(101L));
		processor.process(session, 101L, start);
		WSBaseReq delta = new WSBaseReq();
		delta.setType(WSReqTypeEnum.STREAM_DELTA.getType());
		delta.setData("{\"chunk\":\"secret\",\"seq\":1}");
		processor.process(session, 101L, delta);
		verify(identity, timeout(2000).times(3)).authorizedMembers(10L, 101L, 9L);
		verify(push, timeout(2000).times(1)).sendPushMsg(any(), eq(200L), eq(101L));
	}

	@Test
	void rejectedImWriteCannotBroadcastSuccessfulStreamEnd() throws Exception {
		HttpServer im = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicReference<String> serviceProof = new AtomicReference<>();
		im.createContext("/chat/msg", exchange -> {
			serviceProof.set(exchange.getRequestHeaders().getFirst("X-Thinking-Service-Auth"));
			byte[] rejected = "{\"code\":500,\"success\":false}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, rejected.length);
			try (var body = exchange.getResponseBody()) { body.write(rejected); }
		});
		im.start();
		try {
			StreamProcessor processor = new StreamProcessor();
			ThinkingProcessor identity = mock(ThinkingProcessor.class);
			PushService push = mock(PushService.class);
			DiscoveryClient discovery = mock(DiscoveryClient.class);
			ServiceInstance instance = mock(ServiceInstance.class);
			when(discovery.getInstances("luohuo-im-server")).thenReturn(List.of(instance));
			when(instance.getUri()).thenReturn(URI.create("http://127.0.0.1:" + im.getAddress().getPort()));
			ReflectionTestUtils.setField(processor, "thinkingProcessor", identity);
			ReflectionTestUtils.setField(processor, "pushService", push);
			ReflectionTestUtils.setField(processor, "discoveryClient", discovery);
			ReflectionTestUtils.setField(processor, "internalSecret", "test-only-secret");
			WebSocketSession session = mock(WebSocketSession.class);
			when(identity.trustedTenant(session, 101L)).thenReturn(9L);
			when(identity.authorizedMembers(10L, 101L, 9L)).thenReturn(List.of(101L, 200L));
			WSBaseReq start = new WSBaseReq();
			start.setType(WSReqTypeEnum.STREAM_START.getType());
			start.setData("{\"roomId\":10,\"toUid\":200}");
			WSBaseReq end = new WSBaseReq();
			end.setType(WSReqTypeEnum.STREAM_END.getType());
			end.setData("{\"status\":\"complete\",\"fullContent\":\"hello\"}");
			processor.process(session, 101L, start);
			processor.process(session, 101L, end);
			@SuppressWarnings("rawtypes") ArgumentCaptor<WsBaseResp> notifications = ArgumentCaptor.forClass(WsBaseResp.class);
			verify(push, timeout(5000).times(2)).sendPushMsg(notifications.capture(), eq(200L), eq(101L));
			assertEquals("test-only-secret", serviceProof.get());
			assertEquals("streamStart", notifications.getAllValues().get(0).getType());
			assertEquals("streamEnd", notifications.getAllValues().get(1).getType());
			assertEquals("error", ((WSStreamEnd) notifications.getAllValues().get(1).getData()).getStatus());
		} finally {
			im.stop(0);
		}
	}
}
