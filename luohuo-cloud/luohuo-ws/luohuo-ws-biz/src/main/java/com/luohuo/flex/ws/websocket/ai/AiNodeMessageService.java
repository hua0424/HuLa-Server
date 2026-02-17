package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.dto.AiNodeFinalReplyDTO;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * AI 节点消息处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNodeMessageService {

	private final AiNodeRegistryService aiNodeRegistryService;
	private final AiNodeRequestTrackerService aiNodeRequestTrackerService;
	private final AiNodeSessionManager aiNodeSessionManager;
	private final RocketMQTemplate rocketMQTemplate;
	private final CachePlusOps cachePlusOps;

	public void handleMessage(AiNodeSessionMetadata metadata, String payload) {
		JSONObject json;
		try {
			json = JSONUtil.parseObj(payload);
		} catch (Exception ex) {
			log.warn("[AI-LINK] event=ai_protocol_invalid, nodeId={}, reason=invalid_json, payload={}",
					metadata.getNodeId(), payload);
			sendErrorFrame(metadata, "AI_PROTOCOL_INVALID", "Invalid JSON format");
			return;
		}

		String type = json.getStr("type", "").trim();
		switch (type) {
			case "ai_heartbeat" -> aiNodeRegistryService.touch(metadata);
			case "ai_reply_chunk" -> {
				aiNodeRegistryService.touch(metadata);
				String requestId = json.getStr("requestId");
				boolean isFinal = Boolean.TRUE.equals(json.getBool("isFinal"));
				String content = json.getStr("content", "");
				log.debug("[AI-LINK] event=ai_reply_chunk, nodeId={}, requestId={}, seq={}, isFinal={}",
						metadata.getNodeId(), requestId, json.getInt("seq"), isFinal);
				AiNodeFinalReplyDTO reply = aiNodeRequestTrackerService.appendChunk(requestId, content, isFinal, metadata.getNodeId());
				if (reply != null) {
					Boolean exists = cachePlusOps.exists(AiNodeCacheKeyBuilder.buildAiReplyDedup(requestId));
					if (Boolean.TRUE.equals(exists)) {
						log.warn("[AI-LINK] event=ai_reply_duplicate, requestId={}", requestId);
						return;
					}
					cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiReplyDedup(requestId), "1");
					rocketMQTemplate.send(MqConstant.AI_NODE_REPLY_TOPIC,
							MessageBuilder.withPayload(reply).build());
					log.info("[AI-LINK] event=ai_reply_flow_back, requestId={}, roomId={}, toUserId={}",
							reply.getRequestId(), reply.getRoomId(), reply.getToUserId());
				}
			}
			case "ai_error" -> {
				aiNodeRegistryService.touch(metadata);
				String requestId = json.getStr("requestId");
				aiNodeRequestTrackerService.remove(requestId);
				log.warn("[AI-LINK] event=ai_node_error, nodeId={}, requestId={}, code={}, msg={}",
						metadata.getNodeId(), requestId, json.getStr("code"), json.getStr("message"));
			}
			default -> {
				log.warn("[AI-LINK] event=ai_unknown_message_type, nodeId={}, type={}", metadata.getNodeId(), type);
				sendErrorFrame(metadata, "AI_PROTOCOL_INVALID", "Unknown message type: " + type);
			}
		}
	}

	/**
	 * 发送错误帧给 AI 节点
	 */
	private void sendErrorFrame(AiNodeSessionMetadata metadata, String code, String message) {
		if (metadata == null || metadata.getNodeId() == null) {
			return;
		}
		String errorPayload = String.format(
				"{\"type\":\"ai_error\",\"code\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
				code, message, System.currentTimeMillis());
		aiNodeSessionManager.sendToNode(metadata.getNodeId(), errorPayload).subscribe();
	}
}
