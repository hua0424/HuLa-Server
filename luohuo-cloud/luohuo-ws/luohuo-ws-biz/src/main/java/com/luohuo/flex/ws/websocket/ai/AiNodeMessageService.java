package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 节点消息处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNodeMessageService {

	private final AiNodeRegistryService aiNodeRegistryService;

	public void handleMessage(AiNodeSessionMetadata metadata, String payload) {
		JSONObject json;
		try {
			json = JSONUtil.parseObj(payload);
		} catch (Exception ex) {
			log.warn("AI节点消息协议错误: nodeId={}, payload={}", metadata.getNodeId(), payload);
			return;
		}

		String type = json.getStr("type", "").trim();
		switch (type) {
			case "ai_heartbeat" -> aiNodeRegistryService.touch(metadata);
			case "ai_reply_chunk" -> {
				aiNodeRegistryService.touch(metadata);
				log.debug("收到AI流式分片: nodeId={}, requestId={}, seq={}, isFinal={}",
						metadata.getNodeId(), json.getStr("requestId"), json.getInt("seq"), json.getBool("isFinal"));
				// TODO: Phase 1.2 将 ai_reply_chunk 路由回 IM 推送链路
			}
			case "ai_error" -> {
				aiNodeRegistryService.touch(metadata);
				log.warn("AI节点执行错误: nodeId={}, requestId={}, code={}, msg={}",
						metadata.getNodeId(), json.getStr("requestId"), json.getStr("code"), json.getStr("message"));
			}
			default -> log.warn("未知AI消息类型: nodeId={}, type={}", metadata.getNodeId(), type);
		}
	}
}
