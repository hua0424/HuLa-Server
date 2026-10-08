package com.luohuo.flex.gateway.filter;

import cn.dev33.satoken.config.SaTokenConfig;
import com.luohuo.basic.context.ContextConstants;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.common.controller.HealthController;
import com.luohuo.flex.common.properties.IgnoreProperties;
import com.luohuo.flex.im.facade.DefResourceFacade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthEndpointTest {
    @Configuration
    @EnableWebFlux
    @Import(HealthController.class)
    static class HttpConfig {}

    @ParameterizedTest
    @ValueSource(strings = {"gateway", "ws"})
    void reactiveServicesServeAnonymousStableJsonAndRejectWrites(String service) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.application.name=luohuo-" + service + "-server");
            context.register(HttpConfig.class);
            context.refresh();
            WebTestClient.MockServerSpec<?> spec = WebTestClient.bindToApplicationContext(context);
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            DefResourceFacade permissions = mock(DefResourceFacade.class);
            String url = HealthController.PATH;
            if (service.equals("gateway")) {
                ServerProperties properties = new ServerProperties();
                properties.getServlet().setContextPath("/api");
                IgnoreProperties ignored = new IgnoreProperties();
                spec.webFilter(new ContextPathFilter(properties),
                        new TokenContextFilter(ignored, new SaTokenConfig(), redis, WebClient.builder()),
                        new AuthenticationSaInterceptor(permissions, ignored));
                url = "/api" + url;
            }
            WebTestClient client = spec.configureClient().build();
            for (int i = 0; i < 2; i++) {
                client.get().uri(url).header(ContextConstants.JWT_KEY_SYSTEM_TYPE, "10")
                        .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith("application/json")
                        .expectBody().json("{\"status\":\"UP\"}", true);
            }
            client.get().uri(url).header("satoken", "00000000-0000-0000-0000-000000000000")
                    .header(ContextConstants.JWT_KEY_SYSTEM_TYPE, "10")
                    .exchange().expectStatus().isOk().expectBody().json("{\"status\":\"UP\"}", true);
            client.post().uri(url).exchange().expectStatus().isEqualTo(405);
            client.put().uri(url).exchange().expectStatus().isEqualTo(405);
            client.delete().uri(url).exchange().expectStatus().isEqualTo(405);
            verifyNoInteractions(redis, permissions);
        } finally {
            ContextUtil.remove();
            ContextUtil.clearTenantContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/anno/health", "/oauth/anno/health", "/im/anno/health", "/system/anno/health", "/ws/anno/health"})
    void allFiveGatewayPathsBypassTokenAndPermissionDependencies(String path) {
        IgnoreProperties ignored = new IgnoreProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DefResourceFacade permissions = mock(DefResourceFacade.class);
        TokenContextFilter token = new TokenContextFilter(ignored, new SaTokenConfig(), redis, WebClient.builder());
        AuthenticationSaInterceptor auth = new AuthenticationSaInterceptor(permissions, ignored);
        ServerProperties properties = new ServerProperties();
        properties.getServlet().setContextPath("/api");
        AtomicInteger forwarded = new AtomicInteger();
        try {
            new ContextPathFilter(properties).filter(MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api" + path)
                            .header(ContextConstants.JWT_KEY_SYSTEM_TYPE, "10").build()),
                    exchange -> token.filter(exchange, authenticated -> auth.filter(authenticated, destination -> {
                        assertEquals(path, destination.getRequest().getPath().value());
                        forwarded.incrementAndGet();
                        return Mono.empty();
                    }))).block();
            assertEquals(1, forwarded.get());
            verifyNoInteractions(redis, permissions);
        } finally {
            ContextUtil.remove();
            ContextUtil.clearTenantContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/im/chat/msg", "/ws/ws", "/system/user", "/im/anno/health/details", "/base/anno/health"})
    void healthPermissionBypassIsExact(String path) {
        assertFalse(HealthController.GATEWAY_PATHS.contains(path));
        IgnoreProperties ignored = new IgnoreProperties();
        ignored.setBaseUri(java.util.Map.of());
        DefResourceFacade permissions = mock(DefResourceFacade.class);
        AuthenticationSaInterceptor auth = new AuthenticationSaInterceptor(permissions, ignored);
        AtomicInteger forwarded = new AtomicInteger();
        auth.filter(MockServerWebExchange.from(MockServerHttpRequest.get(path)
                        .header(ContextConstants.JWT_KEY_SYSTEM_TYPE, "10").build()), exchange -> {
                    forwarded.incrementAndGet();
                    return Mono.empty();
                }).block();
        assertEquals(0, forwarded.get());
    }
}
