package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.AiclawRoomMembershipService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * aichatoverview#3: aiclaw 房间成员校验实现。
 * 数据源统一为 GroupMemberCache + RoomFriendDao（与全仓一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiclawRoomMembershipServiceImpl implements AiclawRoomMembershipService {

	private final RoomCache roomCache;
	private final GroupMemberCache groupMemberCache;
	private final RoomFriendDao roomFriendDao;

	@Override
	public void checkMembership(Long aiclawUid, Long roomId) {
		Room room = roomCache.get(roomId);
		if (room == null) {
			// 房间不存在，无法校验成员关系，拒绝
			throw new BizException("房间不存在，无法校验成员身份");
		}

		if (room.isRoomGroup()) {
			checkGroupMembership(aiclawUid, roomId);
		} else if (room.isRoomFriend()) {
			checkFriendMembership(aiclawUid, roomId);
		} else {
			// 白名单思维：未知房间类型对 aiclaw 明确拒绝
			log.warn("aiclaw attempted to access unknown room type: aiclawUid={}, roomId={}, roomType={}",
					aiclawUid, roomId, room.getType());
			throw new BizException("不支持的房间类型，aiclaw 无法发送消息");
		}
	}

	/**
	 * 群聊成员校验。
	 * 空列表语义：GroupMemberCache 在 RoomGroup 记录缺失时返回空列表，属数据异常
	 * （正常群聊至少含群主，故空列表 ⇔ 房间缺失/损坏）。
	 */
	private void checkGroupMembership(Long aiclawUid, Long roomId) {
		List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
		if (memberUids.isEmpty()) {
			// 群聊数据异常（RoomGroup 记录缺失），与「非成员」语义分离
			log.error("aiclaw room membership check: group data missing, aiclawUid={}, roomId={}", aiclawUid, roomId);
			throw new BizException("群聊数据异常，无法校验成员身份");
		}
		if (!memberUids.contains(aiclawUid)) {
			throw new BizException("非房间成员，无法发送消息");
		}
	}

	/**
	 * 私聊成员校验。
	 */
	private void checkFriendMembership(Long aiclawUid, Long roomId) {
		RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
		if (roomFriend == null) {
			throw new BizException("私聊数据异常，无法校验成员身份");
		}
		boolean isMember = aiclawUid.equals(roomFriend.getUid1()) || aiclawUid.equals(roomFriend.getUid2());
		if (!isMember) {
			throw new BizException("非房间成员，无法发送消息");
		}
	}
}
