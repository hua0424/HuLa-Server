package com.luohuo.flex.common.utils;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;

import java.util.Map;

/**
 * MinIO 预签名工具（sign-on-access #146）。
 *
 * <p>下沉自 {@code luohuo-system-biz} 的 {@code MinioStorage.presignGet}，放在 luohuo-common 供
 * <b>luohuo-im</b>（sign-on-access 下载端点）与 <b>luohuo-system</b>（原上传/下载引擎）共用同一份实现，
 * 免去跨模块 feign 调用。</p>
 *
 * <p>MinIO 预签名是纯本地 SigV4 运算，{@code getPresignedObjectUrl} <b>不</b>访问网络，因此无 Spring 依赖，
 * 也可离线单测。</p>
 *
 * @author sign-on-access (aichatoverview#146)
 */
public final class MinioPresigner {

    /** sign-on-access 下载预签名 (GET) 有效期下限：60 秒。 */
    private static final int MIN_SIGN_EXPIRY_SECONDS = 60;

    /** sign-on-access 下载预签名 (GET) 有效期上限：1 小时。 */
    private static final int MAX_SIGN_EXPIRY_SECONDS = 3600;

    /** sign-on-access 下载预签名 (GET) 默认有效期：5 分钟。 */
    private static final int DEFAULT_SIGN_EXPIRY_SECONDS = 300;

    /**
     * MinIO 默认 region。显式设置 region 可避免 SDK 在生成预签名前先发一次
     * GetBucketLocation 网络请求（私有 MinIO 通常用此默认值）。
     */
    private static final String DEFAULT_REGION = "us-east-1";

    private MinioPresigner() {
    }

    /**
     * 为给定 objectKey 生成一条短效预签名 GET url。
     *
     * @param minioConfig   连接配置，键：{@code minioEndpoint} / {@code minioAccessKey} /
     *                      {@code minioSecretKey} / {@code minioBucket}，可选 {@code minioRegion}
     * @param objectKey     对象键
     * @param expirySeconds 期望有效期（秒），最终钳制到
     *                      [{@link #MIN_SIGN_EXPIRY_SECONDS}, {@link #MAX_SIGN_EXPIRY_SECONDS}]
     * @return 预签名 GET url 字符串
     */
    public static String presignGet(Map<String, String> minioConfig, String objectKey, int expirySeconds) {
        String endpoint = minioConfig.getOrDefault("minioEndpoint", "");
        String accessKey = minioConfig.getOrDefault("minioAccessKey", "");
        String secretKey = minioConfig.getOrDefault("minioSecretKey", "");
        String bucket = minioConfig.getOrDefault("minioBucket", "");
        String region = resolveRegion(minioConfig.get("minioRegion"));
        int expiry = clamp(expirySeconds);

        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();

            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(expiry)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("MinIO 预签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 sign-on-access 下载有效期（秒）并钳制到
     * [{@link #MIN_SIGN_EXPIRY_SECONDS}, {@link #MAX_SIGN_EXPIRY_SECONDS}]。
     *
     * <p>{@code requested > 0} 时优先取 requested；否则解析配置 {@code minioSignExpiry}，
     * 无效/缺省再回退到 {@link #DEFAULT_SIGN_EXPIRY_SECONDS}。</p>
     *
     * @param rawMinioSignExpiry 配置 {@code minioSignExpiry} 原始字符串（可为 {@code null}）
     * @param requested          调用方期望值（{@code <= 0} 表示未指定，回退配置默认）
     * @return 钳制后的有效期秒数
     */
    public static int resolveSignExpiry(String rawMinioSignExpiry, int requested) {
        int seconds = requested > 0 ? requested : parseConfigured(rawMinioSignExpiry);
        return clamp(seconds);
    }

    private static int parseConfigured(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignore) {
                // 无法解析 → 回退内置默认值
            }
        }
        return DEFAULT_SIGN_EXPIRY_SECONDS;
    }

    private static int clamp(int seconds) {
        return Math.max(MIN_SIGN_EXPIRY_SECONDS, Math.min(MAX_SIGN_EXPIRY_SECONDS, seconds));
    }

    private static String resolveRegion(String region) {
        return region == null || region.isBlank() ? DEFAULT_REGION : region.trim();
    }
}
