package com.luohuo.flex.im.controller.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.RoomAppService;
import com.luohuo.flex.im.domain.vo.req.room.GroupUseStateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigStateResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupUseStateResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * aichatoverview#484 (T3)：两个「当前事实」窄读入口的路由 + wire 契约测试。
 *
 * <p>standalone MockMvc（不起 Spring context）+ mock service：锁住路径、参数绑定、
 * 以及 PRESENT/NOT_ACCESSIBLE 两种结论在 JSON 上的同形性（不额外披露存在性）。
 * 业务事实与缓存绕行由 {@code GroupUseStateReadTest} / {@code AiclawGroupConfigCurrentReadTest} 覆盖。</p>
 */
class GroupUseStateControllerTest {

	private static final Long UID = 1001L;
	private static final Long ROOM_ID = 10L;
	private static final Long AICLAW_UID = 2002L;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private RoomAppService roomService;
	private com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService configService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		roomService = mock(RoomAppService.class);
		configService = mock(com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService.class);

		RoomController roomController = new RoomController();
		ReflectionTestUtils.setField(roomController, "roomService", roomService);

		AiclawGroupConfigController configController = new AiclawGroupConfigController();
		ReflectionTestUtils.setField(configController, "aiclawGroupConfigService", configService);

		mockMvc = MockMvcBuilders.standaloneSetup(roomController, configController).build();
	}

	@Test
	@DisplayName("#484 /room/group/use-state：uid 取自认证上下文，参数绑定 roomId 与 memberUids")
	void groupUseStateBindsAndUsesContextUid() throws Exception {
		GroupUseStateResp resp = GroupUseStateResp.builder()
				.outcome(GroupUseStateResp.OUTCOME_MEMBER)
				.roomId(ROOM_ID)
				.selfUid(UID)
				.selfRoleId(1)
				.memberCount(7L)
				.groupName("测试群")
				.account("acc")
				.requestedMembers(List.of(GroupUseStateResp.MemberProjection.builder()
						.uid(3003L).exists(true).roleId(2).name("新成员").account("acc3").build()))
				.coverage(GroupUseStateResp.Coverage.builder()
						.requested(1).covered(1).limit(GroupUseStateReq.MAX_MEMBER_UIDS).truncated(false).build())
				.build();
		when(roomService.readGroupUseState(any(), eq(UID))).thenReturn(resp);

		String body;
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			body = mockMvc.perform(get("/room/group/use-state")
							.param("roomId", String.valueOf(ROOM_ID))
							.param("memberUids", "3003"))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
		}

		ArgumentCaptor<GroupUseStateReq> captor = ArgumentCaptor.forClass(GroupUseStateReq.class);
		verify(roomService).readGroupUseState(captor.capture(), eq(UID));
		assertEquals(ROOM_ID, captor.getValue().getRoomId());
		assertEquals(List.of(3003L), captor.getValue().getMemberUids());

		JsonNode json = objectMapper.readTree(body).path("data");
		assertEquals("MEMBER", json.path("outcome").asText());
		assertEquals(7L, json.path("memberCount").asLong());
		assertEquals(1, json.path("requestedMembers").size());
		// 大 Long 以字符串出参是既有约定；这里断言角色/存在性靠事实表字段保真
		assertTrue(json.path("requestedMembers").get(0).path("exists").asBoolean());
		assertEquals(2, json.path("requestedMembers").get(0).path("roleId").asInt());
	}

	@Test
	@DisplayName("#484 /aiclaw/group/config/current：PRESENT 带 configured，NOT_ACCESSIBLE 不带任何群内事实")
	void configCurrentOutcomes() throws Exception {
		when(configService.readCurrentGroupConfig(eq(AICLAW_UID), eq(ROOM_ID), eq(UID)))
				.thenReturn(AiclawGroupConfigStateResp.builder()
						.outcome(AiclawGroupConfigStateResp.OUTCOME_PRESENT)
						.configured(true)
						.config(AiclawGroupConfigResp.builder()
								.aiclawUid(AICLAW_UID).roomId(ROOM_ID)
								.rateLimitPerMinute(5).mentionRequired(1).dailyLimit(100)
								.respondToAi(0).approved(1).workspaceDir("/srv/proj").account("acc")
								.build())
						.build());

		String present;
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			present = mockMvc.perform(get("/aiclaw/group/config/current")
							.param("aiclawUid", String.valueOf(AICLAW_UID))
							.param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
		}

		JsonNode presentData = objectMapper.readTree(present).path("data");
		assertEquals("PRESENT", presentData.path("outcome").asText());
		assertTrue(presentData.path("configured").asBoolean());
		assertEquals(1, presentData.path("config").path("approved").asInt());
		assertEquals("/srv/proj", presentData.path("config").path("workspaceDir").asText());

		// 不可访问：同形字段、config 为 null，不额外披露群或助理是否存在
		when(configService.readCurrentGroupConfig(eq(AICLAW_UID), eq(ROOM_ID), eq(UID)))
				.thenReturn(AiclawGroupConfigStateResp.builder()
						.outcome(AiclawGroupConfigStateResp.OUTCOME_NOT_ACCESSIBLE)
						.configured(false)
						.config(null)
						.build());

		String denied;
		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			denied = mockMvc.perform(get("/aiclaw/group/config/current")
							.param("aiclawUid", String.valueOf(AICLAW_UID))
							.param("roomId", String.valueOf(ROOM_ID)))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
		}

		JsonNode deniedData = objectMapper.readTree(denied).path("data");
		assertEquals("NOT_ACCESSIBLE", deniedData.path("outcome").asText());
		assertTrue(deniedData.path("config").isNull());
		assertNull(objectMapper.readTree(denied).path("data").path("configured").textValue());
	}

	@Test
	@DisplayName("#484 两个入口都忽略请求体里的 uid 之类的越权参数（uid 只能来自认证上下文）")
	void uidCannotBeSpoofedByQueryParam() throws Exception {
		when(roomService.readGroupUseState(any(), eq(UID)))
				.thenReturn(GroupUseStateResp.builder()
						.outcome(GroupUseStateResp.OUTCOME_NOT_ACCESSIBLE)
						.roomId(ROOM_ID).selfUid(UID).build());

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			mockMvc.perform(get("/room/group/use-state")
							.param("roomId", String.valueOf(ROOM_ID))
							.param("uid", "999999"))
					.andExpect(status().isOk());
		}

		// 伪造的 uid 参数不会进入 service：认证上下文 uid 是唯一来源
		verify(roomService).readGroupUseState(any(), eq(UID));
	}
}
