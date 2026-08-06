package com.luohuo.flex.im.domain.vo.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #202 code:-4 修复：群名长度上限统一 32（建群 GroupAddReq.groupName / 改名 RoomInfoReq.name 拉齐）。
 * im_room_group.name 历史上 varchar(16) 而 GroupAddReq.groupName 无长度校验，>16 字符群名 INSERT 时
 * MysqlDataTruncation → DataIntegrityViolation → 全局映射 SQL_EX(-4)。现校验卡 32（DDL 放宽至 64 留余量）。
 * groupName null/空串仍放行 → 走默认群名生成路径，不得加 min/NotNull。
 */
class GroupNameValidationTest {

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

	private Set<ConstraintViolation<GroupAddReq>> createViolations(String groupName) {
		return validator.validate(GroupAddReq.builder().uidList(List.of(1L, 2L)).groupName(groupName).build());
	}

	private Set<ConstraintViolation<RoomInfoReq>> renameViolations(String name) {
		RoomInfoReq req = new RoomInfoReq();
		req.setId(1L);
		req.setAvatar("https://cdn/x.png");
		req.setName(name);
		return validator.validate(req);
	}

	@Test
	@DisplayName("建群：33 字符群名 → 违约（校验拦截，不再打到 DB 触发 SQL_EX(-4)）")
	void createGroup_name33_rejected() {
		Set<ConstraintViolation<GroupAddReq>> v = createViolations("群".repeat(33));
		assertTrue(v.stream().anyMatch(c -> c.getPropertyPath().toString().equals("groupName")));
	}

	@Test
	@DisplayName("建群：32 字符群名 → 校验通过（上限边界）")
	void createGroup_name32_passes() {
		assertTrue(createViolations("群".repeat(32)).isEmpty());
	}

	@Test
	@DisplayName("建群：groupName null/空串 → 校验通过（走默认群名生成路径）")
	void createGroup_nameNullOrEmpty_passes() {
		assertTrue(createViolations(null).isEmpty());
		assertTrue(createViolations("").isEmpty());
	}

	@Test
	@DisplayName("改名：name null → 违约（@NotNull）")
	void renameGroup_nameNull_rejected() {
		Set<ConstraintViolation<RoomInfoReq>> v = renameViolations(null);
		assertTrue(v.stream().anyMatch(c -> c.getPropertyPath().toString().equals("name")));
	}

	@Test
	@DisplayName("改名：33 字符违约（原 max=10 拉齐到 32）；32 字符通过")
	void renameGroup_nameBounds_aligned32() {
		assertTrue(renameViolations("群".repeat(33)).stream()
				.anyMatch(c -> c.getPropertyPath().toString().equals("name")));
		assertTrue(renameViolations("群".repeat(32)).isEmpty());
	}
}
