package com.luohuo.flex.im.domain.vo.response.msg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.luohuo.flex.im.domain.entity.msg.ReplyMsg;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 视频消息入参
 * @author nyh
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class VideoMsgDTO extends BaseFileDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description ="缩略图宽度（像素）")
    @NotNull
    private Integer thumbWidth;

    @Schema(description ="缩略图高度（像素）")
    @NotNull
    private Integer thumbHeight;

    @Schema(description ="缩略图大小（字节）")
    @NotNull
    private Long thumbSize;

    // sign-on-access(#158，镜像 #146)：thumbUrl 不再强制——新消息只带 thumbObjectKey、thumbUrl 置空；
    // 旧消息带长效 thumbUrl。由 #hasThumbRef 保证「thumbObjectKey 与 thumbUrl 至少一个非空」。
    @Schema(description ="缩略图下载地址（可选；新消息置空，改用 thumbObjectKey 按需换签）")
    private String thumbUrl;

    @Schema(description ="缩略图对象存储 objectKey，用于按需重新签名（可选，thumbUrl 为空时使用）")
    private String thumbObjectKey;

	@Schema(description ="回复的消息id")
	private Long replyMsgId;

	@Schema(description ="艾特的uid")
	private List<Long> atUidList;

	@Schema(description ="父消息，如果没有父消息，返回的是null")
	private ReplyMsg reply;

    /** #158（镜像 #146）：thumbObjectKey 与 thumbUrl 至少一个非空（否则缩略图无法定位）。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "thumbObjectKey 与 thumbUrl 至少一个非空")
    public boolean isHasThumbRef() {
        return (getThumbUrl() != null && !getThumbUrl().isBlank())
                || (thumbObjectKey != null && !thumbObjectKey.isBlank());
    }
}
