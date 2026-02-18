package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.StrUtil;
import com.luohuo.flex.model.entity.dto.AiNodeFinalReplyDTO;
import com.luohuo.flex.model.entity.dto.AiNodeRequestDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 请求上下文与分片聚合跟踪
 */
@Slf4j
@Service
public class AiNodeRequestTrackerService {

	private final ConcurrentHashMap<String, AiNodeRequestDTO> requestMap = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, StringBuilder> chunkBufferMap = new ConcurrentHashMap<>();

	public void register(AiNodeRequestDTO requestDTO) {
		requestMap.put(requestDTO.getRequestId(), requestDTO);
		chunkBufferMap.put(requestDTO.getRequestId(), new StringBuilder());
	}

	public AiNodeFinalReplyDTO appendChunk(String requestId, String content, boolean isFinal, String nodeId) {
		AiNodeRequestDTO req = requestMap.get(requestId);
		if (req == null) {
			return null;
		}
		if (StrUtil.isNotEmpty(content)) {
			chunkBufferMap.computeIfAbsent(requestId, k -> new StringBuilder()).append(content);
		}
		if (!isFinal) {
			return null;
		}

		String finalText = chunkBufferMap.getOrDefault(requestId, new StringBuilder()).toString();
		remove(requestId);

		AiNodeFinalReplyDTO reply = new AiNodeFinalReplyDTO();
		reply.setTenantId(req.getTenantId());
		reply.setRequestId(requestId);
		reply.setMsgId(req.getMsgId());
		reply.setRoomId(req.getRoomId());
		reply.setToUserId(req.getFromUserId());
		reply.setFromUserId(req.getFromUserId());
		reply.setAiUserId(req.getAiUserId());
		reply.setNodeId(nodeId);
		reply.setContent(finalText);
		reply.setOriginalText(req.getOriginalText());
		reply.setFinalText(req.getFinalText());
		reply.setTimestamp(System.currentTimeMillis());
		return reply;
	}

	public void remove(String requestId) {
		requestMap.remove(requestId);
		chunkBufferMap.remove(requestId);
	}

	@Scheduled(fixedRate = 60000)
	public void cleanupTimeoutRequests() {
		long now = System.currentTimeMillis();
		long timeoutThreshold = 180000;
		Iterator<Map.Entry<String, AiNodeRequestDTO>> iterator = requestMap.entrySet().iterator();
		int cleaned = 0;
		while (iterator.hasNext()) {
			Map.Entry<String, AiNodeRequestDTO> entry = iterator.next();
			AiNodeRequestDTO req = entry.getValue();
			if (req.getTimestamp() != null && now - req.getTimestamp() > timeoutThreshold) {
				iterator.remove();
				chunkBufferMap.remove(entry.getKey());
				cleaned++;
			}
		}
		if (cleaned > 0) {
			log.info("清理超时AI请求: {} 条", cleaned);
		}
	}
}
