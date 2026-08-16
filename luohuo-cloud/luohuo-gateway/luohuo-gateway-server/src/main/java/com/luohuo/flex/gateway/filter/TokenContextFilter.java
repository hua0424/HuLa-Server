package com.luohuo.flex.gateway.filter;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.exception.code.ResponseEnum;
import com.luohuo.flex.common.utils.IPUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextConstants;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.exception.UnauthorizedException;
import com.luohuo.basic.utils.StrPool;
import com.luohuo.flex.common.properties.IgnoreProperties;

import static com.luohuo.basic.context.ContextConstants.*;

/**
 * 过滤器
 *
 * @author 乾乾
 * @date 2019/07/31
 */
@Component
@Slf4j
public class TokenContextFilter implements WebFilter, Ordered {
    private final IgnoreProperties ignoreProperties;
    protected final SaTokenConfig saTokenConfig;
    private final StringRedisTemplate stringRedisTemplate;
    private final WebClient webClient;

    private static final String AICLAW_TOKEN_CACHE_PREFIX = "aiclaw:token:";

    /**
     * #231 本地降级缓存：仅在 Redis 基础设施瞬断（DataAccessException）时观测性留档，
     * 不作放行依据（放行必须由 Redis 权威存在性 + handleAiclawToken 完整校验决定，
     * 避免本地缓存遮蔽 im 侧停用/激活对 Redis 的改写，保证失效语义）。
     * <ul>
     *   <li>写入时机：Redis 缓存命中路径（handleAiclawToken 成功）+ im 回源重建路径（handleAiclawTokenFromIm 成功）。</li>
     *   <li>TTL：{@value #LOCAL_FALLBACK_TTL_SECONDS}s 短期，过期即失效。</li>
     * </ul>
     */
    private static final long LOCAL_FALLBACK_TTL_SECONDS = 30;
    private static final Map<String, LocalFallbackEntry> LOCAL_FALLBACK_CACHE = new ConcurrentHashMap<>();

    private record LocalFallbackEntry(String json, long expireAtMillis) {
    }

    @Value("${spring.profiles.active:dev}")
    protected String profiles;

    /**
     * #184(a) 方案 B: gateway 缓存缺失时回源 im 的 verify-token 端点。
     * 走 service discovery (lb://)，不经 gateway 自己的反向代理路由表。
     */
    @Value("${luohuo.aiclaw.verify-token-url:lb://luohuo-im-server/aiclaw/anyTenant/verify-token}")
    private String verifyTokenUrl;

    /**
     * 显式构造器：disambiguate the {@code @LoadBalanced} {@link WebClient.Builder} bean
     * (与 Spring Boot 默认 WebClientAutoConfiguration 注册的 builder 区分)。
     */
    public TokenContextFilter(IgnoreProperties ignoreProperties,
                              SaTokenConfig saTokenConfig,
                              StringRedisTemplate stringRedisTemplate,
                              @Qualifier("aiclawLbWebClientBuilder") WebClient.Builder webClientBuilder) {
        this.ignoreProperties = ignoreProperties;
        this.saTokenConfig = saTokenConfig;
        this.stringRedisTemplate = stringRedisTemplate;
        this.webClient = webClientBuilder.build();
    }

    protected boolean isDev(String token) {
        return !StrPool.PROD.equalsIgnoreCase(profiles) && (StrPool.TEST_TOKEN.equalsIgnoreCase(token) || StrPool.TEST.equalsIgnoreCase(token));
    }

    @Override
    public int getOrder() {
        return OrderedConstant.TOKEN;
    }


    /**
     * 忽略 用户token
     */
    protected boolean isIgnoreToken(ServerHttpRequest request) {
        return ignoreProperties.isIgnoreUser(request.getMethod().name(), request.getPath().toString());
    }

    /**
     * 忽略 租户编码
     */
    protected boolean isIgnoreTenant(ServerHttpRequest request) {
        return ignoreProperties.isIgnoreTenant(request.getMethod().name(), request.getPath().toString());
    }

    protected String getHeader(String headerName, ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String token = StrUtil.EMPTY;
        if (headers == null || headers.isEmpty()) {
            return token;
        }

        token = headers.getFirst(headerName);

        if (StrUtil.isNotBlank(token)) {
            return token;
        }

        return request.getQueryParams().getFirst(headerName);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpRequest.Builder mutate = request.mutate();
		mutate.header(HEADER_REQUEST_IP, IPUtils.getClientIp(request));
        ContextUtil.setGrayVersion(getHeader(ContextConstants.GRAY_VERSION, request));


        try {
            // 1 获取 应用信息
            parseApplication(request, mutate);

            Mono<Void> token = parseToken(exchange, chain, mutate);
            if (token != null) {
                return token;
            }
        } catch (UnauthorizedException e) {
            return errorResponse(response, e.getMessage(), e.getCode());
        } catch (BizException e) {
            return errorResponse(response, e.getMessage(), e.getCode());
        } catch (SaTokenException e) {
            log.error(e.getMessage(), e);
            return errorResponse(response, ResponseEnum.JWT_TOKEN_EXCEED.getMsg(), ResponseEnum.JWT_TOKEN_EXCEED.getCode());
        } catch (DataAccessException e) {
            // #231 方向 3：Redis 基础设施异常（超时/连接断，Redisson QueryTimeoutException 等）
            // 与 token 异常区分，报「服务繁忙」而非「验证token出错」，避免误导排障。
            log.error("Redis infrastructure error in TokenContextFilter: {}", e.getMessage(), e);
            return errorResponse(response, "服务繁忙，请稍后重试", R.FAIL_CODE);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return errorResponse(response, "验证token出错", R.FAIL_CODE);
        }

        ServerHttpRequest build = mutate.build();
        return chain.filter(exchange.mutate().request(build).build()).doFinally(e -> {
			ContextUtil.remove();
			ContextUtil.clearTenantContext();
		});
    }

    private Mono<Void> parseToken(ServerWebExchange exchange, WebFilterChain chain, ServerHttpRequest.Builder mutate) {
        ServerHttpRequest request = exchange.getRequest();
        // 判断接口是否需要忽略token验证
        if (isIgnoreToken(request)) {
            log.debug("当前接口：{}, 不解析用户token", request.getPath());
			return chain.filter(exchange.mutate().request(mutate.build()).build());
        }

        HttpHeaders headers = request.getHeaders();

        String tokenName = saTokenConfig.getTokenName();
        String token = headers.getFirst(tokenName);
        // 如果请求头中没有token，则尝试从URL参数中获取
        if (StrUtil.isBlank(token)) {
            token = request.getQueryParams().getFirst(tokenName);
        }
        // URL query fallback（小写 "token"，供浏览器 WS 等场景使用）
        if (StrUtil.isBlank(token)) {
            token = request.getQueryParams().getFirst("token");
        }

        // --- aiclaw token 分支：先查 Redis 前缀，命中则走 aiclaw 校验；缺失则 SaToken-first 判别 ---
        if (isAiclawToken(token)) {
            final String aiclawToken = token;
            // #231：Redis 判别（hasKey）+ aiclaw 缓存读（GET/EXPIRE）均为 Redisson 同步阻塞，
            // 挪到 boundedElastic 执行，不占 reactor-http-epoll 事件循环（3s×4 重试阻塞窗口消除）。
            return Mono.fromCallable(() -> {
                        try {
                            return hasAiclawCache(aiclawToken);
                        } catch (DataAccessException redisDown) {
                            // #231 方向 2/3：Redis 基础设施瞬断（Redisson 超时/断连）。
                            // 本地降级缓存仅观测性留档，不作放行依据（失效语义：im 侧停用/激活会
                            // 改写 Redis，本地副本可能滞后）→ 不放行，报「服务繁忙」让客户端重试，
                            // 绝不误报「验证token出错」。异常转抛 BizException，由 filter() 既有
                            // catch (BizException) 统一写响应（与其他网关基础设施失败语义一致）；
                            // 因 callable 在 boundedElastic 异步执行，BizException 经 reactor error
                            // signal 传播，由下方 onErrorResume 转成错误响应（语义与同步 catch 一致）。
                            log.error("Redis hasKey failed for aiclaw token, prefix={}, err={}",
                                    aiclawToken.substring(0, 8), redisDown.getMessage(), redisDown);
                            throw new BizException(R.FAIL_CODE, "服务繁忙，请稍后重试");
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(hasCache -> {
                        if (Boolean.TRUE.equals(hasCache)) {
                            return handleAiclawToken(aiclawToken, request, mutate, exchange, chain);
                        }
                        return proceedAfterCacheMiss(aiclawToken, request, mutate, exchange, chain);
                    })
                    // callable 在 boundedElastic 执行，其内抛的 BizException 经 reactor error signal
                    // 传播（不会被 filter() 的同步 try-catch 捕获），此处转成「服务繁忙」错误响应。
                    .onErrorResume(BizException.class, e ->
                            errorResponse(exchange.getResponse(), e.getMessage(), e.getCode()))
                    // handleAiclawTokenBlocking 在 boundedElastic 抛的 UnauthorizedException（aiclaw
                    // token 无效/停用/未激活，406）同样经 error signal 传播，转成 406 错误响应
                    // （语义与 filter() 同步 catch (UnauthorizedException) 一致，只是异步路径）。
                    .onErrorResume(UnauthorizedException.class, e ->
                            errorResponse(exchange.getResponse(), e.getMessage(), e.getCode()));
        }

        // --- 原有 SaToken 逻辑（非 UUID token）---
        SaSession tokenSession = StpUtil.getTokenSessionByToken(token);
        log.info("{}", tokenSession);

        if (tokenSession != null) {
            fillSaTokenUserHeaders(tokenSession, mutate);
        }

        return null;
    }

    /**
     * aiclaw 缓存缺失后的分发：SaToken-first 判别 → im 回源。从 parseToken 抽出供 reactive 链复用。
     */
    private Mono<Void> proceedAfterCacheMiss(String token, ServerHttpRequest request,
                                              ServerHttpRequest.Builder mutate,
                                              ServerWebExchange exchange, WebFilterChain chain) {
            // #184a P0：isAiclawToken 只是 UUID 格式判别，而 sa-token token-style:uuid 也是 UUID。
            // 缓存缺失时不能直接落 im（会把正常用户误判为 aiclaw → im 404 → 406 全锁死）。
            // SaToken-first：先查 SaToken 会话；非空 → 正常用户路径（与原逻辑完全一致）；
            // sa-token 1.42 对非已注册 token 抛 SaTokenException(11074) → 视作 aiclaw，落 im 回源。
            SaSession tokenSession;
            try {
                tokenSession = StpUtil.getTokenSessionByToken(token);
            } catch (SaTokenException e) {
                // sa-token 1.42: tokenSessionCheckLogin=true（默认）下，未注册 token 取 Token-Session 抛 11074
                tokenSession = null;
            }
            if (tokenSession != null) {
                fillSaTokenUserHeaders(tokenSession, mutate);
                return null;  // 继续原有 chain（正常用户路径，完全不变）
            }
            // 缓存缺失 → 经 WebClient 回源 im internal verify-token（reactive，不阻塞 netty 事件循环）
            return handleAiclawTokenFromIm(token, request, mutate, exchange, chain);
    }

    /**
     * 把 SaSession 用户上下文写进下游请求头（原 parseToken 内联块抽出，供 UUID/非 UUID 两条 SaToken 路径复用）。
     */
    private void fillSaTokenUserHeaders(SaSession tokenSession, ServerHttpRequest.Builder mutate) {
        Long userId = (Long) tokenSession.getLoginId();
        long topCompanyId = tokenSession.getLong(JWT_KEY_TOP_COMPANY_ID);
        long companyId = tokenSession.getLong(JWT_KEY_COMPANY_ID);
        long deptId = tokenSession.getLong(JWT_KEY_DEPT_ID);
        long uid = tokenSession.getLong(JWT_KEY_U_ID);
        long tenantId = tokenSession.getLong(HEADER_TENANT_ID);

        mutate.header(JWT_KEY_SYSTEM_TYPE, tokenSession.getString(JWT_KEY_SYSTEM_TYPE));
        mutate.header(USER_ID_HEADER, String.valueOf(userId));
        mutate.header(U_ID_HEADER, String.valueOf(uid));
        mutate.header(CURRENT_TOP_COMPANY_ID_HEADER, String.valueOf(topCompanyId));
        mutate.header(CURRENT_COMPANY_ID_HEADER, String.valueOf(companyId));
        mutate.header(CURRENT_DEPT_ID_HEADER, String.valueOf(deptId));
        mutate.header(HEADER_TENANT_ID, String.valueOf(tenantId));
    }

    private void parseApplication(ServerHttpRequest request, ServerHttpRequest.Builder mutate) {
        String applicationIdStr = getHeader(APPLICATION_ID_KEY, request);
        if (StrUtil.isNotEmpty(applicationIdStr)) {
            ContextUtil.setApplicationId(applicationIdStr);
            addHeader(mutate, APPLICATION_ID_HEADER, ContextUtil.getApplicationId());
            MDC.put(APPLICATION_ID_HEADER, applicationIdStr);
        }
    }

    private void addHeader(ServerHttpRequest.Builder mutate, String name, Object value) {
        if (value == null) {
            return;
        }
        String valueStr = value.toString();
        String valueEncode = URLUtil.encode(valueStr);
        mutate.header(name, valueEncode);
    }

    /**
     * 判断 token 是否符合 UUID 格式（快速预检，避免对非 UUID 格式的 SaToken 查 Redis）
     */
    private boolean isAiclawToken(String token) {
        return token != null && token.length() == 36 && token.charAt(8) == '-'
                && token.charAt(13) == '-' && token.charAt(18) == '-' && token.charAt(23) == '-';
    }

    /**
     * 检查 Redis 中是否存在该 token 前缀的 aiclaw 缓存（确认走 aiclaw 分支，避免误判 SaToken）
     */
    private boolean hasAiclawCache(String token) {
        String prefix = token.substring(0, 8);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AICLAW_TOKEN_CACHE_PREFIX + prefix));
    }

    /**
     * 处理 aiclaw token 校验（Redis 缓存 + SHA-256 快速验证）
     *
     * <p>#231：整个 Redis 读（GET）+ 校验 + TTL 刷新（EXPIRE）包在 {@code Mono.fromCallable}
     * 并 subscribeOn boundedElastic，不占 reactor-http-epoll 事件循环。</p>
     */
    private Mono<Void> handleAiclawToken(String token, ServerHttpRequest request,
                                          ServerHttpRequest.Builder mutate,
                                          ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.fromRunnable(() -> handleAiclawTokenBlocking(token, request, mutate))
                .subscribeOn(Schedulers.boundedElastic())
                // #231 修复：then() 后必须继续 filter chain（否则缓存命中路径 Mono 完成即结束，
                // 请求永不到达下游），并 doFinally 清 ThreadLocal 上下文（与 filter() 尾部、
                // handleAiclawTokenFromIm 尾部的清理语义一致）。
                .then(chain.filter(exchange.mutate().request(mutate.build()).build()))
                .doFinally(s -> {
                    ContextUtil.remove();
                    ContextUtil.clearTenantContext();
                });
    }

    /**
     * aiclaw token 校验的阻塞实现（Redis GET + SHA-256 + authStatus + 机器码 + TTL 刷新）。
     * 调用方须保证跑在 boundedElastic 线程。
     *
     * <p>#231 方向 3：Redis GET/EXPIRE 的基础设施异常（DataAccessException）转抛
     * BizException（服务繁忙），与 token 自身无效（UnauthorizedException）区分——
     * 前者是暂时性基础设施故障，后者是 token 真的不合法。</p>
     */
    private void handleAiclawTokenBlocking(String token, ServerHttpRequest request,
                                           ServerHttpRequest.Builder mutate) {
        String prefix = token.substring(0, 8);
        String cacheKey = AICLAW_TOKEN_CACHE_PREFIX + prefix;
        final String cachedJson;
        try {
            cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (DataAccessException redisDown) {
            log.error("Redis GET failed for aiclaw token, prefix={}, err={}", prefix, redisDown.getMessage(), redisDown);
            throw new BizException(R.FAIL_CODE, "服务繁忙，请稍后重试");
        }

        if (cachedJson == null) {
            throw new UnauthorizedException(ResponseEnum.JWT_TOKEN_EXCEED.getCode(), "aiclaw token无效");
        }

        JSONObject info = JSONUtil.parseObj(cachedJson);
        String tokenSha256 = info.getStr("tokenSha256");

        // SHA-256 快速校验
        if (tokenSha256 == null || !tokenSha256.equals(SecureUtil.sha256(token))) {
            throw new UnauthorizedException(ResponseEnum.JWT_TOKEN_EXCEED.getCode(), "aiclaw token无效");
        }

        Integer authStatus = info.getInt("authStatus");
        if (authStatus != null && authStatus == 2) {
            throw new UnauthorizedException(ResponseEnum.JWT_TOKEN_EXCEED.getCode(), "AI助理已停用");
        }
        if (authStatus != null && authStatus == 0) {
            throw new UnauthorizedException(ResponseEnum.JWT_TOKEN_EXCEED.getCode(), "AI助理未激活，请先执行 aichat activate");
        }

        Long uid = info.getLong("uid");
        Long tenantId = info.getLong("tenantId", 1L);

        mutate.header(U_ID_HEADER, String.valueOf(uid));
        mutate.header(USER_ID_HEADER, String.valueOf(uid));
        mutate.header(HEADER_TENANT_ID, String.valueOf(tenantId));

        // 检查机器码是否变更
        String storedMachineCode = info.getStr("machineCode");
        String clientId = request.getQueryParams().getFirst("clientId");
        if (StrUtil.isNotBlank(storedMachineCode) && StrUtil.isNotBlank(clientId)
                && !storedMachineCode.equals(clientId)) {
            mutate.header("X-Aiclaw-Machine-Changed", "true");
            mutate.header("X-Aiclaw-Owner-Uid", String.valueOf(info.getLong("ownerUid")));
        }

        // 认证成功，刷新 TTL（EXPIRE 基础设施异常同样转「服务繁忙」，不误报 token 出错）
        try {
            stringRedisTemplate.expire(cacheKey, java.time.Duration.ofDays(7));
        } catch (DataAccessException redisDown) {
            log.error("Redis EXPIRE failed for aiclaw token, prefix={}, err={}", prefix, redisDown.getMessage(), redisDown);
            throw new BizException(R.FAIL_CODE, "服务繁忙，请稍后重试");
        }

        // #231：认证成功 → 写入本地降级缓存（供 Redis 瞬断时兜底放行）
        seedLocalFallbackCache(prefix, cachedJson);
    }

    /**
     * 缓存缺失路径：经 WebClient (lb://) 回源 im internal verify-token 重建缓存。
     *
     * <p>全程 reactive 非阻塞，不在 netty 事件循环上 block。im 侧天然鉴权 = BCrypt.checkpw(token, hash)；
     * 失败路径（record null/bcrypt mismatch/状态异常）im 已 WARN-logged 含 prefix，本侧不再重复打。</p>
     * <ul>
     *   <li>im 返回 200 + 身份信息 → 重建 Redis 缓存（setIfAbsent，形态与 im saveTokenCache 一致）→
     *       写请求头 → 继续 filter chain。</li>
     *   <li>im 返回 404（token 无效/停用/未激活）→ 永久拒绝，body code = {@link ResponseEnum#JWT_TOKEN_EXCEED} (406)。</li>
     *   <li>im 返回其他非 2xx → 暂时性失败，body code = 503（plugins 可 transient-retry）。</li>
     *   <li>im 不可达（ConnectException/Timeout 等 WebClientRequestException）→ 暂时性失败，body code = 503。</li>
     * </ul>
     * 失败日志一律只打 token prefix（前 8 位），绝不打完整 token。
     */
    private Mono<Void> handleAiclawTokenFromIm(String token, ServerHttpRequest request,
                                                ServerHttpRequest.Builder mutate,
                                                ServerWebExchange exchange, WebFilterChain chain) {
        String prefix = token.substring(0, 8);
        String cacheKey = AICLAW_TOKEN_CACHE_PREFIX + prefix;

        return webClient.post()
                .uri(verifyTokenUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", token))
                .retrieve()
                // 404（token 无效/停用/未激活）→ 永久拒绝，body code = 406；im 已 WARN-logged(prefix)
                .onStatus(s -> s.value() == 404,
                        resp -> Mono.error(new UnauthorizedException(
                                ResponseEnum.JWT_TOKEN_EXCEED.getCode(), "aiclaw token无效")))
                // 其他 4xx/5xx → 暂时性失败，body code = 503（plugins 可 transient-retry）；retrieve 自动释放 body
                .onStatus(HttpStatusCode::isError,
                        resp -> {
                            log.warn("aiclaw verify-token upstream non-success status={}, prefix={}",
                                    resp.statusCode().value(), prefix);
                            return Mono.error(new UnauthorizedException(503, "aiclaw verify-token upstream error"));
                        })
                .bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new UnauthorizedException(503,
                        "aiclaw verify-token upstream empty body")))
                .<ServerHttpRequest.Builder>handle((bodyJson, sink) -> {
                    // P3.2：JSON 解析补 try/catch，im 返回非 JSON → 503 malformed
                    JSONObject body;
                    try {
                        body = JSONUtil.parseObj(bodyJson);
                    } catch (Exception ex) {
                        sink.error(new UnauthorizedException(503, "aiclaw verify-token malformed body"));
                        return;
                    }
                    JSONObject data = body.getJSONObject("data");
                    if (data == null) {
                        sink.error(new UnauthorizedException(503, "aiclaw verify-token upstream malformed body"));
                        return;
                    }
                    Long uid = data.getLong("uid");
                    Long ownerUid = data.getLong("ownerUid");
                    Long tenantId = data.getLong("tenantId") != null ? data.getLong("tenantId") : 1L;
                    Integer authStatus = data.getInt("authStatus");
                    String machineCode = data.getStr("machineCode");

                    // P2 tech-debt（reviewer 裁决本轮记债）：setIfAbsent 是阻塞 Redis 调用，与存量 filter 用 blocking
                    // StringRedisTemplate 一致；未来整体迁 reactive Redis 时一并改。见 PR body。
                    JSONObject cache = new JSONObject();
                    cache.set("uid", uid);
                    cache.set("ownerUid", ownerUid);
                    cache.set("tenantId", tenantId);
                    cache.set("authStatus", authStatus);
                    cache.set("tokenSha256", SecureUtil.sha256(token));
                    if (StrUtil.isNotBlank(machineCode)) {
                        cache.set("machineCode", machineCode);
                    }
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            cacheKey, cache.toString(), Duration.ofDays(7));
                    log.info("aiclaw token cache rebuilt from im, uid={}", uid);
                    // #231：im 回源重建成功 → 本地降级缓存同步置位
                    seedLocalFallbackCache(prefix, cache.toString());

                    // 写下游请求头
                    mutate.header(U_ID_HEADER, String.valueOf(uid));
                    mutate.header(USER_ID_HEADER, String.valueOf(uid));
                    mutate.header(HEADER_TENANT_ID, String.valueOf(tenantId));

                    // 机器码变更检测（与 cache-hit 路径一致）
                    String clientId = request.getQueryParams().getFirst("clientId");
                    if (StrUtil.isNotBlank(machineCode) && StrUtil.isNotBlank(clientId)
                            && !machineCode.equals(clientId)) {
                        mutate.header("X-Aiclaw-Machine-Changed", "true");
                        mutate.header("X-Aiclaw-Owner-Uid", String.valueOf(ownerUid));
                    }

                    // 把 builder 往下传 → chain.filter 移到 onErrorResume 之后运行，
                    // 下游 chain 抛的 UnauthorizedException 不会被 im-phase 的 resume 误吞为 406（P3.1 核心）
                    sink.next(mutate);
                })
                // im-phase 错误（onStatus/switchIfEmpty/handle 抛的 UnauthorizedException 406/503）落这里：
                .onErrorResume(UnauthorizedException.class, e ->
                        errorResponse(exchange.getResponse(), e.getMessage(), e.getCode())
                                .then(Mono.empty()))
                .onErrorResume(WebClientRequestException.class, e -> {
                    log.warn("aiclaw verify-token upstream unavailable, prefix={}", prefix);
                    return errorResponse(exchange.getResponse(), "aiclaw verify-token 不可达", 503)
                            .then(Mono.empty());
                })
                // chain.filter 只在 im 成功（builder 被 emit）时运行；im 错误已写响应 + empty，不进 flatMap
                .flatMap(builder -> chain.filter(exchange.mutate().request(builder.build()).build()))
                // #184(b) Bug2: @LoadBalanced WebClient LB resolve 时框架 GrayscaleVersionRoundRobinLoadBalancer
                // 调了 Mono.block()，须隔离到 boundedElastic（允许 block），否则在 reactor-http-epoll 线程
                // → IllegalStateException: block() not supported → 被全局异常处理包成 "系统繁忙"。
                // subscribeOn 订阅时生效，netty IO 回调仍走 netty 线程，不受影响。
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(s -> {
                    ContextUtil.remove();
                    ContextUtil.clearTenantContext();
                });
    }

    protected Mono<Void> errorResponse(ServerHttpResponse response, String errMsg, int errCode) {
        R tokenError = R.fail(errCode, errMsg);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setStatusCode(HttpStatus.OK);
        DataBuffer dataBuffer = response.bufferFactory().wrap(tokenError.toString().getBytes());
        return response.writeWith(Mono.just(dataBuffer));
    }

    // ===== #231 本地降级缓存：写入入口 + 测试钩子 =====

    /** 写入本地降级缓存（短期 TTL）。 */
    private static void seedLocalFallbackCache(String prefix, String json) {
        LOCAL_FALLBACK_CACHE.put(prefix, new LocalFallbackEntry(
                json, System.currentTimeMillis() + LOCAL_FALLBACK_TTL_SECONDS * 1000));
    }

    /** 测试钩子：预置本地降级缓存条目。 */
    static void testSeedLocalFallbackCache(String prefix, String json) {
        seedLocalFallbackCache(prefix, json);
    }

    /** 测试钩子：清除本地降级缓存条目。 */
    static void testEvictLocalFallbackCache(String prefix) {
        LOCAL_FALLBACK_CACHE.remove(prefix);
    }

    /** 测试钩子：本地降级缓存是否含该 prefix（未过期）。 */
    static boolean testHasLocalFallbackCache(String prefix) {
        LocalFallbackEntry e = LOCAL_FALLBACK_CACHE.get(prefix);
        return e != null && System.currentTimeMillis() <= e.expireAtMillis();
    }

}
