package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.CcAiclawResolveService;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.CcAiclawResolveResp;
import com.luohuo.flex.model.entity.ws.CcLaunchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-010 S9 (#100): CcLaunchController 接线层行为（FINAL 架构）。
 *
 * <p>锁定：requesterUid <b>只</b>来自认证身份 {@link ContextUtil#getUid()}（不消费 query）；
 * resolve OK → 调 ws ccBind → R.success(CcLaunchResp)；resolve 每个 errorCode → R.fail（契约文案）；
 * ws 调用返回 null（节点离线/服务不可达）→ R.fail 离线/超时。</p>
 *
 * <p>standalone MockMvc + mock {@link CcAiclawResolveService} + {@link MockedStatic} ContextUtil；
 * 用 spy 桩掉 {@code callWsCcBind}（避免真实 DiscoveryClient+HTTP）。</p>
 */
class CcLaunchControllerTest {

	private static final Long REQUESTER = 1001L;
	private static final Long ROOM_ID = 10L;
	private static final Long AICLAW_UID = 200L;
	private static final Long COUNTERPART = 2L;

	private CcAiclawResolveService resolveService;
	private CcLaunchController controller;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		resolveService = mock(CcAiclawResolveService.class);
		controller = spy(new CcLaunchController());
		ReflectionTestUtils.setField(controller, "ccAiclawResolveService", resolveService);
		ReflectionTestUtils.setField(controller, "internalToken", "test-token");
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	private CcAiclawResolveResp ok() {
		return CcAiclawResolveResp.builder()
				.ok(true).aiclawUid(AICLAW_UID).roomType(2).counterpartUid(COUNTERPART).build();
	}

	private CcAiclawResolveResp err(String code) {
		return CcAiclawResolveResp.builder().ok(false).errorCode(code).build();
	}

	@Test
	@DisplayName("resolve OK → 调 ws ccBind → R.success(CcLaunchResp)，且 requesterUid 取自认证身份")
	void resolveOk_callsWs_success() throws Exception {
		when(resolveService.resolveCcAiclawForRoom(eq(ROOM_ID), eq(REQUESTER), any())).thenReturn(ok());
		doReturn(CcLaunchResp.builder().launchCommand("claude --resume xyz").workspaceDir("/work/abc").build())
				.when(controller).callWsCcBind(AICLAW_UID, ROOM_ID, 2, COUNTERPART);

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(REQUESTER);

			mockMvc.perform(get("/room/aiclaw/cc-launch").param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.code").value(200))
					.andExpect(jsonPath("$.data.launchCommand").value("claude --resume xyz"))
					.andExpect(jsonPath("$.data.workspaceDir").value("/work/abc"));

			// 核心：resolve 收到的是认证身份 REQUESTER（不是 query 伪造）。
			verify(resolveService).resolveCcAiclawForRoom(ROOM_ID, REQUESTER, null);
		}
	}

	@Test
	@DisplayName("未登录（getUid null）→ R.fail 未登录，且不触达 resolve/ws")
	void notLoggedIn_fails() throws Exception {
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(null);

			mockMvc.perform(get("/room/aiclaw/cc-launch").param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.msg").value("未登录"));

			verify(resolveService, never()).resolveCcAiclawForRoom(anyLong(), anyLong(), any());
		}
	}

	@Test
	@DisplayName("resolve NO_CC → R.fail 该房间没有 CC 助理，不调 ws")
	void noCc_fails() throws Exception {
		assertResolveErrorMapsTo("NO_CC", "该房间没有 CC 助理");
	}

	@Test
	@DisplayName("resolve AMBIGUOUS → R.fail 房间有多个 CC 助理，请指定 uid")
	void ambiguous_fails() throws Exception {
		assertResolveErrorMapsTo("AMBIGUOUS", "房间有多个 CC 助理，请指定 uid");
	}

	@Test
	@DisplayName("resolve UID_NOT_CC → R.fail 指定的 uid 不是该房间的 CC 助理")
	void uidNotCc_fails() throws Exception {
		assertResolveErrorMapsTo("UID_NOT_CC", "指定的 uid 不是该房间的 CC 助理");
	}

	@Test
	@DisplayName("resolve NOT_OWNER → R.fail 无权限")
	void notOwner_fails() throws Exception {
		assertResolveErrorMapsTo("NOT_OWNER", "无权限");
	}

	@Test
	@DisplayName("resolve OK 但 ws 返回 null（节点离线/服务不可达）→ R.fail 离线/超时")
	void wsOffline_fails() throws Exception {
		when(resolveService.resolveCcAiclawForRoom(eq(ROOM_ID), eq(REQUESTER), any())).thenReturn(ok());
		doReturn(null).when(controller).callWsCcBind(AICLAW_UID, ROOM_ID, 2, COUNTERPART);

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(REQUESTER);

			mockMvc.perform(get("/room/aiclaw/cc-launch").param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.msg").value("CC 助理节点离线或响应超时"));
		}
	}

	private void assertResolveErrorMapsTo(String code, String expectedMsg) throws Exception {
		when(resolveService.resolveCcAiclawForRoom(eq(ROOM_ID), eq(REQUESTER), any())).thenReturn(err(code));

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(REQUESTER);

			mockMvc.perform(get("/room/aiclaw/cc-launch").param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.msg").value(expectedMsg));

			// resolve 失败时不应触达 ws。
			verify(controller, never()).callWsCcBind(anyLong(), anyLong(), any(), any());
		}
	}
}
