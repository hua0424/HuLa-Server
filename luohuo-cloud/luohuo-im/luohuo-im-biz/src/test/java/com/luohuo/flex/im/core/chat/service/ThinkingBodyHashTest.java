package com.luohuo.flex.im.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aichatoverview#351：bodyETag 纯函数测试（SHA-256，UTF-8 字节十六进制小写）。
 */
class ThinkingBodyHashTest {

    @Test
    @DisplayName("null 正文无 ETag（进行中尚未落库，无从校验）")
    void nullContentHasNoETag() {
        assertNull(ThinkingBodyHash.sha256Hex(null));
    }

    @Test
    @DisplayName("成功空正文有效：空串有确定的 SHA-256")
    void emptyContentIsValidETag() {
        // SHA-256("") 公知值；成功空正文是有效已读，不等同于失败。
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ThinkingBodyHash.sha256Hex(""));
    }

    @Test
    @DisplayName("ASCII 公知向量一致")
    void asciiKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ThinkingBodyHash.sha256Hex("abc"));
    }

    @Test
    @DisplayName("中文按 UTF-8 字节哈希：64 位小写十六进制，内容不同则不同")
    void utf8BytesHashedDeterministically() {
        String etag = ThinkingBodyHash.sha256Hex("完整的思考内容");
        assertTrue(etag.matches("[0-9a-f]{64}"), "实际=" + etag);
        assertEquals(etag, ThinkingBodyHash.sha256Hex("完整的思考内容"));
        assertNotEquals(etag, ThinkingBodyHash.sha256Hex("完整的思考内容 "));
    }
}
