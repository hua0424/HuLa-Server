package com.luohuo.flex.im.domain.vo.response.msg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.io.Serializable;


/**
 * 文件基类
 * @author nyh
 */
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class BaseFileDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    @Schema(description ="大小（字节）")
    @NotNull
    private Long size;

    // sign-on-access(#146)：url 不再强制——新消息只带 objectKey、url 置空；旧消息带长效 url。
    // 由 #hasFileRef 保证「objectKey 与 url 至少一个非空」，不会退化成两者皆空的坏消息。
    @Schema(description ="下载地址（可选；新消息置空，改用 objectKey 按需换签）")
    private String url;

    @Schema(description ="文件 MIME 类型，如 image/png、application/pdf")
    private String mime;

    @Schema(description ="对象存储 objectKey，用于按需重新签名下载地址（可选，url 为空时使用）")
    private String objectKey;

    /** sign-on-access(#146)：objectKey 与 url 至少一个非空（否则消息无法定位文件）。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "objectKey 与 url 至少一个非空")
    public boolean isHasFileRef() {
        return (url != null && !url.isBlank()) || (objectKey != null && !objectKey.isBlank());
    }
}
