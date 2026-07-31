package com.luohuo.flex.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.hutool.crypto.SecureUtil;
import com.luohuo.flex.common.properties.IgnoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #184(a) 方案 B: TokenContextFilter 缓存缺失时 WebClient 回源 im 的单元测试。
 *
 * <p>不启动 Spring 上下文；filter 直接用显式构造器实例化，WebClient 注入带 mock
 * {@link ExchangeFunction} 的 builder（{@code WebClient.builder().exchangeFunction(xf).build()}）。
 * 用 {@link StepVerifier} 验证 reactive 链。固化四条契约：
 * <ol>
 *   <li>缓存缺失 + im 返回 200 → 重建缓存(setIfAbsent) + 继续 chain</li>
 *   <li>缓存命中 → 不调 im（WebClient 不 exchange），继续 chain（防回归 cache-hit 路径）</li>
 *   <li>im 返回 404 → body code=406（永久拒绝），chain 不继续</li>
 *   <li>im 不可达（WebClientRequestException）→ body code=503（暂时性），WARN 含 prefix</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenContextFilterImFallbackTest {

	private static final String TOKEN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
	private static final String PREFIX = "aaaaaaaa";
	private static final String CACHE_KEY = "aiclaw:token:" + PREFIX;

	/** 公共桩：tokenName=Authorization；IgnoreProperties 不忽略；opsForValue 返回 mock。 */
	private TokenContextFilter newFilter(IgnoreProperties ignoreProps, SaTokenConfig saConfig,
										  StringRedisTemplate redis, ExchangeFunction xf) {
		lenient().when(saConfig.getTokenName()).thenReturn("Authorization");
		lenient().when(ignoreProps.isIgnoreUser(anyString(), anyString())).thenReturn(false);
		WebClient.Builder wb = WebClient.builder().exchangeFunction(xf);
		return new TokenContextFilter(ignoreProps, saConfig, redis, wb);
	}

	@SuppressWarnings("unchecked")
	private ValueOperations<String, String> stubValueOps(StringRedisTemplate redis) {
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		lenient().when(redis.opsForValue()).thenReturn(valueOps);
		return valueOps;
	}

	private ServerWebExchange newExchange() {
		MockServerHttpRequest request = MockServerHttpRequest
				.post("/")
				.header("Authorization", TOKEN)
				.build();
		return MockServerWebExchange.from(request);
	}

	@Test
	@DisplayName("缓存缺失 + im 返回 200 → 重建缓存(setIfAbsent) + 继续 chain，INFO 含 uid")
	void cacheMiss_imReturnsInfo_rebuildsCacheAndContinuesChain() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false);
		ValueOperations<String, String> valueOps = stubValueOps(redis);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json")
						.body("{\"code\":200,\"data\":{\"uid\":123,\"ownerUid\":1,\"tenantId\":1,"
								+ "\"authStatus\":1,\"machineCode\":\"m1\"}}")
						.build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Logger log = (Logger) LoggerFactory.getLogger(TokenContextFilter.class);
		log.addAppender(appender);
		appender.start();

		StepVerifier.create(filter.filter(exchange, chain))
				.verifyComplete();

		appender.stop();
		verify(valueOps).setIfAbsent(eq(CACHE_KEY), anyString(), any(Duration.class));
		verify(chain).filter(any());
		boolean hasInfoLog = appender.list.stream()
				.anyMatch(e -> e.getLevel().toString().equals("INFO")
					 && e.getFormattedMessage().contains("aiclaw token cache rebuilt from im")
					 && e.getFormattedMessage().contains("uid=123"));
		assertTrue(hasInfoLog, "应打 INFO 含 'aiclaw token cache rebuilt from im' 与 uid=123");
	}

	@Test
	@DisplayName("缓存命中 → 不调 im（xf 不 exchange），继续 chain（cache-hit 路径防回归）")
	void cacheHit_imNotCalled() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.hasKey(CACHE_KEY)).thenReturn(true);
		ValueOperations<String, String> valueOps = stubValueOps(redis);
		String cachedJson = "{\"uid\":123,\"ownerUid\":1,\"tenantId\":1,\"authStatus\":1,"
				+ "\"tokenSha256\":\"" + SecureUtil.sha256(TOKEN) + "\",\"machineCode\":\"m1\"}";
		when(valueOps.get(CACHE_KEY)).thenReturn(cachedJson);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.OK).build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		StepVerifier.create(filter.filter(exchange, chain))
				.verifyComplete();

		verify(xf, never()).exchange(any(ClientRequest.class));
		verify(chain).filter(any());
	}

	@Test
	@DisplayName("缓存缺失 + im 返回 404 → body code=406（永久拒绝），chain 不继续")
	void cacheMiss_imReturns404_returns406Permanent() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
						.header("Content-Type", "application/json")
						.body("{\"code\":-10,\"msg\":\"aiclaw token无效\"}")
						.build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		StepVerifier.create(filter.filter(exchange, chain)
				.then(Mono.defer(() -> ((MockServerWebExchange) exchange).getResponse().getBodyAsString())))
				.assertNext(body -> assertTrue(body.contains("\"code\":406"),
						"404 应映射为永久拒绝 body code=406"))
				.verifyComplete();

		verify(chain, never()).filter(any());
	}

	@Test
	@DisplayName("缓存缺失 + im 不可达(WebClientRequestException) → body code=503（暂时性），WARN 含 prefix")
	void cacheMiss_imUnavailable_returns503Transient() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.error(new WebClientRequestException(new ConnectException("refused"),
						HttpMethod.POST,
						URI.create("lb://luohuo-im-server/aiclaw/anyTenant/verify-token"),
						new HttpHeaders())));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Logger log = (Logger) LoggerFactory.getLogger(TokenContextFilter.class);
		log.addAppender(appender);
		appender.start();

		StepVerifier.create(filter.filter(exchange, chain)
				.then(Mono.defer(() -> ((MockServerWebExchange) exchange).getResponse().getBodyAsString())))
				.assertNext(body -> assertTrue(body.contains("\"code\":503"),
						"不可达应映射为暂时性 body code=503"))
				.verifyComplete();

		appender.stop();
		verify(chain, never()).filter(any());
		boolean hasWarnLog = appender.list.stream()
				.anyMatch(e -> e.getLevel().toString().equals("WARN")
					 && e.getFormattedMessage().contains("upstream unavailable")
					 && e.getFormattedMessage().contains("prefix=" + PREFIX));
		assertTrue(hasWarnLog, "应打 WARN 含 'upstream unavailable' 与 prefix");
	}
}
