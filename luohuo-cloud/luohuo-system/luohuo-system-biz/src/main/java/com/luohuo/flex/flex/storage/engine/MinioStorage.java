package com.luohuo.flex.flex.storage.engine;

import com.alibaba.fastjson.JSONObject;
import com.luohuo.flex.common.utils.MinioPresigner;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class MinioStorage {

    /** 上传预签名 (PUT) 有效期：1 小时（保持原有行为）。 */
    private static final int UPLOAD_EXPIRY_SECONDS = 60 * 60;

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

            JSONObject json = new JSONObject();
            json.put("uploadUrl", uploadUrl);
            // 保留 objectKey，为 sign-on-access「按需重新签名」端点预留。
            // sign-on-access: 不再返回长效 downloadUrl，下载地址改由端点按需短效签名。
            json.put("objectKey", objectKey);
            return json;
        } catch (Exception e) {
            log.error("[Minio presignPut] 生成预签名失败, objectKey:{}", objectKey, e);
            throw new RuntimeException("MinIO 预签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * sign-on-access：为给定 objectKey 生成一条短效预签名 GET url。
     *
     * <p>实现下沉至 luohuo-common 的 {@link MinioPresigner}，im 与 system 共用同一份签名逻辑；
     * 本方法只保留公开签名与「有效期解析（{@code <=0} 回退配置 {@code minioSignExpiry}）」的入参转换。</p>
     *
     * @param objectKey     对象键
     * @param expirySeconds 期望有效期（秒）；{@code <= 0} 时回退到配置 {@code minioSignExpiry}
     *                      （无效/缺省再回退内置默认 300s），最终钳制到 [60, 3600]
     * @return 预签名 GET url 字符串
     */
    public String presignGet(String objectKey, int expirySeconds) {
        int expiry = MinioPresigner.resolveSignExpiry(
                this.config.get("minioSignExpiry"), this.config.get("minioDownloadExpiry"), expirySeconds);
        try {
            return MinioPresigner.presignGet(this.config, objectKey, expiry);
        } catch (Exception e) {
            log.error("[Minio presignGet] 生成预签名失败, objectKey:{}", objectKey, e);
            throw new RuntimeException("MinIO 预签名失败: " + e.getMessage(), e);
        }
    }

    private String resolveRegion() {
        String region = this.config.get("minioRegion");
        return region == null || region.isBlank() ? DEFAULT_REGION : region.trim();
    }
}
