package com.luohuo.flex.ws.consumer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.dto.AiNodeRequestDTO;
import com.luohuo.flex.ws.websocket.ai.AiNodeRegistryService;
import com.luohuo.flex.ws.websocket.ai.AiNodeRequestTrackerService;
import com.luohuo.flex.ws.websocket.ai.AiNodeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM -> WS 的 AI 请求路由消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstant.AI_NODE_REQUEST_TOPIC,
		consumerGroup = MqConstant.AI_NODE_REQUEST_TOPIC_GROUP,
		messageModel = MessageModel.CLUSTERING)
public class AiNodeRequestConsumer implements RocketMQListener<AiNodeRequestDTO> {

	private final AiNodeSessionManager aiNodeSessionManager;
	private final AiNodeRegistryService aiNodeRegistryService;
	private final AiNodeRequestTrackerService aiNodeRequestTrackerService;

	@Override
	public void onMessage(AiNodeRequestDTO dto) {
		if (dto == null || StrUtil.isBlank(dto.getRequestId())) {
			return;
		}
		final String nodeId;
		if (StrUtil.isNotBlank(dto.getToNodeId())) {
			nodeId = dto.getToNodeId();
		} else {
			nodeId = aiNodeRegistryService.getNodeIdByAiUser(dto.getAiUserId()).orElse(null);
		}
		if (StrUtil.isBlank(nodeId)) {
			log.warn("AI请求路由失败，未找到在线节点: requestId={}, aiUserId={}", dto.getRequestId(), dto.getAiUserId());
			return;
		}
		if (aiNodeSessionManager.getSession(nodeId).isEmpty()) {
			log.warn("AI请求路由失败，节点会话不存在: requestId={}, nodeId={}", dto.getRequestId(), nodeId);
			return;
		}

		dto.setToNodeId(nodeId);
		aiNodeRequestTrackerService.register(dto);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "ai_request");
		payload.put("requestId", dto.getRequestId());
		payload.put("msgId", dto.getMsgId());
		payload.put("fromUserId", String.valueOf(dto.getFromUserId()));
		payload.put("toNodeId", nodeId);
		payload.put("roomId", dto.getRoomId());
		payload.put("content", dto.getContent());
		payload.put("timestamp", System.currentTimeMillis());

		final String finalNodeId = nodeId;
		String text = JSONUtil.toJsonStr(payload);
		aiNodeSessionManager.sendToNode(nodeId, text)
				.doOnError(ex -> {
					aiNodeRequestTrackerService.remove(dto.getRequestId());
					log.error("AI请求发送失败: requestId={}, nodeId={}", dto.getRequestId(), finalNodeId, ex);
				})
				.subscribe();
	}
}
