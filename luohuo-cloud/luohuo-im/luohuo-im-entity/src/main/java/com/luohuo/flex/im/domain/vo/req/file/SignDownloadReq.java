package com.luohuo.flex.im.domain.vo.req.file;

import io.swagger.v3.oas.annotations.media.Schema;
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
    private Long msgId;
}
