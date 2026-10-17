package com.luohuo.flex.im.domain.entity.msg;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aichatoverview#434: 文本消息正文上限 1024→32768 字符（ADR-0012）。
 * TextMsgReq 是 Text/Bot 共用的唯一正文校验入口（经 AbstractMsgHandler.checkAndSaveMsg →
 * AssertUtil.allCheckValidateThrow → PARAM_VALID），无发送者分支，故单测此一处即覆盖人类与 aiclaw。
 * 用 CJK 字符断言计数口径为字符数而非字节数。
 */
class TextMsgReqValidationTest {

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

	private Set<ConstraintViolation<TextMsgReq>> violations(String content) {
		TextMsgReq req = new TextMsgReq();
		req.setContent(content);
		return validator.validate(req);
	}

	@Test
	@DisplayName("略超旧限：1025 字符 → 通过（旧 1024 闸已放开）")
	void justOverOldLimit_passes() {
		assertTrue(violations("文".repeat(1025)).isEmpty());
	}

	@Test
	@DisplayName("新上限边界：32768 字符 → 通过")
	void atNewLimit_passes() {
		assertTrue(violations("文".repeat(32768)).isEmpty());
	}

	@Test
	@DisplayName("超新上限：32769 字符 → content 违约（线上映射为 PARAM_VALID「消息内容过长」）")
	void overNewLimit_rejected() {
		Set<ConstraintViolation<TextMsgReq>> v = violations("文".repeat(32769));
		assertTrue(v.stream().anyMatch(c -> c.getPropertyPath().toString().equals("content")));
	}
}
