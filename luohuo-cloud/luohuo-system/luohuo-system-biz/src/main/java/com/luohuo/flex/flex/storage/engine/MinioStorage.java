package com.luohuo.flex.flex.storage.engine;

import com.alibaba.fastjson.JSONObject;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class MinioStorage {

    /** 上传预签名 (PUT) 有效期：1 小时（保持原有行为）。 */
    private static final int UPLOAD_EXPIRY_SECONDS = 60 * 60;

    /** 下载预签名 (GET) 默认有效期：7 天。 */
    private static final int DEFAULT_DOWNLOAD_EXPIRY_SECONDS = 7 * 24 * 60 * 60; // 604800

    /** 下载有效期下限：30 分钟。 */
    private static final int MIN_DOWNLOAD_EXPIRY_SECONDS = 30 * 60; // 1800

    /** 下载有效期上限：7 天 —— AWS SigV4 / MinIO 预签名硬上限。 */
    private static final int MAX_DOWNLOAD_EXPIRY_SECONDS = 7 * 24 * 60 * 60; // 604800

    /**
     * MinIO 默认 region。显式设置 region 可避免 SDK 在生成预签名前先发一次
     * GetBucketLocation 网络请求（私有 MinIO 通常用此默认值）。
     */
    private static final String DEFAULT_REGION = "us-east-1";

    private final Map<String, String> config;

    public MinioStorage(Map<String, String> config) {
        this.config = config;
    }

    public JSONObject presignPut(String objectKey) {
        String endpoint = this.config.getOrDefault("minioEndpoint", "");
        String accessKey = this.config.getOrDefault("minioAccessKey", "");
        String secretKey = this.config.getOrDefault("minioSecretKey", "");
        String bucket = this.config.getOrDefault("minioBucket", "");
        String region = resolveRegion();
        int downloadExpirySeconds = resolveDownloadExpiry();

        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();

            String uploadUrl = client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(UPLOAD_EXPIRY_SECONDS)
                            .build()
            );

            // REQ-007: downloadUrl 必须是预签名 GET，私有 bucket 的原始路径会返回 403。
            String downloadUrl = client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(downloadExpirySeconds)
                            .build()
            );

            JSONObject json = new JSONObject();
            json.put("uploadUrl", uploadUrl);
            json.put("downloadUrl", downloadUrl);
            // 保留 objectKey，为后续「按需重新签名」端点预留。
            json.put("objectKey", objectKey);
            return json;
        } catch (Exception e) {
            log.error("[Minio presignPut] 生成预签名失败, objectKey:{}", objectKey, e);
            throw new RuntimeException("MinIO 预签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析下载有效期（秒）。读取 {@code minioDownloadExpiry}，无效/缺省时回退到
     * {@link #DEFAULT_DOWNLOAD_EXPIRY_SECONDS}，并钳制到
     * [{@link #MIN_DOWNLOAD_EXPIRY_SECONDS}, {@link #MAX_DOWNLOAD_EXPIRY_SECONDS}]。
     */
    private int resolveDownloadExpiry() {
        String raw = this.config.get("minioDownloadExpiry");
        int seconds = DEFAULT_DOWNLOAD_EXPIRY_SECONDS;
        if (raw != null && !raw.isBlank()) {
            try {
                seconds = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                log.warn("[Minio presignPut] minioDownloadExpiry 无法解析:{}, 回退默认值 {}s",
                        raw, DEFAULT_DOWNLOAD_EXPIRY_SECONDS);
                seconds = DEFAULT_DOWNLOAD_EXPIRY_SECONDS;
            }
        }
        return Math.max(MIN_DOWNLOAD_EXPIRY_SECONDS, Math.min(MAX_DOWNLOAD_EXPIRY_SECONDS, seconds));
    }

    private String resolveRegion() {
        String region = this.config.get("minioRegion");
        return region == null || region.isBlank() ? DEFAULT_REGION : region.trim();
    }
}
