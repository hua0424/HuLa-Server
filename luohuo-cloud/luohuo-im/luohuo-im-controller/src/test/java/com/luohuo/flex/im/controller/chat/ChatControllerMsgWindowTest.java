package com.luohuo.flex.im.controller.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.jackson.LuohuoJacksonModule;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.controller.ThinkingInternalAuth;
import com.luohuo.flex.im.domain.vo.request.MsgWindowReq;
import com.luohuo.flex.im.domain.vo.res.MsgWindowResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    @DisplayName("window#350 wire-compat：非空 bounds 经全局 Long→String 模块后 timeMs 仍为数字")
    void windowNonEmptyBoundsTimeMsIsNumeric() throws Exception {
        // 真实包体值（verify wire3.json）：此前 coveredLower/Upper 全为 null，从未覆盖；
        // 全局 LuohuoJacksonModule 把 Long（含 timeMs）转字符串，导致 Rust Option<i64> 解码失败。
        MsgWindowResp envelope = MsgWindowResp.builder()
                .schemaVersion(MsgWindowResp.SCHEMA_VERSION)
                .capabilities(MsgWindowResp.CAPABILITIES)
                .requestId("wcal-wire3")
                .roomId(175988626166784L)
                .items(List.of())
                .coveredLower(MsgWindowResp.WindowBound.builder()
                        .timeMs(1791070187188L).id("212834869724672").build())
                .coveredUpper(MsgWindowResp.WindowBound.builder()
                        .timeMs(1791112189184L).id("213011038881280").build())
                .complete(false)
                .knownReceipts(List.of())
                .knownComplete(true)
                .messagesAccess(true)
                .messageMaxId(213062402328064L)
                .build();
        ObjectMapper wireMapper = new ObjectMapper().registerModule(new LuohuoJacksonModule());
        JsonNode data = wireMapper.valueToTree(envelope);
        // timeMs 必须数字（与 send_time 同源）；ID 类保持字符串（JS 精度保护）。
        assertTrue(data.get("coveredLower").get("timeMs").isNumber(),
                "coveredLower.timeMs 应为数字，实际=" + data.get("coveredLower").get("timeMs"));
        assertTrue(data.get("coveredUpper").get("timeMs").isNumber(),
                "coveredUpper.timeMs 应为数字，实际=" + data.get("coveredUpper").get("timeMs"));
        assertEquals(1791070187188L, data.get("coveredLower").get("timeMs").asLong());
        assertEquals(1791112189184L, data.get("coveredUpper").get("timeMs").asLong());
        assertTrue(data.get("roomId").isTextual(), "roomId 保持字符串，实际=" + data.get("roomId"));
        assertTrue(data.get("messageMaxId").isTextual(),
                "messageMaxId 保持字符串，实际=" + data.get("messageMaxId"));
    }

    @Test
    @DisplayName("window#351：思考 envelope 透传——同触发多助理归属与顺序、逐已知思考 ID 回执、bodyETag")
    void windowThinkingEnvelopePassthrough() throws Exception {
        AiclawThinkingListItemResp first = AiclawThinkingListItemResp.builder()
                .id(501L).aiclawUid(100L).triggerMsgId(7001L).status(1).durationMs(120)
                .hasResponse(1).createTime(java.time.LocalDateTime.of(2026, 10, 4, 12, 0, 0))
                .bodyETag("etag-body-501").build();
        AiclawThinkingListItemResp second = AiclawThinkingListItemResp.builder()
                .id(502L).aiclawUid(200L).triggerMsgId(7001L).status(1).durationMs(130)
                .hasResponse(0).createTime(java.time.LocalDateTime.of(2026, 10, 4, 12, 0, 1))
                .bodyETag("etag-body-502").build();
        MsgWindowResp envelope = MsgWindowResp.builder()
                .schemaVersion(MsgWindowResp.SCHEMA_VERSION)
                .capabilities(MsgWindowResp.CAPABILITIES)
                .requestId("req-thinking")
                .roomId(ROOM_ID)
                .items(List.of())
                .coveredLower(null)
                .coveredUpper(null)
                .complete(true)
                .knownReceipts(List.of())
                .knownComplete(true)
                .messagesAccess(true)
                .messageMaxId(100L)
                .thinkingAccess(true)
                .thinkingTriggers(List.of("7001"))
                .thinkingItems(List.of(first, second))
                .thinkingComplete(true)
                .thinkingKnownReceipts(List.of(
                        MsgWindowResp.ThinkingKnownReceipt.builder()
                                .id("501").available(true).metadata(first).build(),
                        MsgWindowResp.ThinkingKnownReceipt.builder()
                                .id("999").available(false).metadata(null).build()))
                .thinkingKnownComplete(true)
                .build();
        when(chatService.getMsgWindow(any(), eq(UID))).thenReturn(envelope);

        MsgWindowReq req = MsgWindowReq.builder()
                .roomId(ROOM_ID).requestId("req-thinking").mode("tail")
                .knownMsgIds(List.of()).knownThinkingIds(List.of("501", "999")).pageSize(20).build();
        try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
            ctx.when(ContextUtil::getUid).thenReturn(UID);
            mockMvc.perform(post("/chat/msg/window")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.capabilities[2]").value("thinking"))
                    .andExpect(jsonPath("$.data.thinkingAccess").value(true))
                    .andExpect(jsonPath("$.data.thinkingTriggers[0]").value("7001"))
                    .andExpect(jsonPath("$.data.thinkingItems.length()").value(2))
                    // 同触发多助理：归属不同 aiclawUid，id 升序。
                    .andExpect(jsonPath("$.data.thinkingItems[0].aiclawUid").value("100"))
                    .andExpect(jsonPath("$.data.thinkingItems[1].aiclawUid").value("200"))
                    .andExpect(jsonPath("$.data.thinkingItems[0].bodyETag").value("etag-body-501"))
                    .andExpect(jsonPath("$.data.thinkingComplete").value(true))
                    .andExpect(jsonPath("$.data.thinkingKnownReceipts.length()").value(2))
                    .andExpect(jsonPath("$.data.thinkingKnownReceipts[0].available").value(true))
                    .andExpect(jsonPath("$.data.thinkingKnownReceipts[0].metadata.bodyETag").value("etag-body-501"))
                    .andExpect(jsonPath("$.data.thinkingKnownReceipts[1].available").value(false))
                    .andExpect(jsonPath("$.data.thinkingKnownComplete").value(true));
        }
    }
}
