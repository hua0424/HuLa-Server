package com.luohuo.flex.im.controller.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * aichatoverview#40: ChatController#sendMsg 的优雅降级行为测试。
 *
 * <p>修复前：sendMsg 后 {@code chatService.getMsgResp(msgId, ...)} 在 getById 返 null 时
 * 经 singletonList(null) 触发下游 {@code Message::getId} NPE → HTTP 500。
 *
 * <p>修复后：getMsgResp 优雅返 null → {@code R.success(null)} → HTTP 200、data 为 null，
 * 不再抛异常。本测试锁住「controller 层把 getMsgResp 的 null 结果以 200 成功响应返回」这一降级契约。
 *
 * <p>采用 standalone MockMvc（不起完整 Spring context）+ Mockito mock service
 * + {@link MockedStatic} mock 静态 {@code ContextUtil.getUid()}，与同包
 * {@code AiclawGroupConfigControllerTest} 同款接线方式。
 */
class ChatControllerSendMsgTest {

	/** 认证身份 uid。 */
	private static final Long UID = 1001L;
	/** sendMsg 落库返回的 msgId。 */
	private static final Long MSG_ID = 999L;

	private ChatService chatService;
	private UserSummaryCache userSummaryCache;
	private MockMvc mockMvc;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		chatService = mock(ChatService.class);
		userSummaryCache = mock(UserSummaryCache.class);
		ChatController controller = new ChatController();
		// @Resource 字段注入 → 反射注入 mock（与 AiclawGroupConfigControllerTest 同款）。
		org.springframework.test.util.ReflectionTestUtils.setField(controller, "chatService", chatService);
		org.springframework.test.util.ReflectionTestUtils.setField(controller, "userSummaryCache", userSummaryCache);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	@DisplayName("sendMsg：getMsgResp 返 null → HTTP 200 + data 为 null（优雅降级，非 500）")
	void sendMsgGracefullyDegradesWhenGetMsgRespReturnsNull() throws Exception {
		// given：sendMsg 落库返回 msgId；但回查 getMsgResp 返 null（模拟 getById 找不到）。
		when(chatService.sendMsg(any(), eq(UID))).thenReturn(MSG_ID);
		when(chatService.getMsgResp(eq(MSG_ID), eq(UID))).thenReturn(null);

		ChatMessageReq req = ChatMessageReq.builder()
				.roomId(10L)
				.msgType(1)
				.body("hello")
				.build();
		String json = objectMapper.writeValueAsString(req);

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(UID);

			// then：200 + data 为 null，证明 R.success(null) 被框架优雅处理，而非 NPE→500。
			mockMvc.perform(post("/chat/msg")
							.contentType(MediaType.APPLICATION_JSON)
							.content(json))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data").value(nullValue()));
		}
	}
}
