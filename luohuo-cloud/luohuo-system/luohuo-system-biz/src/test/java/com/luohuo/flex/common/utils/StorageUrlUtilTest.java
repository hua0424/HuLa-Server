package com.luohuo.flex.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for {@link StorageUrlUtil#parseObjectKeyFromUrl(String, String)}.
 *
 * <p>Old file messages persist only a presigned GET {@code url} of shape
 * {@code {prefix}/{bucket}/{objectKey}?X-Amz-...}; the sign-on-access re-sign path recovers the
 * objectKey from it. This util is pure (no Spring / no I/O).</p>
 */
class StorageUrlUtilTest {

    private static final String BUCKET = "hula-bucket";

    @Test
    void standardPresignedUrlYieldsObjectKey() {
        String url = "http://127.0.0.1:9000/" + BUCKET
                + "/chat/1717000000000_photo.png?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=300";
        assertEquals("chat/1717000000000_photo.png", StorageUrlUtil.parseObjectKeyFromUrl(url, BUCKET));
    }

    @Test
    void nestedPathObjectKeyIsReturnedInFull() {
        String url = "https://minio.example.com/" + BUCKET + "/chat/123_a/b.png?X-Amz-Signature=abc";
        assertEquals("chat/123_a/b.png", StorageUrlUtil.parseObjectKeyFromUrl(url, BUCKET));
    }

    @Test
    void urlWithoutQueryStringStillYieldsObjectKey() {
        String url = "https://minio.example.com/" + BUCKET + "/chat/plain.txt";
        assertEquals("chat/plain.txt", StorageUrlUtil.parseObjectKeyFromUrl(url, BUCKET));
    }

    @Test
    void urlWithoutBucketSegmentReturnsNull() {
        String url = "https://minio.example.com/other-bucket/chat/photo.png?X-Amz-Expires=300";
        assertNull(StorageUrlUtil.parseObjectKeyFromUrl(url, BUCKET));
    }

    @Test
    void blankUrlReturnsNull() {
        assertNull(StorageUrlUtil.parseObjectKeyFromUrl(null, BUCKET));
        assertNull(StorageUrlUtil.parseObjectKeyFromUrl("", BUCKET));
        assertNull(StorageUrlUtil.parseObjectKeyFromUrl("   ", BUCKET));
    }
}
