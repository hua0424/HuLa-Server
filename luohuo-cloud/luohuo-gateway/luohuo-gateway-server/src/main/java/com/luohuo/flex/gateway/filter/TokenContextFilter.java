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
import java.time.Duration;
import java.util.Map;
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

        // --- aiclaw token 分支：先查 Redis 前缀，命中则走 aiclaw 校验；缺失则 WebClient 回源 im ---
        if (isAiclawToken(token)) {
            if (hasAiclawCache(token)) {
                return handleAiclawToken(token, request, mutate, exchange, chain);
            }
            // 缓存缺失 → 经 WebClient 回源 im internal verify-token（reactive，不阻塞 netty 事件循环）
            return handleAiclawTokenFromIm(token, request, mutate, exchange, chain);
        }

        // --- 原有 SaToken 逻辑 ---
        SaSession tokenSession = StpUtil.getTokenSessionByToken(token);
        log.info("{}", tokenSession);

        if (tokenSession != null) {
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

        return null;
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
     */
    private Mono<Void> handleAiclawToken(String token, ServerHttpRequest request,
                                          ServerHttpRequest.Builder mutate,
                                          ServerWebExchange exchange, WebFilterChain chain) {
        String prefix = token.substring(0, 8);
        String cacheKey = AICLAW_TOKEN_CACHE_PREFIX + prefix;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);

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

        // 认证成功，刷新 TTL
        stringRedisTemplate.expire(cacheKey, java.time.Duration.ofDays(7));

        return null; // 继续 filter chain
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
                .flatMap(bodyJson -> {
                    JSONObject body = JSONUtil.parseObj(bodyJson);
                    JSONObject data = body.getJSONObject("data");
                    if (data == null) {
                        return Mono.<Void>error(new UnauthorizedException(503,
                                "aiclaw verify-token upstream malformed body"));
                    }
                    Long uid = data.getLong("uid");
                    Long ownerUid = data.getLong("ownerUid");
                    Long tenantId = data.getLong("tenantId") != null ? data.getLong("tenantId") : 1L;
                    Integer authStatus = data.getInt("authStatus");
                    String machineCode = data.getStr("machineCode");

                    // 重建缓存（setIfAbsent：不覆盖并发写入；形态与 im saveTokenCache 一致）
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

                    return chain.filter(exchange.mutate().request(mutate.build()).build());
                })
                .onErrorResume(UnauthorizedException.class, e ->
                        errorResponse(exchange.getResponse(), e.getMessage(), e.getCode()))
                .onErrorResume(WebClientRequestException.class, e -> {
                    log.warn("aiclaw verify-token upstream unavailable, prefix={}", prefix);
                    return errorResponse(exchange.getResponse(), "aiclaw verify-token 不可达", 503);
                })
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

}
