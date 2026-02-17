package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * AI 节点 token 校验
 */
@Slf4j
@Component
public class AiNodeTokenVerifier {

	@Value("${luohuo.ai-node.jwt-secret:ai-node-secret}")
	private String jwtSecret;

	public AiNodeAuthClaims verify(String token) {
		if (StrUtil.isBlank(token)) {
			log.warn("[AI-LINK] event=ai_token_invalid, reason=blank_token");
			throw new IllegalArgumentException("AI_AUTH_INVALID_TOKEN");
		}

		if (!JWTUtil.verify(token, jwtSecret.getBytes(StandardCharsets.UTF_8))) {
			log.warn("[AI-LINK] event=ai_token_invalid, reason=signature_mismatch");
			throw new IllegalArgumentException("AI_AUTH_INVALID_TOKEN");
		}

		JWT jwt = JWTUtil.parseToken(token);
		String nodeId = readStr(jwt, "nodeId");
		Long ownerId = readLong(jwt, "ownerId");
		String mode = readStr(jwt, "mode");
		Long aiUserId = readLong(jwt, "aiUserId");
		Long exp = readLong(jwt, "exp");
		String jti = readStr(jwt, "jti");

		// 检查必需字段
		if (StrUtil.hasBlank(nodeId, mode)) {
			log.warn("[AI-LINK] event=ai_token_claim_mismatch, reason=missing_required_fields, nodeId={}, mode={}",
					nodeId, mode);
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
		if (ownerId == null) {
			log.warn("[AI-LINK] event=ai_token_claim_mismatch, reason=missing_ownerId");
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}

		if (exp == null) {
			log.warn("[AI-LINK] event=ai_token_exp_missing, nodeId={}", nodeId);
			throw new IllegalArgumentException("AI_AUTH_TOKEN_EXPIRED");
		}
		long expMs = exp > 100_000_000_000L ? exp : exp * 1000;
		if (System.currentTimeMillis() >= expMs) {
			log.warn("[AI-LINK] event=ai_token_expired, nodeId={}, exp={}, now={}", nodeId, expMs, System.currentTimeMillis());
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
			log.warn("[AI-LINK] event=ai_token_claim_mismatch, reason=invalid_format_key={}", key);
			throw new IllegalArgumentException("AI_AUTH_CLAIM_MISMATCH");
		}
	}
}
