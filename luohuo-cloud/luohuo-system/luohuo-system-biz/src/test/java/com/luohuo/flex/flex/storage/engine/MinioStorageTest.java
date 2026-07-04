package com.luohuo.flex.flex.storage.engine;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic unit tests for {@link MinioStorage}.
 *
 * <p>MinIO presigning is pure local SigV4 crypto — {@code getPresignedObjectUrl} does NOT hit the
 * network — so these tests run fully offline against a dummy endpoint / creds / bucket.</p>
 */
class MinioStorageTest {

    private static final String ENDPOINT = "http://127.0.0.1:9000/";
    private static final String ACCESS_KEY = "dummyAccessKey";
    private static final String SECRET_KEY = "dummySecretKey1234567890";
    private static final String BUCKET = "test-bucket";
    private static final String OBJECT_KEY = "ai/2026/06/photo.png";

    private static final Pattern EXPIRES_PATTERN = Pattern.compile("X-Amz-Expires=(\\d+)");

    private Map<String, String> baseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("minioEndpoint", ENDPOINT);
        config.put("minioAccessKey", ACCESS_KEY);
        config.put("minioSecretKey", SECRET_KEY);
        config.put("minioBucket", BUCKET);
        return config;
    }

    private JSONObject presign(Map<String, String> config) {
        return new MinioStorage(config).presignPut(OBJECT_KEY);
    }

    private String presignGet(Map<String, String> config, int expiry) {
        return new MinioStorage(config).presignGet(OBJECT_KEY, expiry);
    }

    private int extractExpires(String url) {
        Matcher m = EXPIRES_PATTERN.matcher(url);
        assertTrue(m.find(), "url must contain X-Amz-Expires=<n>, got: " + url);
        return Integer.parseInt(m.group(1));
    }

    // ---------------------------------------------------------------------
    // presignPut
    // ---------------------------------------------------------------------

    @Test
    void presignPutNoLongerEmitsDownloadUrl() {
        JSONObject json = presign(baseConfig());
        assertNull(json.getString("downloadUrl"),
                "sign-on-access: presignPut must no longer emit a 7-day downloadUrl");
    }

    @Test
    void presignPutStillReturnsUploadUrlAndObjectKey() {
        JSONObject json = presign(baseConfig());
        String uploadUrl = json.getString("uploadUrl");
        assertNotNull(uploadUrl, "uploadUrl must be present");
        assertTrue(uploadUrl.contains("X-Amz-Signature="),
                "uploadUrl must remain a presigned URL, got: " + uploadUrl);
        // PUT presign keeps the existing 3600s expiry.
        assertEquals(3600, extractExpires(uploadUrl), "uploadUrl expiry must stay at 3600s");
        assertEquals(OBJECT_KEY, json.getString("objectKey"), "objectKey must be echoed back");
    }

    // ---------------------------------------------------------------------
    // presignGet — sign-on-access short-expiry GET
    // ---------------------------------------------------------------------

    @Test
    void presignGetReturnsPresignedGetContainingObjectKey() {
        String url = presignGet(baseConfig(), 600);
        assertNotNull(url, "presignGet must return a url");
        assertFalse(url.isBlank(), "presignGet url must not be blank");
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"),
                "presignGet must be SigV4 presigned, got: " + url);
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertTrue(decoded.contains(OBJECT_KEY),
                "presignGet url must contain the objectKey, got: " + decoded);
    }

    @Test
    void presignGetHonoursExpiryInRange() {
        assertEquals(600, extractExpires(presignGet(baseConfig(), 600)));
    }

    @Test
    void presignGetClampsExpiryToMinWhenTooSmall() {
        assertEquals(60, extractExpires(presignGet(baseConfig(), 10)));
    }

    @Test
    void presignGetClampsExpiryToMaxWhenTooLarge() {
        assertEquals(3600, extractExpires(presignGet(baseConfig(), 99999999)));
    }

    @Test
    void presignGetFallsBackToConfiguredDefaultWhenNonPositive() {
        Map<String, String> config = baseConfig();
        config.put("minioSignExpiry", "900");
        assertEquals(900, extractExpires(presignGet(config, 0)));
    }

    @Test
    void presignGetFallsBackToBuiltinDefaultWhenConfigAbsentOrBadAndNonPositive() {
        // absent config → DEFAULT_SIGN_EXPIRY_SECONDS = 300
        assertEquals(300, extractExpires(presignGet(baseConfig(), 0)));

        Map<String, String> garbage = baseConfig();
        garbage.put("minioSignExpiry", "not-a-number");
        assertEquals(300, extractExpires(presignGet(garbage, -5)));
    }

    @Test
    void presignGetClampsConfiguredDefaultToo() {
        // configured default below the floor → clamped up to 60
        Map<String, String> config = baseConfig();
        config.put("minioSignExpiry", "5");
        assertEquals(60, extractExpires(presignGet(config, 0)));
    }
}
