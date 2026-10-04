package com.luohuo.flex.im.controller.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.controller.ThinkingInternalAuth;
import com.luohuo.flex.im.domain.vo.request.MsgWindowReq;
import com.luohuo.flex.im.domain.vo.res.MsgWindowResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * aichatoverview#350：POST /chat/msg/window 的 envelope 契约测试。
 *
 * <p>standalone MockMvc（不起完整 Spring context）+ mock service：
 * 锁住路由、请求透传与 envelope 字段（schemaVersion/capabilities/回执独立性），
 * 不起 DB、不碰生产。旧接口兼容由既有测试覆盖。
 */
class ChatControllerMsgWindowTest {

    private static final Long UID = 1001L;
    private static final Long ROOM_ID = 10L;

    private ChatService chatService;
    private ChatController controller;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        controller = new ChatController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "chatService", chatService);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "userSummaryCache", mock(UserSummaryCache.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "userCache", mock(UserCache.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "thinkingInternalAuth", mock(ThinkingInternalAuth.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("window：envelope 透传，逐已知 ID 恰好一条回执且与范围 complete 独立")
    void windowEnvelopePassthrough() throws Exception {
        MsgWindowResp envelope = MsgWindowResp.builder()
                .schemaVersion(MsgWindowResp.SCHEMA_VERSION)
                .capabilities(MsgWindowResp.CAPABILITIES)
                .requestId("req-1")
                .roomId(ROOM_ID)
                .items(List.of())
                .coveredLower(null)
                .coveredUpper(null)
                .complete(true)
                .knownReceipts(List.of(
                        MsgWindowResp.KnownReceipt.builder().id("100").available(true).message(null).build(),
                        MsgWindowResp.KnownReceipt.builder().id("999").available(false).message(null).build()))
                .knownComplete(true)
                .messagesAccess(true)
                .messageMaxId(100L)
                .build();
        when(chatService.getMsgWindow(any(), eq(UID))).thenReturn(envelope);

        MsgWindowReq req = MsgWindowReq.builder()
                .roomId(ROOM_ID).requestId("req-1").mode("tail")
                .knownMsgIds(List.of("100", "999")).pageSize(20).build();
        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            mockMvc.perform(post("/chat/msg/window")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.schemaVersion").value("msg-window-v1"))
                    .andExpect(jsonPath("$.data.capabilities[0]").value("messages"))
                    .andExpect(jsonPath("$.data.capabilities[1]").value("known-receipts"))
                    .andExpect(jsonPath("$.data.requestId").value("req-1"))
                    .andExpect(jsonPath("$.data.complete").value(true))
                    .andExpect(jsonPath("$.data.knownReceipts.length()").value(2))
                    .andExpect(jsonPath("$.data.knownReceipts[0].available").value(true))
                    .andExpect(jsonPath("$.data.knownReceipts[1].available").value(false))
                    .andExpect(jsonPath("$.data.knownComplete").value(true))
                    .andExpect(jsonPath("$.data.messagesAccess").value(true));
        }
    }

    @Test
    @DisplayName("window：缺 roomId 时 400，不当失权或成功空")
    void windowMissingRoomIdIsBadRequest() throws Exception {
        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            mockMvc.perform(post("/chat/msg/window")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"tail\",\"pageSize\":20}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
