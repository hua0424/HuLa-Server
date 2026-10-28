package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.RoomAppService;
import com.luohuo.flex.im.core.user.service.dto.PushReceipt;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #482：首批三个 HTTP 入口的响应契约。
 *
 * <p>锁定的事实：
 * <ul>
 *   <li>业务成功 + 必要通知未全部接收 → 顶层出现 {@code notificationSync.state=INCOMPLETE}，code/data/success 保持原语义；</li>
 *   <li>业务成功 + 全部接收（或根本没发生通知）→ 字段完全缺省，且绝不返回 COMPLETE/DELIVERED；</li>
 *   <li>管理员接口 data 仍为 null（不得因为加了回执就改成 Boolean）；</li>
 *   <li>停止期拒绝（目标未解析）不得被读成「全部送达」。</li>
 * </ul>
 * 采用 standalone MockMvc + mock service + {@link MockedStatic} 认证身份，最轻量且不启动 Spring 容器。
 */
class RoomControllerNotificationSyncTest {

	private static final Long UID = 1001L;

	private RoomAppService roomAppService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		roomAppService = mock(RoomAppService.class);
		RoomController controller = new RoomController();
		ReflectionTestUtils.setField(controller, "roomService", roomAppService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	/** 两个设备里只有 client-1 被接收 → 聚合 PARTIAL。 */
	private PushSubmission partialSubmission() {
		List<PushSubmission.Target> targets = PushSubmission.targetsOf("node-1", Map.of("client-1", UID, "client-2", UID));
		targets.get(1).reject("执行器拒绝");
		return PushSubmission.combine(List.of(PushSubmission.resolved(targets)));
	}

	@Test
	@DisplayName("updateRoomInfo：部分端未接收时附加顶层 notificationSync=INCOMPLETE，data 仍为原 Boolean")
	void updateRoomInfoPartialAddsNotificationSync() throws Exception {
		when(roomAppService.updateRoomInfo(anyLong(), any(), any())).thenAnswer(invocation -> {
			PushReceipt receipt = invocation.getArgument(2);
			receipt.record(partialSubmission());
			return true;
		});

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			mockMvc.perform(post("/room/updateRoomInfo")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"id\":10,\"name\":\"n\",\"avatar\":\"a\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.code").value(200))
					.andExpect(jsonPath("$.data").value(true))
					.andExpect(jsonPath("$.notificationSync.state").value("INCOMPLETE"));
		}
	}

	@Test
	@DisplayName("updateRoomInfo：全部接收时字段缺省，不返回 COMPLETE/DELIVERED")
	void updateRoomInfoFullyAdmittedOmitsField() throws Exception {
		when(roomAppService.updateRoomInfo(anyLong(), any(), any())).thenAnswer(invocation -> {
			PushReceipt receipt = invocation.getArgument(2);
			receipt.record(PushSubmission.resolved(PushSubmission.targetsOf("node-1", Map.of("client-1", UID))));
			return true;
		});

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			mockMvc.perform(post("/room/updateRoomInfo")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"id\":10,\"name\":\"n\",\"avatar\":\"a\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data").value(true))
					.andExpect(jsonPath("$.notificationSync").doesNotExist());
		}
	}

	@Test
	@DisplayName("updateRoomInfo：业务返回 false 时不发送任何同步补充信息（业务失败表达不靠该字段）")
	void updateRoomInfoBusinessFalseKeepsPlainFailure() throws Exception {
		when(roomAppService.updateRoomInfo(anyLong(), any(), any())).thenReturn(false);

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			mockMvc.perform(post("/room/updateRoomInfo")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"id\":10,\"name\":\"n\",\"avatar\":\"a\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data").value(false))
					.andExpect(jsonPath("$.notificationSync").doesNotExist());
		}
	}

	@Test
	@DisplayName("addAdmin / revokeAdmin：data 保持 null，部分未接收时附加 notificationSync")
	void adminEndpointsKeepNullDataAndCarrySyncState() throws Exception {
		// 两个门面方法都是 void，只能用 doAnswer 记录回执，不能用 when(...)
		doAnswer(invocation -> {
			PushReceipt receipt = invocation.getArgument(2);
			receipt.record(partialSubmission());
			return null;
		}).when(roomAppService).addAdmin(anyLong(), any(), any());
		doNothing().when(roomAppService).revokeAdmin(anyLong(), any(), any());

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);
			mockMvc.perform(put("/room/group/admin")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"roomId\":10,\"uidList\":[7]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data").doesNotExist())
					.andExpect(jsonPath("$.notificationSync.state").value("INCOMPLETE"));

			mockMvc.perform(delete("/room/group/admin")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"roomId\":10,\"uidList\":[7]}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data").doesNotExist())
					.andExpect(jsonPath("$.notificationSync").doesNotExist());
		}
	}

	@Test
	@DisplayName("停止期拒绝：目标未解析的回执不得被读成全部送达")
	void refusedSubmissionIsNotFullyAdmitted() {
		PushSubmission refused = PushSubmission.refused(List.of(UID), List.of(), "推送已停止外部准入（#451 有限排空）");
		assertNull(refused.getAdmission(), "未解析目标不能编造准入聚合");
		assertFalse(refused.isFullyAdmitted(), "停止期拒绝绝不能被读成全部被接收");
		assertFalse(PushSubmission.none().isFullyAdmitted(), "空快照按未全部接收处理");
		assertTrue(PushSubmission.resolved(PushSubmission.targetsOf("node-1", Map.of("client-1", UID))).isFullyAdmitted());
	}
}
