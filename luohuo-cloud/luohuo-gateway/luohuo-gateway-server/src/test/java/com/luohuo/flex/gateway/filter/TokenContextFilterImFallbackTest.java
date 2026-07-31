package com.luohuo.flex.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.SecureUtil;
import com.luohuo.flex.common.properties.IgnoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.luohuo.basic.context.ContextConstants.JWT_KEY_SYSTEM_TYPE;
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
 * 用 {@link StepVerifier} 验证 reactive 链。固化六条契约：
 * <ol>
 *   <li><b>P0 回归闸</b>：UUID token + SaToken 有效会话 → 走 SaToken 用户路径，im 不被调用（防
 *       "正常用户 UUID SaToken 被误判 aiclaw → 406 全锁死"）。</li>
 *   <li>缓存缺失 + SaToken miss + im 返回 404 → body code=406（永久拒绝），chain 不继续。</li>
 *   <li>缓存缺失 + SaToken miss + im 返回 500 → body code=503（暂时性），WARN 含 status=500。</li>
 *   <li>缓存缺失 + SaToken miss + im 返回 200 → 重建缓存(setIfAbsent) + 继续 chain。</li>
 *   <li>缓存命中 → 不调 im（也不查 SaToken），继续 chain（cache-hit 路径防回归）。</li>
 *   <li>缓存缺失 + SaToken miss + im 不可达（WebClientRequestException）→ body code=503（暂时性），WARN 含 prefix。</li>
 * </ol>
 *
 * <p>sa-token 1.42：对未注册 token 调 {@code StpUtil.getTokenSessionByToken(token)} 抛
 * {@link SaTokenException}（code 11074）—— 这正是 aiclaw connectionToken 的真实行为。
 * 故凡走 im 回源分支的用例，都用 {@code mockStatic(StpUtil.class)} 桩成抛 11074。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenContextFilterImFallbackTest {

	private static final String TOKEN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
	private static final String PREFIX = "aaaaaaaa";
	private static final String CACHE_KEY = "aiclaw:token:" + PREFIX;
	private static final int SA_TOKEN_NOT_REGISTERED_CODE = 11074;
	private static final String SA_TOKEN_NOT_REGISTERED_MSG = "Token-Session 获取失败，token 无效";

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

	// ===== P0 回归闸：UUID token + SaToken 有效会话 → 走用户路径，im 不被调用 =====
	@Test
	@DisplayName("P0 闸：UUID token + SaToken 有效会话 → 走 SaToken 用户路径，im 不被调用，chain 继续，body 无 406")
	void uuidToken_validSaTokenSession_goesSaTokenPath_imNotCalled() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false); // aiclaw 缓存缺失 → 进 SaToken-first 分支
		stubValueOps(redis);

		// SaToken 有效会话：getLoginId + getLong(覆盖 5 个字段) + getString(systemType)
		SaSession session = mock(SaSession.class);
		lenient().when(session.getLoginId()).thenReturn(456L);
		lenient().when(session.getLong(anyString())).thenReturn(1L);
		lenient().when(session.getString(JWT_KEY_SYSTEM_TYPE)).thenReturn("im");

		// xf 应当永不被调（im 路径不触达）
		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.OK).build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		// SaToken-first：返回非空会话 → 走用户路径
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN)).thenReturn(session);
			StepVerifier.create(filter.filter(exchange, chain))
					.verifyComplete();
		}

		verify(xf, never()).exchange(any(ClientRequest.class)); // im 完全没被调
		verify(chain).filter(any()); // 正常用户路径继续 chain
		// P0 闸：正常用户路径不应写任何错误响应（response 未 commit = 没有 406/503 body）
		assertTrue(!((MockServerWebExchange) exchange).getResponse().isCommitted(),
				"P0 回归：SaToken 有效会话的正常用户绝不能被写 406/错误响应");
	}

	@Test
	@DisplayName("缓存缺失 + SaToken miss + im 返回 200 → 重建缓存(setIfAbsent) + 继续 chain，INFO 含 uid")
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

		// sa-token 1.42：未注册 token 抛 11074 → 落 im 回源
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN))
					.thenThrow(new SaTokenException(SA_TOKEN_NOT_REGISTERED_CODE, SA_TOKEN_NOT_REGISTERED_MSG));
			StepVerifier.create(filter.filter(exchange, chain))
					.verifyComplete();
		}

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

		// cache-hit 路径在 StpUtil 之前 return（handleAiclawToken），无需 mockStatic
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
	@DisplayName("SaToken miss + im 返回 404 → body code=406（永久拒绝），chain 不继续")
	void aiclawToken_saTokenMiss_imReturns404_returns406Permanent() {
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

		// sa-token 1.42：未注册 token 抛 11074 → 落 im 回源 → 404 → 406
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN))
					.thenThrow(new SaTokenException(SA_TOKEN_NOT_REGISTERED_CODE, SA_TOKEN_NOT_REGISTERED_MSG));
			StepVerifier.create(filter.filter(exchange, chain)
					.then(Mono.defer(() -> ((MockServerWebExchange) exchange).getResponse().getBodyAsString())))
					.assertNext(body -> assertTrue(body.contains("\"code\":406"),
							"404 应映射为永久拒绝 body code=406"))
					.verifyComplete();
		}

		verify(chain, never()).filter(any());
	}

	@Test
	@DisplayName("缓存缺失 + SaToken miss + im 返回 500 → body code=503（暂时性），WARN 含 status=500 与 prefix")
	void cacheMiss_saTokenMiss_imReturns500_returns503Transient() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
						.body("")
						.build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		when(chain.filter(any())).thenReturn(Mono.empty());
		ServerWebExchange exchange = newExchange();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Logger log = (Logger) LoggerFactory.getLogger(TokenContextFilter.class);
		log.addAppender(appender);
		appender.start();

		// sa-token 1.42：未注册 token 抛 11074 → 落 im 回源 → 500 → 503
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN))
					.thenThrow(new SaTokenException(SA_TOKEN_NOT_REGISTERED_CODE, SA_TOKEN_NOT_REGISTERED_MSG));
			StepVerifier.create(filter.filter(exchange, chain)
					.then(Mono.defer(() -> ((MockServerWebExchange) exchange).getResponse().getBodyAsString())))
					.assertNext(body -> assertTrue(body.contains("\"code\":503"),
							"500 应映射为暂时性 body code=503"))
					.verifyComplete();
		}

		appender.stop();
		verify(chain, never()).filter(any());
		boolean hasWarnLog = appender.list.stream()
				.anyMatch(e -> e.getLevel().toString().equals("WARN")
					 && e.getFormattedMessage().contains("non-success status=500")
					 && e.getFormattedMessage().contains("prefix=" + PREFIX));
		assertTrue(hasWarnLog, "应打 WARN 含 'non-success status=500' 与 prefix");
	}

	@Test
	@DisplayName("缓存缺失 + SaToken miss + im 不可达(WebClientRequestException) → body code=503（暂时性），WARN 含 prefix")
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

		// sa-token 1.42：未注册 token 抛 11074 → 落 im 回源 → 不可达 → 503
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN))
					.thenThrow(new SaTokenException(SA_TOKEN_NOT_REGISTERED_CODE, SA_TOKEN_NOT_REGISTERED_MSG));
			StepVerifier.create(filter.filter(exchange, chain)
					.then(Mono.defer(() -> ((MockServerWebExchange) exchange).getResponse().getBodyAsString())))
					.assertNext(body -> assertTrue(body.contains("\"code\":503"),
							"不可达应映射为暂时性 body code=503"))
					.verifyComplete();
		}

		appender.stop();
		verify(chain, never()).filter(any());
		boolean hasWarnLog = appender.list.stream()
				.anyMatch(e -> e.getLevel().toString().equals("WARN")
					 && e.getFormattedMessage().contains("upstream unavailable")
					 && e.getFormattedMessage().contains("prefix=" + PREFIX));
		assertTrue(hasWarnLog, "应打 WARN 含 'upstream unavailable' 与 prefix");
	}

	/**
	 * #184(b) Bug2: handleAiclawTokenFromIm 的 WebClient Mono chain 须 subscribeOn(boundedElastic)。
	 *
	 * <p>线上复现：@LoadBalanced WebClient 做 LB resolve 时，HuLa 框架
	 * {@code GrayscaleVersionRoundRobinLoadBalancer.getInstanceResponse} 调了 {@code Mono.block()}，
	 * 在 reactor-http-epoll 线程 → {@code IllegalStateException: block() not supported} →
	 * 被 WebFluxGlobalExceptionHandler 包成 {@code {code:-1,"系统繁忙"}}。
	 *
	 * <p>单测里 ExchangeFunction 被 mock，无真实 LB，block() 不会真实触发；但 subscribeOn 是否生效
	 * 可由下游 chain.filter 的执行线程反映。RED 暴露：若缺 {@code subscribeOn(boundedElastic)}，
	 * chain.filter 跑在 test/StepVerifier 线程（非 boundedElastic）→ 断言失败。真实 LB block() 的
	 * 端到端验证在部署 task 10 自愈实测。
	 */
	@Test
	@DisplayName("#184(b) Bug2: handleAiclawTokenFromIm 在 boundedElastic 线程执行（隔离 LB Mono.block）")
	void handleAiclawTokenFromIm_runsOnBoundedElasticThread() {
		IgnoreProperties ignoreProps = mock(IgnoreProperties.class);
		SaTokenConfig saConfig = mock(SaTokenConfig.class);
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		lenient().when(redis.hasKey(CACHE_KEY)).thenReturn(false);
		stubValueOps(redis);

		ExchangeFunction xf = mock(ExchangeFunction.class);
		when(xf.exchange(any(ClientRequest.class))).thenReturn(
				Mono.just(ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", "application/json")
						.body("{\"code\":200,\"data\":{\"uid\":123,\"ownerUid\":1,\"tenantId\":1,"
								+ "\"authStatus\":1,\"machineCode\":\"m1\"}}")
						.build()));

		TokenContextFilter filter = newFilter(ignoreProps, saConfig, redis, xf);
		WebFilterChain chain = mock(WebFilterChain.class);
		AtomicReference<String> chainThread = new AtomicReference<>();
		when(chain.filter(any())).thenAnswer(inv -> {
			chainThread.set(Thread.currentThread().getName());
			return Mono.empty();
		});
		ServerWebExchange exchange = newExchange();

		// sa-token 1.42：未注册 token 抛 11074 → 落 im 回源
		try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
			mocked.when(() -> StpUtil.getTokenSessionByToken(TOKEN))
					.thenThrow(new SaTokenException(SA_TOKEN_NOT_REGISTERED_CODE, SA_TOKEN_NOT_REGISTERED_MSG));
			StepVerifier.create(filter.filter(exchange, chain))
					.verifyComplete();
		}

		String tname = chainThread.get();
		assertTrue(tname != null && tname.contains("boundedElastic"),
				"chain.filter 应在 boundedElastic 线程执行（subscribeOn 隔离 LB block），实际线程: " + tname);
	}
}
