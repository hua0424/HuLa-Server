package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AI 节点在线注册
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNodeRegistryService {

	private final CachePlusOps cachePlusOps;

	public void online(AiNodeSessionMetadata metadata) {
		AiNodeSessionMetadata latest = copyWithLastSeen(metadata);
		cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiNodeOnline(latest.getNodeId()), latest);
		if (latest.getAiUserId() != null) {
			cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiUserNode(latest.getAiUserId()), latest.getNodeId());
			if (latest.getOwnerId() != null) {
				cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiUserOwner(latest.getAiUserId()), latest.getOwnerId());
			}
		}
		if (latest.getOwnerId() != null) {
			cachePlusOps.sAdd(AiNodeCacheKeyBuilder.buildOwnerNodes(latest.getOwnerId()), latest.getNodeId());
		}
	}

	public void touch(AiNodeSessionMetadata metadata) {
		online(metadata);
	}

	/**
	 * MVP 使用被动过期，不做主动删除
	 */
	public void offline(AiNodeSessionMetadata metadata) {
		log.info("AI节点断开，等待TTL被动过期: nodeId={}, connectionId={}", metadata.getNodeId(), metadata.getConnectionId());
	}

	public boolean isOnlineByAiUser(Long aiUserId) {
		if (aiUserId == null) {
			return false;
		}
		Optional<String> nodeIdOpt = getNodeIdByAiUser(aiUserId);
		if (nodeIdOpt.isEmpty()) {
			return false;
		}
		return Boolean.TRUE.equals(cachePlusOps.exists(AiNodeCacheKeyBuilder.buildAiNodeOnline(nodeIdOpt.get())));
	}

	public Optional<String> getNodeIdByAiUser(Long aiUserId) {
		CacheResult<String> result = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiUserNode(aiUserId));
		String nodeId = result == null ? null : result.asString();
		if (StrUtil.isBlank(nodeId)) {
			return Optional.empty();
		}
		return Optional.of(nodeId);
	}

	private AiNodeSessionMetadata copyWithLastSeen(AiNodeSessionMetadata source) {
		long now = System.currentTimeMillis();
		return AiNodeSessionMetadata.builder()
				.nodeId(source.getNodeId())
				.ownerId(source.getOwnerId())
				.mode(source.getMode())
				.aiUserId(source.getAiUserId())
				.serverInstanceId(source.getServerInstanceId())
				.connectionId(source.getConnectionId())
				.connectedAt(source.getConnectedAt())
				.lastSeen(now)
				.clientVersion(source.getClientVersion())
				.capabilities(source.getCapabilities())
				.build();
	}
}
