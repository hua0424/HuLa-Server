package com.luohuo.flex.gateway.filter;

import cn.dev33.satoken.config.SaTokenConfig;
import com.luohuo.basic.context.ContextConstants;
import com.luohuo.flex.common.properties.IgnoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenContextFilterThinkingAuthTest {
	@Test void stripsForgedIdentityEvenOnIgnoredRoute() {
		IgnoreProperties ignored = mock(IgnoreProperties.class);
		when(ignored.isIgnoreUser(any(), any())).thenReturn(true);
		TokenContextFilter filter = new TokenContextFilter(ignored, mock(SaTokenConfig.class),
				mock(StringRedisTemplate.class), WebClient.builder());
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenAnswer(invocation -> {
			ServerHttpRequest forwarded = invocation.<org.springframework.web.server.ServerWebExchange>getArgument(0).getRequest();
			assertNull(forwarded.getHeaders().getFirst(ContextConstants.U_ID_HEADER));
			assertNull(forwarded.getHeaders().getFirst(ContextConstants.HEADER_TENANT_ID));
			assertNull(forwarded.getHeaders().getFirst("X-Thinking-Service-Auth"));
			return Mono.empty();
		});
		MockServerHttpRequest request = MockServerHttpRequest.get("/api/ws/ws")
				.header(ContextConstants.U_ID_HEADER, "999")
				.header(ContextConstants.HEADER_TENANT_ID, "999")
				.header("X-Thinking-Service-Auth", "forged").build();
		filter.filter(MockServerWebExchange.from(request), chain).block();
		verify(chain).filter(any());
	}

	@Test void anonymousNonThinkingRouteRetainsRequiredTenantButNotServiceProof() {
		IgnoreProperties ignored = mock(IgnoreProperties.class);
		when(ignored.isIgnoreUser(any(), any())).thenReturn(true);
		TokenContextFilter filter = new TokenContextFilter(ignored, mock(SaTokenConfig.class),
				mock(StringRedisTemplate.class), WebClient.builder());
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenAnswer(invocation -> {
			ServerHttpRequest forwarded = invocation.<org.springframework.web.server.ServerWebExchange>getArgument(0).getRequest();
			assertEquals("9", forwarded.getHeaders().getFirst(ContextConstants.HEADER_TENANT_ID));
			assertNull(forwarded.getHeaders().getFirst("X-Thinking-Service-Auth"));
			return Mono.empty();
		});
		filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/im/anyUser/public")
				.header(ContextConstants.HEADER_TENANT_ID, "9")
				.header("X-Thinking-Service-Auth", "forged").build()), chain).block();
		verify(chain).filter(any());
	}

	@Test void blocksExternalThinkingEndpoint() {
		TokenContextFilter filter = new TokenContextFilter(mock(IgnoreProperties.class),
				mock(SaTokenConfig.class), mock(StringRedisTemplate.class), WebClient.builder());
		WebFilterChain chain = mock(WebFilterChain.class);
		filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/im/thinking/start").build()), chain).block();
		verifyNoInteractions(chain);
	}
}
