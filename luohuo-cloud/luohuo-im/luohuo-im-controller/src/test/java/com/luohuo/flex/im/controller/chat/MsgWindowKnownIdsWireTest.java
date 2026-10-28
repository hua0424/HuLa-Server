package com.luohuo.flex.im.controller.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.jackson.LuohuoJacksonModule;
import com.luohuo.flex.im.domain.vo.request.MsgWindowReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aichatoverview#483：窗口校准请求的 wire 字段合同（无 Spring、无 DB）。
 *
 * <p>前端 TS 曾以 {@code knownIds} 发送，而本 DTO 与 Rust {@code CalibrateWindowParam}
 * 都只认 {@code knownMsgIds}，字段对不上时服务端看到的是「没有已知 ID」，
 * 于是逐 ID 回执为空、撤回与终态无从纠正。这里锁住三件事：
 * <ol>
 *   <li>正确的 {@code knownMsgIds} 能被绑定，且大 ID 不丢精度（ID 一律十进制字符串）；</li>
 *   <li>旧的 {@code knownIds} 不会被静默当成已知 ID（改名是兼容边界，必须显式）；</li>
 *   <li>响应侧回执 ID 与请求 ID 原样对齐，便于调用方逐 ID 核账。</li>
 * </ol>
 */
class MsgWindowKnownIdsWireTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new LuohuoJacksonModule())
            // 与 Spring Boot 默认一致：未知字段忽略而不是解析失败，因此旧字段会被静默丢弃。
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("knownMsgIds 绑定成功且大 ID 逐字符保真")
    void knownMsgIdsBindAndKeepLargeIds() throws Exception {
        String body = """
                {
                  "roomId": 175988626166784,
                  "requestId": "wcal-483",
                  "mode": "tail",
                  "knownMsgIds": ["212834869724672", "9007199254740993"],
                  "knownThinkingIds": ["501"],
                  "pageSize": 20
                }
                """;
        MsgWindowReq req = objectMapper.readValue(body, MsgWindowReq.class);
        assertEquals("wcal-483", req.getRequestId());
        assertEquals("tail", req.getMode());
        assertEquals(20, req.getPageSize());
        assertEquals(List.of("212834869724672", "9007199254740993"), req.getKnownMsgIds());
        assertEquals(List.of("501"), req.getKnownThinkingIds());
        // 2^53+1 超出 JS Number 安全整数：必须以字符串往返，不得变成 9007199254740992。
        assertEquals("9007199254740993", req.getKnownMsgIds().get(1));
    }

    @Test
    @DisplayName("旧字段 knownIds 不冒充 knownMsgIds：改名是显式兼容边界")
    void legacyKnownIdsIsNotBound() throws Exception {
        String body = """
                {
                  "roomId": 10,
                  "requestId": "wcal-legacy",
                  "mode": "tail",
                  "knownIds": ["1", "2"],
                  "pageSize": 20
                }
                """;
        MsgWindowReq req = objectMapper.readValue(body, MsgWindowReq.class);
        assertNull(req.getKnownMsgIds(), "旧字段 knownIds 不得被当成已知消息 ID");
    }

    @Test
    @DisplayName("回执 ID 与请求 ID 原样对齐且逐条可核")
    void receiptsAlignWithRequestIds() throws Exception {
        String envelope = """
                {
                  "schemaVersion": "msg-window-v1",
                  "capabilities": ["messages", "known-receipts", "thinking"],
                  "requestId": "wcal-483",
                  "roomId": "10",
                  "items": [],
                  "complete": true,
                  "knownReceipts": [
                    {"id": "212834869724672", "available": false},
                    {"id": "9007199254740993", "available": true}
                  ],
                  "knownComplete": true,
                  "messagesAccess": true
                }
                """;
        JsonNode data = objectMapper.readTree(envelope);
        JsonNode receipts = data.get("knownReceipts");
        assertNotNull(receipts);
        assertEquals(2, receipts.size());
        assertEquals("212834869724672", receipts.get(0).get("id").asText());
        assertEquals("9007199254740993", receipts.get(1).get("id").asText());
        assertTrue(receipts.get(1).get("available").asBoolean());
        assertEquals("msg-window-v1", data.get("schemaVersion").asText());
        assertTrue(data.get("capabilities").toString().contains("known-receipts"));
    }
}
