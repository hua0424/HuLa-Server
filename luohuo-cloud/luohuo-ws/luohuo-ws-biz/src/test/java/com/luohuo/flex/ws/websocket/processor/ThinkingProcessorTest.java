package com.luohuo.flex.ws.websocket.processor;

import com.luohuo.basic.context.ContextConstants;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import org.springframework.http.HttpHeaders;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import com.luohuo.flex.ws.service.PushService;
import com.luohuo.flex.ws.service.AiclawRateLimitChecker;
import com.luohuo.flex.common.config.AiclawProperties;
import com.sun.net.httpserver.HttpServer;
import org.springframework.cloud.client.ServiceInstance;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import com.luohuo.flex.model.ws.WSBaseReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-004 [S4] ThinkingProcessor.supports：DELTA(21) 不再受理，仅 START(20)/END(22) 受理。
 * 纯 JUnit 单元测试，supports() 不触碰任何 @Resource 字段。
 */
class ThinkingProcessorTest {

	private final ThinkingProcessor processor = new ThinkingProcessor();

	private WSBaseReq reqOfType(int type) {
		WSBaseReq req = new WSBaseReq();
		req.setType(type);
		return req;
	}

	@Test
	@DisplayName("supports: THINKING_DELTA(21) → false（S4 已废弃）")
	void supports_thinkingDelta_false() {
		assertFalse(processor.supports(reqOfType(WSReqTypeEnum.THINKING_DELTA.getType())));
	}

	@Test
	@DisplayName("supports: THINKING_START(20) → true")
	void supports_thinkingStart_true() {
		assertTrue(processor.supports(reqOfType(WSReqTypeEnum.THINKING_START.getType())));
	}

	@Test
	@DisplayName("supports: THINKING_END(22) → true")
	void supports_thinkingEnd_true() {
		assertTrue(processor.supports(reqOfType(WSReqTypeEnum.THINKING_END.getType())));
	}

	private WebSocketSession session(HttpHeaders headers) {
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshake = mock(HandshakeInfo.class);
		when(handshake.getHeaders()).thenReturn(headers);
		when(session.getHandshakeInfo()).thenReturn(handshake);
		return session;
	}

	private HttpHeaders trustedHeaders() {
		HttpHeaders h = new HttpHeaders();
		h.add("X-Thinking-Service-Auth", "test-internal-secret");
		h.add("X-Thinking-Actor-Type", "AICLAW");
		h.add("X-Thinking-Actor-Uid", "42");
		h.add(ContextConstants.HEADER_TENANT_ID, "9");
		return h;
	}

	@Test
	void sessionIdentityRequiresGatewayProofActorAndTenant() {
		ReflectionTestUtils.setField(processor, "internalSecret", "test-internal-secret");
		HttpHeaders headers = trustedHeaders();
		assertEquals(9L, processor.trustedTenant(session(headers), 42L));
		assertNull(processor.trustedTenant(session(headers), 43L));
		headers.remove(ContextConstants.HEADER_TENANT_ID);
		assertNull(processor.trustedTenant(session(headers), 42L));
		headers = trustedHeaders();
		headers.set("X-Thinking-Service-Auth", "forged");
		assertNull(processor.trustedTenant(session(headers), 42L));
		headers = trustedHeaders();
		headers.set("X-Thinking-Actor-Type", "NORMAL");
		assertNull(processor.trustedTenant(session(headers), 42L));
	}

	@Test
	void missingThinkingIdNeverResolvesLatestRoomRecord() {
		ReflectionTestUtils.setField(processor, "internalSecret", "test-internal-secret");
		DiscoveryClient discovery = mock(DiscoveryClient.class);
		PushService push = mock(PushService.class);
		ReflectionTestUtils.setField(processor, "discoveryClient", discovery);
		ReflectionTestUtils.setField(processor, "pushService", push);
		WSBaseReq end = reqOfType(WSReqTypeEnum.THINKING_END.getType());
		end.setData("{\"roomId\":\"777\",\"status\":\"complete\"}");
		assertDoesNotThrow(() -> processor.process(session(trustedHeaders()), 42L, end));
		verifyNoInteractions(discovery, push); // Invalid END is rejected before any asynchronous IM request.
	}

	@Test
	void remoteTerminalReconcilesOldWsIndexWithoutSecondBroadcast() throws Exception {
		HttpServer im = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger errors = new AtomicInteger();
		AtomicInteger terminalReads = new AtomicInteger();
		im.createContext("/thinking/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String body;
			if (path.endsWith("/start")) body = "{\"code\":200,\"success\":true,\"data\":777}";
			else if (path.endsWith("/members")) body = "{\"code\":200,\"success\":true,\"data\":[42]}";
			else if (path.endsWith("/error")) {
				errors.incrementAndGet();
				body = "{\"code\":409,\"success\":false}"; // Another node won the CAS.
			} else if (path.endsWith("/terminal")) {
				terminalReads.incrementAndGet();
				body = "{\"code\":200,\"success\":true,\"data\":true}";
			} else body = "{\"code\":404,\"success\":false}";
			byte[] data = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, data.length);
			try (var stream = exchange.getResponseBody()) { stream.write(data); }
		});
		im.start();
		try {
			ReflectionTestUtils.setField(processor, "internalSecret", "test-internal-secret");
			DiscoveryClient discovery = mock(DiscoveryClient.class);
			ServiceInstance instance = mock(ServiceInstance.class);
			when(discovery.getInstances("luohuo-im-server")).thenReturn(List.of(instance));
			when(instance.getUri()).thenReturn(URI.create("http://127.0.0.1:" + im.getAddress().getPort()));
			ReflectionTestUtils.setField(processor, "discoveryClient", discovery);
			PushService push = mock(PushService.class);
			ReflectionTestUtils.setField(processor, "pushService", push);
			AiclawRateLimitChecker rate = mock(AiclawRateLimitChecker.class);
			when(rate.check(42L, 10L)).thenReturn(AiclawRateLimitChecker.LimitResult.ALLOWED);
			ReflectionTestUtils.setField(processor, "rateLimitChecker", rate);
			AiclawProperties props = new AiclawProperties();
			ReflectionTestUtils.setField(processor, "aiclawProperties", props);
			WSBaseReq start = reqOfType(WSReqTypeEnum.THINKING_START.getType());
			start.setData("{\"roomId\":\"10\"}");
			processor.process(session(trustedHeaders()), 42L, start);
			verify(push, timeout(2000)).sendPushMsg(any(), anyList(), eq(42L));
			props.getThinking().setTimeoutMs(-1); // Only this test's local WS timer is due now.
			processor.checkThinkingTimeout();
			processor.checkThinkingTimeout();
			assertEquals(1, errors.get());
			assertEquals(1, terminalReads.get());
			verify(push, times(1)).sendPushMsg(any(), anyList(), eq(42L));
		} finally {
			im.stop(0);
		}
	}

	@Test
	void slowStartCannotBeOvertakenByFollowingExplicitEnd() throws Exception {
		HttpServer im = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		ExecutorService handlers = Executors.newFixedThreadPool(2);
		im.setExecutor(handlers);
		CountDownLatch enteredStart = new CountDownLatch(1);
		CountDownLatch releaseStart = new CountDownLatch(1);
		AtomicInteger ends = new AtomicInteger();
		im.createContext("/thinking/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String body;
			if (path.endsWith("/start")) {
				enteredStart.countDown();
				try { releaseStart.await(3, TimeUnit.SECONDS); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				body = "{\"code\":200,\"success\":true,\"data\":777}";
			} else if (path.endsWith("/end")) {
				ends.incrementAndGet();
				body = "{\"code\":200,\"success\":true,\"data\":true}";
			} else body = "{\"code\":200,\"success\":true,\"data\":[42]}";
			byte[] data = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, data.length);
			try (var stream = exchange.getResponseBody()) { stream.write(data); }
		});
		im.start();
		try {
			ReflectionTestUtils.setField(processor, "internalSecret", "test-internal-secret");
			DiscoveryClient discovery = mock(DiscoveryClient.class);
			ServiceInstance instance = mock(ServiceInstance.class);
			when(discovery.getInstances("luohuo-im-server")).thenReturn(List.of(instance));
			when(instance.getUri()).thenReturn(URI.create("http://127.0.0.1:" + im.getAddress().getPort()));
			ReflectionTestUtils.setField(processor, "discoveryClient", discovery);
			PushService push = mock(PushService.class);
			ReflectionTestUtils.setField(processor, "pushService", push);
			AiclawRateLimitChecker rate = mock(AiclawRateLimitChecker.class);
			when(rate.check(42L, 10L)).thenReturn(AiclawRateLimitChecker.LimitResult.ALLOWED);
			ReflectionTestUtils.setField(processor, "rateLimitChecker", rate);
			WSBaseReq start = reqOfType(WSReqTypeEnum.THINKING_START.getType());
			start.setData("{\"roomId\":\"10\"}");
			WSBaseReq end = reqOfType(WSReqTypeEnum.THINKING_END.getType());
			end.setData("{\"roomId\":\"10\",\"thinkingId\":\"777\",\"status\":\"complete\"}");
			WebSocketSession session = session(trustedHeaders());
			processor.process(session, 42L, start);
			assertTrue(enteredStart.await(2, TimeUnit.SECONDS));
			processor.process(session, 42L, end);
			assertEquals(0, ends.get(), "END must remain queued behind slow START");
			releaseStart.countDown();
			verify(push, timeout(2500).times(2)).sendPushMsg(any(), anyList(), eq(42L));
			assertEquals(1, ends.get());
		} finally {
			releaseStart.countDown();
			im.stop(0);
			handlers.shutdownNow();
		}
	}
}
