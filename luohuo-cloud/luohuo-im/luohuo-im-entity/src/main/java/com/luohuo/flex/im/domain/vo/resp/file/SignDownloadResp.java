package com.luohuo.flex.im.domain.vo.resp.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * sign-on-access(#146)：按需下载签名响应。
 *
 * @author sign-on-access (aichatoverview#146)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignDownloadResp implements Serializable {

    @Schema(description = "短效预签名 GET 下载地址")
    private String url;

    @Schema(description = "下载地址有效期（秒），与 url 实际生命周期一致")
    private Integer expiresIn;
}
