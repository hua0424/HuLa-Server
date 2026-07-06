package com.luohuo.flex.im.domain.vo.response.msg;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #158（镜像 #146）：{@link VideoMsgDTO} 缩略图发送校验——thumbUrl 放开为可选，但 thumbObjectKey 与
 * thumbUrl 至少一个非空（否则缩略图无法定位）。锁住三态：新 thumbObjectKey-only 过 / 旧 thumbUrl-only
 * 过 / 皆空拒。主文件的 objectKey/url（继承自 BaseFileDTO，#146 治理）在每个用例里都置为已满足，
 * 以隔离出缩略图断言。
 */
class VideoMsgDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.close();
    }

    /** 主文件引用 + 缩略图三要素齐备的合法基底；调用方只改缩略图 url/objectKey。 */
    private VideoMsgDTO video(String thumbUrl, String thumbObjectKey) {
        VideoMsgDTO v = new VideoMsgDTO();
        // 主文件（继承自 BaseFileDTO）：size @NotNull + isHasFileRef 满足
        v.setSize(2048L);
        v.setObjectKey("chat/1717_video.mp4");
        // 缩略图三要素 @NotNull
        v.setThumbWidth(320);
        v.setThumbHeight(180);
        v.setThumbSize(4096L);
        // 被测缩略图引用
        v.setThumbUrl(thumbUrl);
        v.setThumbObjectKey(thumbObjectKey);
        return v;
    }

    private boolean hasThumbRefViolation(VideoMsgDTO dto) {
        Set<ConstraintViolation<VideoMsgDTO>> v = validator.validate(dto);
        return v.stream().anyMatch(c -> c.getMessage().contains("thumbObjectKey 与 thumbUrl 至少一个非空"));
    }

    @Test
    @DisplayName("新消息 thumbObjectKey-only（thumbUrl 置空）→ 校验通过（sign-on-access 主路径）")
    void thumbObjectKeyOnlyPasses() {
        assertFalse(hasThumbRefViolation(video(null, "chat/1717_video_thumb.png")));
        assertFalse(hasThumbRefViolation(video("", "chat/1717_video_thumb.png")));
    }

    @Test
    @DisplayName("旧消息 thumbUrl-only（无 thumbObjectKey）→ 校验通过（向后兼容旧客户端）")
    void thumbUrlOnlyPasses() {
        assertFalse(hasThumbRefViolation(video("http://minio/tmp/chat/thumb.png?X-Amz=...", null)));
    }

    @Test
    @DisplayName("两者皆空 → 校验拒绝于 isHasThumbRef（坏消息无法定位缩略图）")
    void bothThumbBlankRejected() {
        assertTrue(hasThumbRefViolation(video(null, null)));
        assertTrue(hasThumbRefViolation(video("   ", "  ")));
    }
}
