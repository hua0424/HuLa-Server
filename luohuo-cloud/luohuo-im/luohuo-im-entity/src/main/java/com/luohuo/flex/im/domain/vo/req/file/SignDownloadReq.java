package com.luohuo.flex.im.domain.vo.req.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * sign-on-access(#146)：按需下载签名请求。
 *
 * @author sign-on-access (aichatoverview#146)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignDownloadReq implements Serializable {

    @Schema(description = "消息 id")
    @NotNull
    @Min(value = 1, message = "msgId 必须为正整数")
    private Long msgId;

    @Schema(description ="下载目标：file=主文件(默认)｜thumb=缩略图（仅视频消息）")
    private String target;
}
