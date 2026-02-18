package com.luohuo.flex.im.core.chat.service.ai;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ai.approval.AiApprovalService;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 消息发送前预检
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDispatchPrecheckService {

	private final RoomCache roomCache;
	private final RoomFriendDao roomFriendDao;
	private final UserDao userDao;
	private final CachePlusOps cachePlusOps;
	private final AiApprovalService aiApprovalService;
	private final AiRateLimiterService aiRateLimiterService;

	/**
	 * 仅对 AI 单聊做可达性预检，离线直接失败
	 * @return 节点ID，用于后续请求投递
	 */
	public String preCheck(Long roomId, Long senderUid, String originalText) {
		Room room = roomCache.get(roomId);
		if (room == null || !room.isRoomFriend()) {
			return null;
		}

		RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
		if (roomFriend == null) {
			return null;
		}

		Long targetUid = resolveTargetUid(roomFriend, senderUid);
		if (targetUid == null) {
			return null;
		}

		User targetUser = userDao.getById(targetUid);
		if (targetUser == null || !UserTypeEnum.BOT.getValue().equals(targetUser.getUserType())) {
			return null;
		}

		CacheResult<String> nodeResult = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiUserNode(targetUid));
		String nodeId = nodeResult == null ? null : nodeResult.asString();
		if (StrUtil.isBlank(nodeId)) {
			log.warn("[AI-LINK] event=ai_node_offline, targetUid={}, nodeId=null", targetUid);
			AiErrorCodeEnum.AI_NODE_OFFLINE.throwEx();
		}

		boolean online = Boolean.TRUE.equals(cachePlusOps.exists(AiNodeCacheKeyBuilder.buildAiNodeOnline(nodeId)));
		if (!online) {
			log.warn("[AI-LINK] event=ai_node_offline, targetUid={}, nodeId={}", targetUid, nodeId);
			AiErrorCodeEnum.AI_NODE_OFFLINE.throwEx();
		}

		Long ownerUid = resolveOwnerUid(targetUid, nodeId);
		// 审批检查
		aiApprovalService.ensureAccess(targetUid, ownerUid, senderUid, originalText);

		// 限流检查（在审批通过后）
		aiRateLimiterService.checkRateLimit(senderUid, nodeId, targetUid);

		log.info("[AI-LINK] event=precheck_pass, roomId={}, senderUid={}, targetUid={}, nodeId={}",
				roomId, senderUid, targetUid, nodeId);
		return nodeId;
	}

	private Long resolveOwnerUid(Long aiUserId, String nodeId) {
		CacheResult<Long> ownerResult = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiUserOwner(aiUserId));
		Long ownerUid = ownerResult == null ? null : ownerResult.getValue();
		if (ownerUid != null) {
			return ownerUid;
		}

		CacheResult<Object> onlineMetaResult = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiNodeOnline(nodeId));
		Object onlineMeta = onlineMetaResult == null ? null : onlineMetaResult.getValue();
		if (onlineMeta instanceof Map<?, ?> map) {
			Object ownerVal = map.get("ownerId");
			if (ownerVal != null) {
				try {
					return Long.parseLong(String.valueOf(ownerVal));
				} catch (NumberFormatException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private Long resolveTargetUid(RoomFriend roomFriend, Long senderUid) {
		if (senderUid.equals(roomFriend.getUid1())) {
			return roomFriend.getUid2();
		}
		if (senderUid.equals(roomFriend.getUid2())) {
			return roomFriend.getUid1();
		}
		return null;
	}
}
