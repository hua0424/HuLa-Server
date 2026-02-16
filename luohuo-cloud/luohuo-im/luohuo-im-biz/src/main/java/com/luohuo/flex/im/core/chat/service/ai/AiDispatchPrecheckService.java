package com.luohuo.flex.im.core.chat.service.ai;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.exception.BizException;
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

	/**
	 * 仅对 AI 单聊做可达性预检，离线直接失败
	 */
	public void preCheck(Long roomId, Long senderUid) {
		Room room = roomCache.get(roomId);
		if (room == null || !room.isRoomFriend()) {
			return;
		}

		RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
		if (roomFriend == null) {
			return;
		}

		Long targetUid = resolveTargetUid(roomFriend, senderUid);
		if (targetUid == null) {
			return;
		}

		User targetUser = userDao.getById(targetUid);
		if (targetUser == null || !UserTypeEnum.BOT.getValue().equals(targetUser.getUserType())) {
			return;
		}

		CacheResult<String> nodeResult = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiUserNode(targetUid));
		String nodeId = nodeResult == null ? null : nodeResult.asString();
		if (StrUtil.isBlank(nodeId)) {
			throw BizException.wrap(503, "AI_NODE_OFFLINE");
		}

		boolean online = Boolean.TRUE.equals(cachePlusOps.exists(AiNodeCacheKeyBuilder.buildAiNodeOnline(nodeId)));
		if (!online) {
			log.warn("AI节点离线: targetUid={}, nodeId={}", targetUid, nodeId);
			throw BizException.wrap(503, "AI_NODE_OFFLINE");
		}

		Long ownerUid = resolveOwnerUid(targetUid, nodeId);
		aiApprovalService.ensureAccess(targetUid, ownerUid, senderUid);
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
