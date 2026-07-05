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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sign-on-access(#146)：{@link BaseFileDTO} 发送校验——url 放开为可选，但 objectKey 与 url
 * 至少一个非空（否则消息无法定位文件）。锁住 review 三态：旧 url-only 过 / 新 objectKey-only 过 / 皆空拒。
 */
class BaseFileDTOValidationTest {

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

    private FileMsgDTO file(String url, String objectKey) {
        FileMsgDTO f = new FileMsgDTO();
        f.setSize(120L);
        f.setFileName("x.txt");
        f.setUrl(url);
        f.setObjectKey(objectKey);
        return f;
    }

    private boolean hasFileRefViolation(BaseFileDTO dto) {
        Set<ConstraintViolation<BaseFileDTO>> v = validator.validate(dto);
        return v.stream().anyMatch(c -> c.getMessage().contains("objectKey 与 url 至少一个非空"));
    }

    @Test
    @DisplayName("旧消息 url-only（无 objectKey）→ 校验通过（向后兼容旧客户端）")
    void urlOnlyPasses() {
        assertFalse(hasFileRefViolation(file("http://minio/tmp/chat/a.txt?X-Amz=...", null)));
    }

    @Test
    @DisplayName("新消息 objectKey-only（url 置空）→ 校验通过（sign-on-access 主路径）")
    void objectKeyOnlyPasses() {
        assertFalse(hasFileRefViolation(file(null, "chat/1717_a.txt")));
        assertFalse(hasFileRefViolation(file("", "chat/1717_a.txt")));
    }

    @Test
    @DisplayName("两者皆空 → 校验拒绝（坏消息无法定位文件）")
    void bothBlankRejected() {
        assertTrue(hasFileRefViolation(file(null, null)));
        assertTrue(hasFileRefViolation(file("   ", "  ")));
    }

    @Test
    @DisplayName("size 仍 @NotNull（放开 url 不影响其它必填）")
    void sizeStillRequired() {
        FileMsgDTO f = file(null, "chat/a.txt");
        f.setSize(null);
        Set<ConstraintViolation<FileMsgDTO>> v = validator.validate(f);
        assertEquals(1, v.stream().filter(c -> c.getPropertyPath().toString().equals("size")).count());
    }
}
