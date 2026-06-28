package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.CcAiclawResolveService;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.CcAiclawResolveResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REQ-010 S9: CC aiclaw 解析 + owner 鉴权实现。
 *
 * <p>成员枚举复用全仓既有通道（群聊 {@code RoomGroupCache + GroupMemberDao}，
 * 单聊 {@code RoomFriendDao}），与 {@code RoomAppServiceImpl.aiclawListMembers}
 * 和 {@code ThinkingController.getRoomMembers} 同源。</p>
 *
 * @author developer
 */
@Slf4j
@Service
public class CcAiclawResolveServiceImpl implements CcAiclawResolveService {

	/** node 侧 CcDriver 的 tool 名（reportAgentType 落库到 im_aiclaw.adapter_type）。 */
	private static final String CC_ADAPTER_TYPE = "cc";

	@Resource
	private RoomCache roomCache;
	@Resource
	private RoomGroupCache roomGroupCache;
	@Resource
	private GroupMemberDao groupMemberDao;
	@Resource
	private RoomFriendDao roomFriendDao;
	@Resource
	private AiclawDao aiclawDao;

	@Override
	public CcAiclawResolveResp resolveCcAiclawForRoom(Long roomId, Long requesterUid, Long uid) {
		// 1. 房间须存在
		Room room = roomCache.get(roomId);
		if (room == null) {
			throw new BizException("房间不存在");
		}
		Integer roomType = room.getType();

		// 2. 枚举房间成员 + 单聊对端
		List<Long> memberUids;
		Long counterpartUid = null;
		if (room.isRoomGroup()) {
			RoomGroup roomGroup = roomGroupCache.get(roomId);
			if (roomGroup == null || roomGroup.getId() == null) {
				throw new BizException("群聊不存在");
			}
			memberUids = groupMemberDao.getMemberUidList(roomGroup.getId(), null);
		} else if (RoomTypeEnum.of(roomType) == RoomTypeEnum.FRIEND) {
			RoomFriend friend = roomFriendDao.getByRoomId(roomId);
			if (friend == null) {
				throw new BizException("会话不存在");
			}
			memberUids = new ArrayList<>(List.of(friend.getUid1(), friend.getUid2()));
		} else {
			throw new BizException("不支持的房间类型");
		}

		// 3. 在成员中筛出 CC aiclaw（adapter_type = cc）
		List<Aiclaw> ccAiclaws = aiclawDao.listByUids(memberUids).stream()
				.filter(a -> CC_ADAPTER_TYPE.equalsIgnoreCase(a.getAdapterType()))
				.collect(Collectors.toList());

		if (ccAiclaws.isEmpty()) {
			return CcAiclawResolveResp.builder().ok(false).errorCode("NO_CC").build();
		}

		// 4. 消歧：多个 CC 助理时必须指定 uid
		Aiclaw target;
		if (ccAiclaws.size() == 1 && uid == null) {
			target = ccAiclaws.get(0);
		} else {
			if (uid == null) {
				return CcAiclawResolveResp.builder().ok(false).errorCode("AMBIGUOUS").build();
			}
			target = ccAiclaws.stream()
					.filter(a -> uid.equals(a.getUid()))
					.findFirst()
					.orElse(null);
			if (target == null) {
				// 指定 uid 不是该房间的 CC 助理（不在房间 / 不是 CC 类型）
				return CcAiclawResolveResp.builder().ok(false).errorCode("UID_NOT_CC").build();
			}
		}

		// 5. owner 鉴权：requester 必须是该 CC aiclaw 的 owner_uid
		if (target.getOwnerUid() == null || !target.getOwnerUid().equals(requesterUid)) {
			return CcAiclawResolveResp.builder().ok(false).errorCode("NOT_OWNER").build();
		}

		// 6. 单聊对端 = 成员中非 aiclaw 的那一方（用于 node 推导工作区/会话）
		if (RoomTypeEnum.of(roomType) == RoomTypeEnum.FRIEND) {
			counterpartUid = memberUids.stream()
					.filter(m -> !m.equals(target.getUid()))
					.findFirst()
					.orElse(null);
		}

		return CcAiclawResolveResp.builder()
				.ok(true)
				.aiclawUid(target.getUid())
				.roomType(roomType)
				.counterpartUid(counterpartUid)
				.build();
	}
}
