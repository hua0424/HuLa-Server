package com.luohuo.flex.im.core.chat.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3-1 #42 序列化契约（manager 要求）：
 * <ul>
 *   <li>clientMsgId=null → JSON 必须省略该字段（@JsonInclude NON_NULL），不污染既有响应</li>
 *   <li>clientMsgId 有值 → JSON 必须回带</li>
 *   <li>老客户端/插件发来省略 clientMsgId 的 JSON → 反序列化不报错、字段为 null（向后兼容）</li>
 * </ul>
 */
class ChatMessageRespSerializationTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("F3-1: clientMsgId=null → JSON 省略 clientMsgId 字段")
	void nullClientMsgIdOmittedFromJson() throws Exception {
		ChatMessageResp.Message message = new ChatMessageResp.Message();
		message.setClientMsgId(null);

		String json = mapper.writeValueAsString(message);

		assertFalse(json.contains("clientMsgId"),
				"clientMsgId 为 null 时应被 NON_NULL 省略，实际 JSON=" + json);
	}

	@Test
	@DisplayName("F3-1: clientMsgId 有值 → JSON 回带 \"clientMsgId\":\"T-x\"")
	void presentClientMsgIdSerialized() throws Exception {
		ChatMessageResp.Message message = new ChatMessageResp.Message();
		message.setClientMsgId("T-x");

		String json = mapper.writeValueAsString(message);

		assertTrue(json.contains("\"clientMsgId\":\"T-x\""),
				"clientMsgId 有值时应出现在 JSON，实际 JSON=" + json);
	}

	@Test
	@DisplayName("F3-1: 反序列化省略 clientMsgId 的 JSON → 不报错、字段为 null（向后兼容）")
	void deserializeWithoutClientMsgIdIsBackwardCompatible() throws Exception {
		String legacyJson = "{\"id\":\"1\",\"roomId\":\"2\",\"type\":1}";

		ChatMessageResp.Message message = mapper.readValue(legacyJson, ChatMessageResp.Message.class);

		assertNull(message.getClientMsgId(), "省略 clientMsgId 的旧 JSON 反序列化后字段应为 null");
	}
}
