package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.im.common.event.GroupMemberAddEvent;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.adapter.MemberAdapter;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.AiclawFriendExtDao;
import com.luohuo.flex.im.core.user.service.NoticeService;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawFriendExt;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.NoticeTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * aichatoverview#169: {@link AiclawParticipant} 实现——把 aiclaw 特判从通用 IM 路径收拢至此。
 *
 * <p>本类仅委派既有封装（{@code AiclawGroupConfigService} 审批门控 + SETNX 去重、{@code AiclawOwnerCache}
 * owner 缓存、{@code AiclawDao}/{@code AiclawFriendExtDao} 读），不重写任何门控逻辑；被搬入的
 * {@code batchAddAiclawMembers}、{@code findAiclawUid}、{@code fillAiclawExt} 逐字保留原行为
 * （含 #153 P0-1 通知失败补偿回滚、#153 P1 踢/退清标记语义）。
 *
 * <p>注入 {@link ChatService} 无循环依赖：本类与其依赖（含 {@code ChatServiceImpl}、
 * {@code AiclawGroupConfigServiceImpl}）都不反向依赖 {@code AiclawParticipant}。
 */
@Slf4j
@Service
@AllArgsConstructor
public class AiclawParticipantImpl implements AiclawParticipant {

	private final UserSummaryCache userSummaryCache;
	private final AiclawGroupConfigService aiclawGroupConfigService;
	private final RoomFriendDao roomFriendDao;
	private final ChatService chatService;
	private final AiclawDao aiclawDao;
	private final AiclawFriendExtDao aiclawFriendExtDao;
	private final AiclawOwnerCache aiclawOwnerCache;
	private final GroupMemberDao groupMemberDao;
	private final GroupMemberCache groupMemberCache;
	private final CachePlusOps cachePlusOps;
	private final TransactionTemplate transactionTemplate;
	private final NoticeService noticeService;

	@Override
	public boolean isAiclaw(Long uid) {
		SummeryInfoDTO info = userSummaryCache.get(uid);
		return info != null && Objects.equals(info.getUserType(), UserTypeEnum.AICLAW.getValue());
	}

	@Override
	public List<Long> filterGroupRecipients(List<Long> memberUids, Long roomId) {
		// REQ-009#84 (ADR-0002): 委派既有门控，剔除「未批准」的 aiclaw 收件人。私聊不受此门控约束。
		return aiclawGroupConfigService.filterUnapprovedAiclawRecipients(memberUids, roomId);
	}

	@Override
	public Optional<DirectChatDelivery> resolveDirectChatDelivery(Message message, Room room, MsgSendMessageDTO dto) {
		// 仅单聊场景做 aiclaw 定向富化；群聊/其它一律无（保持通用路径原样推送）。
		if (!Objects.equals(room.getType(), RoomTypeEnum.FRIEND.getType())) {
			return Optional.empty();
		}
		RoomFriend rf = roomFriendDao.getByRoomId(room.getId());
		Long aiclawUid = findAiclawUid(rf.getUid1(), rf.getUid2());
		if (aiclawUid == null) {
			return Optional.empty();
		}
		Long senderUid = message.getFromUid();
		// 为 aiclaw 构建带扩展字段的 payload（全新 getMsgResp，避免与基础 payload 共享引用串味）
		ChatMessageResp aiclawResp = chatService.getMsgResp(message, null);
		// REQ-004 S5: aiclaw 变体也回填房间类型（单聊=2）
		MessageAdapter.fillRoomType(aiclawResp, room.getType());
		// REQ-004 M2-2: aiclaw 响应也透传 extra
		if (dto.getExtra() != null && aiclawResp.getMessage() != null) {
			aiclawResp.getMessage().setExtra(dto.getExtra());
		}
		fillAiclawExt(aiclawResp, aiclawUid, senderUid);
		return Optional.of(new DirectChatDelivery(aiclawUid, aiclawResp));
	}

	/**
	 * 检查两个 uid 中是否有 aiclaw 用户，有则返回其 uid，否则返回 null（原 MsgSendConsumer.findAiclawUid）。
	 */
	private Long findAiclawUid(Long uid1, Long uid2) {
		if (isAiclaw(uid1)) {
			return uid1;
		}
		if (isAiclaw(uid2)) {
			return uid2;
		}
		return null;
	}

	/**
	 * 为推送给 aiclaw 的消息附加扩展字段（原 MsgSendConsumer.fillAiclawExt，逐字保留）。
	 */
	private void fillAiclawExt(ChatMessageResp resp, Long aiclawUid, Long senderUid) {
		Aiclaw aiclaw = aiclawDao.getByUid(aiclawUid);
		if (aiclaw == null) return;

		boolean isOwner = Objects.equals(senderUid, aiclaw.getOwnerUid());
		SummeryInfoDTO senderInfo = userSummaryCache.get(senderUid);

		ChatMessageResp.AiclawExt ext = ChatMessageResp.AiclawExt.builder()
				.senderName(senderInfo != null ? senderInfo.getName() : null)
				.isOwner(isOwner)
				.build();

		if (!isOwner) {
			ext.setPublicPersona(aiclaw.getPublicPersona());
			AiclawFriendExt friendExt = aiclawFriendExtDao.getByAiclawAndFriend(aiclawUid, senderUid);
			ext.setRelationDesc(friendExt != null ? friendExt.getRelationDesc() : null);
		}

		resp.getMessage().setAiclaw(ext);
	}

	@Override
	public Set<Long> autoJoinInvitedAiclaws(RoomGroup roomGroup, List<User> resolvedInvitees, Long inviterUid) {
		// REQ-009 #88: 识别被邀请人中的所有 aiclaw（userType=4），无论归属，统一自动入群（pending）。
		// 别人拉你的 aiclaw 也走自动入群，避免落入普通邀请流程而无 UI 可接受。
		Set<Long> autoAgreeUids = resolvedInvitees.stream()
				.filter(user -> Objects.equals(user.getUserType(), UserTypeEnum.AICLAW.getValue()))
				.map(User::getId)
				.collect(Collectors.toSet());
		if (!autoAgreeUids.isEmpty()) {
			batchAddAiclawMembers(roomGroup, autoAgreeUids, inviterUid);
		}
		return autoAgreeUids;
	}

	/**
	 * 批量添加 aiclaw 入群（自动同意，不走 UserApply）——原 RoomAppServiceImpl.batchAddAiclawMembers，逐字保留。
	 */
	private void batchAddAiclawMembers(RoomGroup roomGroup, Set<Long> aiclawUids, Long inviterUid) {
		for (Long aiclawUid : aiclawUids) {
			GroupMember member = groupMemberDao.getMemberByGroupId(roomGroup.getId(), aiclawUid);
			if (member != null) {
				continue; // 已在群中，跳过
			}

			transactionTemplate.execute(e -> {
				groupMemberDao.save(MemberAdapter.buildMemberAdd(roomGroup.getId(), aiclawUid));
				chatService.createContact(aiclawUid, roomGroup.getRoomId());
				return true;
			});

			// 更新缓存
			groupMemberCache.evictMemberList(roomGroup.getRoomId());
			groupMemberCache.evictExceptMemberList(roomGroup.getRoomId());
			CacheKey uKey = PresenceCacheKeyBuilder.userGroupsKey(aiclawUid);
			CacheKey gKey = PresenceCacheKeyBuilder.groupMembersKey(roomGroup.getRoomId());
			CacheKey onlineGroupMembersKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomGroup.getRoomId());
			cachePlusOps.sAdd(uKey, roomGroup.getRoomId());
			cachePlusOps.sAdd(gKey, aiclawUid);

			SpringUtils.publishEvent(new GroupMemberAddEvent(this, roomGroup.getRoomId(),
					Math.toIntExact(cachePlusOps.sCard(gKey)),
					Math.toIntExact(cachePlusOps.sCard(onlineGroupMembersKey)),
					Arrays.asList(aiclawUid), inviterUid));

			log.info("aiclaw auto-joined group: aiclawUid={}, roomId={}, inviter={}", aiclawUid, roomGroup.getRoomId(), inviterUid);

			// REQ-009 #88: 别人拉你的 aiclaw 入群 → 给主人发「待批准」通知；主人自己拉自己的不发（主人在群里有就地审批弹窗）。
			// #153: 去重门控——同一 (aiclaw, room) 未决期内只发一条待批准通知。tryMarkApproveNotified 用 Redis SETNX
			// 原子占位，处理「移出群后再被拉回」的重复邀请与并发双邀请竞态；主人做出决定后清标记（见 updateConfig）。
			Long ownerUid = aiclawOwnerCache.getOwnerUid(aiclawUid);
			if (ownerUid != null && !ownerUid.equals(inviterUid)
					&& aiclawGroupConfigService.tryMarkApproveNotified(aiclawUid, roomGroup.getRoomId())) {
				try {
					noticeService.createNotice(
							RoomTypeEnum.GROUP, NoticeTypeEnum.AICLAW_GROUP_APPROVE,
							aiclawUid,                 // senderId
							ownerUid,                  // receiverId（aiclaw 的主人）
							0L,                        // applyId
							aiclawUid,                 // operate（= aiclaw uid → 服务端置 receiverUserType=4，转发给主人）
							roomGroup.getRoomId(),     // roomId
							roomGroup.getName());      // content = 群名
				} catch (RuntimeException ex) {
					// #153 P0-1: 通知创建失败 → 补偿回滚 SETNX 去重标记，否则标记会占位 24h 而通知永久丢失，
					// 主人在这 24h 内再也收不到该 (aiclaw, room) 的待批准通知。只有通知真正落地，标记才应永久保留。
					aiclawGroupConfigService.clearApproveNotified(aiclawUid, roomGroup.getRoomId());
					throw ex;
				}
			}
		}
	}

	@Override
	public void onMembersRemoved(Long roomId, Collection<Long> removedUids) {
		// #153 P1-1/P1-2: 被踢/退群者若是 aiclaw（getOwnerUid != null 表示其为 aiclaw）→ 清入群待批准去重标记。
		// 踢出/退群 = 明确不想要该 aiclaw，若之后再被拉回应重新给主人发待批准通知，不能被旧标记压制 24h。
		for (Long uid : removedUids) {
			if (aiclawOwnerCache.getOwnerUid(uid) != null) {
				aiclawGroupConfigService.clearApproveNotified(uid, roomId);
			}
		}
	}
}
