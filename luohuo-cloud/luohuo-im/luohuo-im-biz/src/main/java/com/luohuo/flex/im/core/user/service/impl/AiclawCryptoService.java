package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Aiclaw 激活 token 加解密服务（AES-256-GCM）
 * 激活 token = Base64( iv(12) + ciphertext + tag(16) )
 * 明文 JSON: { "uid": Long, "connectionToken": String, "timestamp": Long }
 */
@Slf4j
@Service
public class AiclawCryptoService {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 128; // bits

	@Value("${luohuo.aiclaw.activation.secret:}")
	private String secretKey;

	/**
	 * 加密生成激活 token
	 */
	public String encryptActivationToken(Long uid, String connectionToken) {
		validateSecretKey();
		try {
			JSONObject payload = new JSONObject();
			payload.set("uid", uid);
			payload.set("connectionToken", connectionToken);
			payload.set("timestamp", System.currentTimeMillis());

			byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
			byte[] keyBytes = getKeyBytes();
			byte[] iv = new byte[GCM_IV_LENGTH];
			new SecureRandom().nextBytes(iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE,
					new SecretKeySpec(keyBytes, "AES"),
					new GCMParameterSpec(GCM_TAG_LENGTH, iv));
			byte[] ciphertext = cipher.doFinal(plaintext);

			// iv + ciphertext (includes GCM tag)
			byte[] result = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, result, 0, iv.length);
			System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

			return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
		} catch (Exception e) {
			log.error("Failed to encrypt activation token", e);
			throw new BizException("生成激活码失败");
		}
	}

	/**
	 * 解密激活 token，返回 { uid, connectionToken, timestamp }
	 */
	public JSONObject decryptActivationToken(String activationToken) {
		validateSecretKey();
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(activationToken);
			if (decoded.length < GCM_IV_LENGTH + 1) {
				throw new BizException("激活码格式无效");
			}

			byte[] iv = new byte[GCM_IV_LENGTH];
			byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
			System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
			System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE,
					new SecretKeySpec(getKeyBytes(), "AES"),
					new GCMParameterSpec(GCM_TAG_LENGTH, iv));
			byte[] plaintext = cipher.doFinal(ciphertext);

			return JSONUtil.parseObj(new String(plaintext, StandardCharsets.UTF_8));
		} catch (BizException e) {
			throw e;
		} catch (Exception e) {
			log.error("Failed to decrypt activation token", e);
			throw new BizException("激活码无效或已过期");
		}
	}

	/**
	 * 校验 timestamp 是否在 72 小时内
	 */
	public void validateTimestamp(long timestamp) {
		long hours72 = 72 * 60 * 60 * 1000L;
		if (System.currentTimeMillis() - timestamp > hours72) {
			throw new BizException("激活码已过期（72小时），请重新生成");
		}
	}

	private void validateSecretKey() {
		if (secretKey == null || secretKey.isEmpty()) {
			throw new BizException("aiclaw.activation.secret 未配置");
		}
	}

	private byte[] getKeyBytes() {
		// AES-256 需要 32 字节密钥，对配置的密钥做 SHA-256 哈希确保长度
		return cn.hutool.crypto.SecureUtil.sha256().digest(secretKey.getBytes(StandardCharsets.UTF_8));
	}
}
