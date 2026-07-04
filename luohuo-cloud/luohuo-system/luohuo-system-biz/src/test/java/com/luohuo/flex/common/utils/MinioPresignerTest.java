package com.luohuo.flex.common.utils;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic unit tests for {@link MinioPresigner} (sign-on-access #146).
 *
 * <p>Presigning is pure local SigV4 crypto — no network — so these run offline against dummy
 * endpoint/creds/bucket. Placed in luohuo-system-biz's test tree (which already carries the
 * io.minio + junit deps), mirroring where {@code StorageUrlUtilTest} lives.</p>
 */
class MinioPresignerTest {

    private static final String OBJECT_KEY = "ai/2026/06/photo.png";
    private static final Pattern EXPIRES_PATTERN = Pattern.compile("X-Amz-Expires=(\\d+)");

    private Map<String, String> baseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("minioEndpoint", "http://127.0.0.1:9000/");
        config.put("minioAccessKey", "dummyAccessKey");
        config.put("minioSecretKey", "dummySecretKey1234567890");
        config.put("minioBucket", "test-bucket");
        return config;
    }

    private int extractExpires(String url) {
        Matcher m = EXPIRES_PATTERN.matcher(url);
        assertTrue(m.find(), "url must contain X-Amz-Expires=<n>, got: " + url);
        return Integer.parseInt(m.group(1));
    }

    // ---------------------------------------------------------------------
    // resolveSignExpiry — clamp + default
    // ---------------------------------------------------------------------

    @Test
    void requestedPositiveInRangeWins() {
        assertEquals(600, MinioPresigner.resolveSignExpiry("900", 600));
    }

    @Test
    void requestedClampedToFloorAndCeiling() {
        assertEquals(60, MinioPresigner.resolveSignExpiry(null, 10));
        assertEquals(3600, MinioPresigner.resolveSignExpiry(null, 99999999));
    }

    @Test
    void nonPositiveFallsBackToConfiguredDefault() {
        assertEquals(900, MinioPresigner.resolveSignExpiry("900", 0));
    }

    @Test
    void nonPositiveWithAbsentOrBadConfigFallsBackToBuiltinDefault() {
        assertEquals(300, MinioPresigner.resolveSignExpiry(null, 0));
        assertEquals(300, MinioPresigner.resolveSignExpiry("not-a-number", -5));
    }

    @Test
    void configuredDefaultIsAlsoClamped() {
        assertEquals(60, MinioPresigner.resolveSignExpiry("5", 0));
        assertEquals(3600, MinioPresigner.resolveSignExpiry("999999", 0));
    }

    // ---------------------------------------------------------------------
    // presignGet — pure local SigV4
    // ---------------------------------------------------------------------

    @Test
    void presignGetIsSigV4AndCarriesObjectKeyAndExpiry() {
        String url = MinioPresigner.presignGet(baseConfig(), OBJECT_KEY, 600);
        assertNotNull(url);
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), "must be SigV4, got: " + url);
        assertEquals(600, extractExpires(url));
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertTrue(decoded.contains(OBJECT_KEY), "url must contain objectKey, got: " + decoded);
    }

    @Test
    void presignGetClampsOutOfRangeExpiry() {
        assertEquals(60, extractExpires(MinioPresigner.presignGet(baseConfig(), OBJECT_KEY, 10)));
        assertEquals(3600, extractExpires(MinioPresigner.presignGet(baseConfig(), OBJECT_KEY, 99999999)));
    }
}
