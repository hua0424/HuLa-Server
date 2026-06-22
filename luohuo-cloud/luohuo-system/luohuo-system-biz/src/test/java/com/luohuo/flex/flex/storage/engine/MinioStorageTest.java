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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic unit tests for {@link MinioStorage#presignPut(String)}.
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

    private int extractExpires(String url) {
        Matcher m = EXPIRES_PATTERN.matcher(url);
        assertTrue(m.find(), "downloadUrl must contain X-Amz-Expires=<n>, got: " + url);
        return Integer.parseInt(m.group(1));
    }

    @Test
    void downloadUrlIsPresignedGet() {
        JSONObject json = presign(baseConfig());
        String downloadUrl = json.getString("downloadUrl");
        assertNotNull(downloadUrl, "downloadUrl must be present");
        // The whole point of REQ-007: downloadUrl must be a presigned GET, not a raw path.
        assertTrue(downloadUrl.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"),
                "downloadUrl must be SigV4 presigned (X-Amz-Algorithm), got: " + downloadUrl);
        assertTrue(downloadUrl.contains("X-Amz-Signature="),
                "downloadUrl must carry a signature, got: " + downloadUrl);
        assertTrue(downloadUrl.contains("X-Amz-Expires="),
                "downloadUrl must carry an expiry, got: " + downloadUrl);
    }

    @Test
    void downloadUrlContainsBucketAndObjectPath() {
        JSONObject json = presign(baseConfig());
        String downloadUrl = URLDecoder.decode(json.getString("downloadUrl"), StandardCharsets.UTF_8);
        assertTrue(downloadUrl.contains(BUCKET), "downloadUrl must contain bucket, got: " + downloadUrl);
        assertTrue(downloadUrl.contains(OBJECT_KEY), "downloadUrl must contain objectKey, got: " + downloadUrl);
    }

    @Test
    void uploadUrlIsStillPresignedPut() {
        JSONObject json = presign(baseConfig());
        String uploadUrl = json.getString("uploadUrl");
        assertNotNull(uploadUrl, "uploadUrl must be present");
        assertTrue(uploadUrl.contains("X-Amz-Signature="),
                "uploadUrl must remain a presigned URL, got: " + uploadUrl);
        // PUT presign keeps the existing 3600s expiry.
        assertEquals(3600, extractExpires(uploadUrl), "uploadUrl expiry must stay at 3600s");
    }

    @Test
    void objectKeyEchoedBack() {
        JSONObject json = presign(baseConfig());
        assertEquals(OBJECT_KEY, json.getString("objectKey"));
    }

    @Test
    void downloadExpiryDefaultsToSevenDaysWhenAbsent() {
        JSONObject json = presign(baseConfig());
        assertEquals(604800, extractExpires(json.getString("downloadUrl")));
    }

    @Test
    void downloadExpiryClampedToMinWhenTooSmall() {
        Map<String, String> config = baseConfig();
        config.put("minioDownloadExpiry", "100");
        assertEquals(1800, extractExpires(presign(config).getString("downloadUrl")));
    }

    @Test
    void downloadExpiryClampedToMaxWhenTooLarge() {
        Map<String, String> config = baseConfig();
        config.put("minioDownloadExpiry", "99999999");
        assertEquals(604800, extractExpires(presign(config).getString("downloadUrl")));
    }

    @Test
    void downloadExpiryHonouredWhenInRange() {
        Map<String, String> config = baseConfig();
        config.put("minioDownloadExpiry", "3600");
        assertEquals(3600, extractExpires(presign(config).getString("downloadUrl")));
    }

    @Test
    void downloadExpiryDefaultsWhenBlankOrUnparseable() {
        Map<String, String> blank = baseConfig();
        blank.put("minioDownloadExpiry", "   ");
        assertEquals(604800, extractExpires(presign(blank).getString("downloadUrl")));

        Map<String, String> garbage = baseConfig();
        garbage.put("minioDownloadExpiry", "not-a-number");
        assertEquals(604800, extractExpires(presign(garbage).getString("downloadUrl")));
    }
}
