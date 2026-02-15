package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * AI 节点 token 校验
 */
@Component
public class AiNodeTokenVerifier {

	@Value("${luohuo.ai-node.jwt-secret:ai-node-secret}")
	private String jwtSecret;

	public AiNodeAuthClaims verify(String token) {
		if (StrUtil.isBlank(token)) {
			throw new IllegalArgumentException("AI_AUTH_INVALID_TOKEN");
		}

		if (!JWTUtil.verify(token, jwtSecret.getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalArgumentException("AI_AUTH_INVALID_TOKEN");
		}

		JWT jwt = JWTUtil.parseToken(token);
		String nodeId = readStr(jwt, "nodeId");
		Long ownerId = readLong(jwt, "ownerId");
		String mode = readStr(jwt, "mode");
		Long aiUserId = readLong(jwt, "aiUserId");
		Long exp = readLong(jwt, "exp");
		String jti = readStr(jwt, "jti");

		if (StrUtil.hasBlank(nodeId, mode) || ownerId == null) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}

		if (exp == null) {
			throw new IllegalArgumentException("AI_AUTH_TOKEN_EXPIRED");
		}
		long expMs = exp > 100_000_000_000L ? exp : exp * 1000;
		if (System.currentTimeMillis() >= expMs) {
			throw new IllegalArgumentException("AI_AUTH_TOKEN_EXPIRED");
		}

		return AiNodeAuthClaims.builder()
				.nodeId(nodeId)
				.ownerId(ownerId)
				.mode(mode.toLowerCase())
				.aiUserId(aiUserId)
				.exp(exp)
				.jti(jti)
				.build();
	}

	private String readStr(JWT jwt, String key) {
		Object value = jwt.getPayload(key);
		if (value == null) {
			return null;
		}
		String result = String.valueOf(value);
		return StrUtil.isBlank(result) || "null".equalsIgnoreCase(result) ? null : result;
	}

	private Long readLong(JWT jwt, String key) {
		String str = readStr(jwt, key);
		if (StrUtil.isBlank(str)) {
			return null;
		}
		try {
			return Long.parseLong(str);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
	}
}
