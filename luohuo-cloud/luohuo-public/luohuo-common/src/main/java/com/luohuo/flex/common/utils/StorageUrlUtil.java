package com.luohuo.flex.common.utils;

/**
 * 对象存储 URL 工具。
 *
 * <p>老文件消息只持久化了一条预签名 GET {@code url}，形如
 * {@code {minioUrlPrefix}/{bucket}/{objectKey}?X-Amz-Algorithm=...}。sign-on-access 的
 * 「按需重新签名」在缺少 objectKey 时需要从该 url 回推出 objectKey。</p>
 *
 * <p>纯工具，无 Spring 依赖，放在 luohuo-common 供 luohuo-im 与 luohuo-system 共用。</p>
 *
 * @author sign-on-access (aichatoverview#146)
 */
public class StorageUrlUtil {

    private StorageUrlUtil() {
    }

    /**
     * 从预签名 GET url 中解析 objectKey。
     *
     * <p>先去掉查询串，再返回第一个 {@code /{bucket}/} 段之后的路径子串。</p>
     *
     * @param url    预签名 url，形如 {@code {prefix}/{bucket}/{objectKey}?X-Amz-...}
     * @param bucket 桶名
     * @return objectKey；当 url 为空、bucket 为空或找不到 {@code /{bucket}/} 段时返回 {@code null}
     */
    public static String parseObjectKeyFromUrl(String url, String bucket) {
        if (url == null || url.isBlank() || bucket == null || bucket.isBlank()) {
            return null;
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String marker = "/" + bucket + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String key = path.substring(idx + marker.length());
        return key.isBlank() ? null : key;
    }
}
