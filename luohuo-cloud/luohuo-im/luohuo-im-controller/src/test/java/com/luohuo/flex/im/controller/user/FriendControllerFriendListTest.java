package com.luohuo.flex.im.controller.user;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.core.user.service.RoleService;
import com.luohuo.flex.im.domain.enums.RoleTypeEnum;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#296（T05）: /user/friend/page 的显式 uid 只能查自己或（管理员）他人。
 *
 * <p>修复前：任何认证身份（含 aiclaw token）传 uid 即可读取任意用户的好友列表与备注
 * （私密关系数据越权）。修复后：uid != caller 且 caller 非管理员 → BizException，不触达 service。
 */
class FriendControllerFriendListTest {

	private static final Long CALLER = 1001L;
	private static final Long OTHER = 2002L;

	private FriendService friendService;
	private RoleService roleService;
	private FriendController controller;

	@BeforeEach
	void setUp() {
		friendService = mock(FriendService.class);
		roleService = mock(RoleService.class);
		controller = new FriendController();
		ReflectionTestUtils.setField(controller, "friendService", friendService);
		ReflectionTestUtils.setField(controller, "roleService", roleService);
	}

	private void run(Long callerUid, Runnable body) {
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(callerUid);
			body.run();
		}
	}

	@Test
	@DisplayName("无 uid：查当前认证身份自己的好友列表（原行为保持）")
	void defaultQueriesCallerSelf() {
		run(CALLER, () -> {
			when(friendService.friendList(eq(CALLER), any())).thenReturn(CursorPageBaseResp.empty());
			controller.friendList(new CursorPageBaseReq(), null);
			verify(friendService).friendList(eq(CALLER), any());
		});
	}

	@Test
	@DisplayName("uid == caller：允许（自己查自己）")
	void explicitSelfUidAllowed() {
		run(CALLER, () -> {
			when(friendService.friendList(eq(CALLER), any())).thenReturn(CursorPageBaseResp.empty());
			controller.friendList(new CursorPageBaseReq(), CALLER);
			verify(friendService).friendList(eq(CALLER), any());
		});
	}

	@Test
	@DisplayName("uid != caller 且非管理员：拒绝，不触达 friendService（aiclaw 不能读 owner 好友）")
	void otherUidRejectedForNonAdmin() {
		when(roleService.hasRole(CALLER, RoleTypeEnum.ADMIN)).thenReturn(false);
		run(CALLER, () -> {
			try {
				controller.friendList(new CursorPageBaseReq(), OTHER);
				fail("expected BizException");
			} catch (BizException e) {
				// expected
			}
			verifyNoInteractions(friendService);
		});
	}

	@Test
	@DisplayName("uid != caller 且为管理员：允许（后台场景保持）")
	void otherUidAllowedForAdmin() {
		when(roleService.hasRole(CALLER, RoleTypeEnum.ADMIN)).thenReturn(true);
		run(CALLER, () -> {
			when(friendService.friendList(eq(OTHER), any())).thenReturn(CursorPageBaseResp.empty());
			controller.friendList(new CursorPageBaseReq(), OTHER);
			verify(friendService).friendList(eq(OTHER), any());
		});
	}

}
