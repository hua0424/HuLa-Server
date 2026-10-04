package com.luohuo.flex.im.core.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * aichatoverview#351：思考正文 bodyETag（SHA-256，UTF-8 字节十六进制小写）。
 *
 * <p>元数据与 detail 的 ETag 必须由<b>同一正文字节</b>算出：调用方只判断相等，
 * 不做时序或权限推断。{@code null} 正文（进行中、尚未落库）无 ETag，返回
 * {@code null}；成功空正文（{@code ""}）是有效值，有确定的空串哈希。</p>
 */
public final class ThinkingBodyHash {

    private ThinkingBodyHash() {
    }

    /**
     * @param content 正文（可为 {@code null}）
     * @return {@code null} 正文返回 {@code null}，否则返回 SHA-256 十六进制小写
     */
    public static String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺失 SHA-256", e);
        }
    }
}
