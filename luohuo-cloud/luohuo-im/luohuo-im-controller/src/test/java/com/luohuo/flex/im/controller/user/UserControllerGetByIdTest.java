package com.luohuo.flex.im.controller.user;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.user.service.UserService;
import com.luohuo.flex.im.domain.vo.resp.user.UserInfoResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#296（T05）: /user/getById/{id} 的字段裁剪。
 *
 * <p>修复前：任何认证身份（含 aiclaw 的 member-info 查询）都能拿到目标用户的
 * email / modifyNameChance / context / num 等私有字段。修复后：caller != id 时
 * 这四个私有字段置空；公开资料字段（昵称/头像/简介/账号/userType/ownerInfo）保持返回。
 * caller == id（本人）保持原行为不裁剪；caller == null（网关后不会出现，仅服务间直调可能）
 * 一并裁剪——现有服务间消费者（oauth granter 等）只读注册/黑名单相关字段，不受影响。
 */
class UserControllerGetByIdTest {

	private static final Long CALLER = 1001L;
	private static final Long TARGET = 2002L;

	private UserService userService;
	private UserController controller;

	@BeforeEach
	void setUp() {
		userService = mock(UserService.class);
		controller = new UserController();
		ReflectionTestUtils.setField(controller, "userService", userService);
	}

	private UserInfoResp fullProfile() {
		UserInfoResp resp = new UserInfoResp();
		resp.setUid(TARGET);
		resp.setName("target");
		resp.setAvatar("a.png");
		resp.setResume("hi");
		resp.setAccount("h100");
		resp.setEmail("target@example.com");
		resp.setModifyNameChance(3);
		resp.setContext(true);
		resp.setNum(5);
		return resp;
	}

	@Test
	@DisplayName("caller != id（含 aiclaw 查询他人）：私有字段被裁剪，公开字段保留")
	void otherUserQueryIsTrimmed() {
		when(userService.getUserInfo(TARGET)).thenReturn(fullProfile());
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(CALLER);
			UserInfoResp resp = controller.getById(TARGET).getData();
			assertEquals(TARGET, resp.getUid());
			assertEquals("target", resp.getName());
			assertEquals("h100", resp.getAccount());
			assertNull(resp.getEmail());
			assertNull(resp.getModifyNameChance());
			assertNull(resp.getContext());
			assertNull(resp.getNum());
		}
	}

	@Test
	@DisplayName("caller == id（本人）：私有字段保持返回（个人资料页原行为）")
	void selfQueryKeepsPrivateFields() {
		when(userService.getUserInfo(CALLER)).thenReturn(fullProfile());
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(CALLER);
			UserInfoResp resp = controller.getById(CALLER).getData();
			assertEquals("target@example.com", resp.getEmail());
			assertEquals(3, resp.getModifyNameChance());
			assertEquals(true, resp.getContext());
			assertEquals(5, resp.getNum());
		}
	}
}
