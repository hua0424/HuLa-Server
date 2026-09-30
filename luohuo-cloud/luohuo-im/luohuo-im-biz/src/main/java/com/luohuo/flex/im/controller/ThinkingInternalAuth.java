package com.luohuo.flex.im.controller;

import com.luohuo.basic.context.ContextConstants;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Internal WS/Gateway → IM identity, never supplied by an external caller. */
@Component
public class ThinkingInternalAuth {
	public static final String SERVICE_AUTH = "X-Thinking-Service-Auth";
	public static final String ACTOR = "X-Thinking-Actor-Uid";
	public static final String SERVICE_TIMEOUT = "X-Thinking-Service-Timeout";

	@Value("${THINKING_INTERNAL_SECRET}")
	private String internalSecret;

	public Long require(HttpServletRequest request) {
		return authorize(request, false);
	}

	public Long requireTimeout(HttpServletRequest request) {
		return authorize(request, true);
	}

	private Long authorize(HttpServletRequest request, boolean timeout) {
		String supplied = request.getHeader(SERVICE_AUTH);
		if (internalSecret == null || internalSecret.isBlank() || supplied == null ||
				!MessageDigest.isEqual(internalSecret.getBytes(StandardCharsets.UTF_8),
						supplied.getBytes(StandardCharsets.UTF_8))) {
			throw new BizException("未授权的内部 thinking 调用");
		}
		if (timeout != "true".equals(request.getHeader(SERVICE_TIMEOUT))) {
			throw new BizException("无效的 thinking 服务身份");
		}
		Long tenant = parsePositive(request.getHeader(ContextConstants.HEADER_TENANT_ID));
		Long actor = parsePositive(request.getHeader(ACTOR));
		if (tenant == null || actor == null) {
			throw new BizException("缺少可信 thinking 身份或租户");
		}
		ContextUtil.setTenantId(tenant);
		ContextUtil.setUid(actor);
		return actor;
	}

	private Long parsePositive(String value) {
		try {
			Long id = Long.valueOf(value);
			return id > 0 ? id : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
