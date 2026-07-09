package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
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
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#169: {@link AiclawParticipant} 接缝单测。
 *
 * <p>覆盖：isAiclaw 判定、filterGroupRecipients 委派+透传、resolveDirectChatDelivery 富化/空、
 * autoJoinInvitedAiclaws（自动入群 + inviter≠owner 通知主人 + 去重 + 非 aiclaw 剔除）、
 * onMembersRemoved 清审批标记。行为等价于重构前散落在 MsgSendConsumer / RoomAppServiceImpl 的特判。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiclawParticipantTest {

	@Mock private UserSummaryCache userSummaryCache;
	@Mock private AiclawGroupConfigService aiclawGroupConfigService;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private ChatService chatService;
	@Mock private AiclawDao aiclawDao;
	@Mock private AiclawFriendExtDao aiclawFriendExtDao;
	@Mock private AiclawOwnerCache aiclawOwnerCache;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private TransactionTemplate transactionTemplate;
	@Mock private NoticeService noticeService;

	@InjectMocks private AiclawParticipantImpl participant;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long HUMAN_UID = 100L;
	private static final Long AICLAW_UID = 200L;
	private static final Long OWNER = 999L;
	private static final String GROUP_NAME = "测试群";

	@BeforeEach
	void setUp() {
		// aiclaw 入群会经 SpringUtils.publishEvent(GroupMemberAddEvent)（静态），单测无容器 → 注入 mock。
		SpringUtils.setApplicationContext(
				org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class));
	}

	private SummeryInfoDTO summary(String name, Integer userType) {
		return SummeryInfoDTO.builder().name(name).userType(userType).build();
	}

	private Message msgFrom(Long fromUid) {
		Message m = new Message();
		m.setId(1L);
		m.setRoomId(ROOM_ID);
		m.setFromUid(fromUid);
		return m;
	}

	private Room friendRoom() {
		Room r = new Room();
		r.setId(ROOM_ID);
		r.setType(RoomTypeEnum.FRIEND.getType());
		return r;
	}

	private Room groupRoom() {
		Room r = new Room();
		r.setId(ROOM_ID);
		r.setType(RoomTypeEnum.GROUP.getType());
		return r;
	}

	private ChatMessageResp emptyResp() {
		ChatMessageResp resp = new ChatMessageResp();
		resp.setMessage(new ChatMessageResp.Message());
		return resp;
	}

	private RoomGroup roomGroup() {
		RoomGroup rg = new RoomGroup();
		rg.setId(GROUP_ID);
		rg.setRoomId(ROOM_ID);
		rg.setName(GROUP_NAME);
		return rg;
	}

	private User user(Long uid, Integer userType) {
		User u = new User();
		u.setId(uid);
		u.setUserType(userType);
		u.setName("u" + uid);
		return u;
	}

	// ---- isAiclaw ----

	@Test
	@DisplayName("isAiclaw: userType==4 → true")
	void isAiclaw_true() {
		when(userSummaryCache.get(AICLAW_UID)).thenReturn(summary("安洁", 4));
		assertTrue(participant.isAiclaw(AICLAW_UID));
	}

	@Test
	@DisplayName("isAiclaw: userType==3(普通) → false")
	void isAiclaw_falseNormal() {
		when(userSummaryCache.get(HUMAN_UID)).thenReturn(summary("张三", 3));
		assertFalse(participant.isAiclaw(HUMAN_UID));
	}

	@Test
	@DisplayName("isAiclaw: summary 为 null → false（不 NPE）")
	void isAiclaw_falseNullSummary() {
		when(userSummaryCache.get(anyLong())).thenReturn(null);
		assertFalse(participant.isAiclaw(HUMAN_UID));
	}

	// ---- filterGroupRecipients ----

	@Test
	@DisplayName("filterGroupRecipients: 委派 AiclawGroupConfigService 并原样返回其结果")
	void filterGroupRecipients_delegatesAndPassesThrough() {
		List<Long> input = List.of(HUMAN_UID, AICLAW_UID);
		List<Long> filtered = List.of(HUMAN_UID);
		when(aiclawGroupConfigService.filterUnapprovedAiclawRecipients(input, ROOM_ID)).thenReturn(filtered);

		List<Long> result = participant.filterGroupRecipients(input, ROOM_ID);

		assertSame(filtered, result, "接缝应原样透传委派结果");
		verify(aiclawGroupConfigService).filterUnapprovedAiclawRecipients(input, ROOM_ID);
	}

	// ---- resolveDirectChatDelivery ----

	@Test
	@DisplayName("resolveDirectChatDelivery: 单聊对端是 aiclaw 且发送者非主人 → 富化 payload(isOwner=false + publicPersona + relationDesc + senderName)")
	void resolveDirectChatDelivery_present_notOwner() {
		Message message = msgFrom(HUMAN_UID); // 发送者是人类好友（非主人）
		Room room = friendRoom();
		MsgSendMessageDTO dto = MsgSendMessageDTO.builder().uid(HUMAN_UID).build();

		RoomFriend rf = new RoomFriend();
		rf.setUid1(HUMAN_UID);
		rf.setUid2(AICLAW_UID);
		when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(rf);
		when(userSummaryCache.get(HUMAN_UID)).thenReturn(summary("张三", 3));
		when(userSummaryCache.get(AICLAW_UID)).thenReturn(summary("安洁", 4));
		when(chatService.getMsgResp(eq(message), eq(null))).thenReturn(emptyResp());

		Aiclaw aiclaw = Aiclaw.builder().uid(AICLAW_UID).ownerUid(OWNER).publicPersona("人设X").build();
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);
		AiclawFriendExt friendExt = new AiclawFriendExt();
		friendExt.setRelationDesc("老同学");
		when(aiclawFriendExtDao.getByAiclawAndFriend(AICLAW_UID, HUMAN_UID)).thenReturn(friendExt);

		Optional<AiclawParticipant.DirectChatDelivery> out =
				participant.resolveDirectChatDelivery(message, room, dto);

		assertTrue(out.isPresent(), "对端 aiclaw → 应有定向投递");
		assertEquals(AICLAW_UID, out.get().getTargetUid());
		ChatMessageResp.AiclawExt ext = out.get().getPayload().getMessage().getAiclaw();
		assertEquals(false, ext.getIsOwner());
		assertEquals("人设X", ext.getPublicPersona());
		assertEquals("老同学", ext.getRelationDesc());
		assertEquals("张三", ext.getSenderName(), "senderName 取发送者 summary.name");
	}

	@Test
	@DisplayName("resolveDirectChatDelivery: 发送者是主人 → isOwner=true 且不带 publicPersona/relationDesc")
	void resolveDirectChatDelivery_present_owner() {
		Message message = msgFrom(OWNER); // 发送者是 aiclaw 主人
		Room room = friendRoom();
		MsgSendMessageDTO dto = MsgSendMessageDTO.builder().uid(OWNER).build();

		RoomFriend rf = new RoomFriend();
		rf.setUid1(OWNER);
		rf.setUid2(AICLAW_UID);
		when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(rf);
		when(userSummaryCache.get(OWNER)).thenReturn(summary("主人", 3));
		when(userSummaryCache.get(AICLAW_UID)).thenReturn(summary("安洁", 4));
		when(chatService.getMsgResp(eq(message), eq(null))).thenReturn(emptyResp());
		Aiclaw aiclaw = Aiclaw.builder().uid(AICLAW_UID).ownerUid(OWNER).publicPersona("人设X").build();
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);

		Optional<AiclawParticipant.DirectChatDelivery> out =
				participant.resolveDirectChatDelivery(message, room, dto);

		assertTrue(out.isPresent());
		ChatMessageResp.AiclawExt ext = out.get().getPayload().getMessage().getAiclaw();
		assertEquals(true, ext.getIsOwner());
		assertNull(ext.getPublicPersona(), "主人不带对外人设");
		assertNull(ext.getRelationDesc(), "主人不带关系说明");
		verify(aiclawFriendExtDao, never()).getByAiclawAndFriend(anyLong(), anyLong());
	}

	@Test
	@DisplayName("resolveDirectChatDelivery: 单聊双方都是人类 → empty")
	void resolveDirectChatDelivery_empty_noAiclaw() {
		Message message = msgFrom(HUMAN_UID);
		Room room = friendRoom();
		MsgSendMessageDTO dto = MsgSendMessageDTO.builder().uid(HUMAN_UID).build();

		RoomFriend rf = new RoomFriend();
		rf.setUid1(HUMAN_UID);
		rf.setUid2(300L);
		when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(rf);
		when(userSummaryCache.get(HUMAN_UID)).thenReturn(summary("张三", 3));
		when(userSummaryCache.get(300L)).thenReturn(summary("李四", 3));

		Optional<AiclawParticipant.DirectChatDelivery> out =
				participant.resolveDirectChatDelivery(message, room, dto);

		assertTrue(out.isEmpty());
		verify(chatService, never()).getMsgResp(any(Message.class), any());
	}

	@Test
	@DisplayName("resolveDirectChatDelivery: 群聊房间 → empty（且不查 RoomFriend）")
	void resolveDirectChatDelivery_empty_groupRoom() {
		Message message = msgFrom(HUMAN_UID);
		Room room = groupRoom();
		MsgSendMessageDTO dto = MsgSendMessageDTO.builder().uid(HUMAN_UID).build();

		Optional<AiclawParticipant.DirectChatDelivery> out =
				participant.resolveDirectChatDelivery(message, room, dto);

		assertTrue(out.isEmpty());
		verify(roomFriendDao, never()).getByRoomId(anyLong());
	}

	// ---- autoJoinInvitedAiclaws ----

	private void stubAutoJoinCommon() {
		// 被邀请 aiclaw 尚未在群 → 走入群
		when(groupMemberDao.getMemberByGroupId(eq(GROUP_ID), eq(AICLAW_UID))).thenReturn(null);
		// transactionTemplate 真正执行回调
		lenient().when(transactionTemplate.execute(any())).thenAnswer(
				inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
		lenient().when(cachePlusOps.sCard(any())).thenReturn(1L);
	}

	@Test
	@DisplayName("autoJoin: 别人拉(inviter≠owner) → aiclaw 入群 + 发 AICLAW_GROUP_APPROVE 给主人；返回集含 aiclaw")
	void autoJoin_notifiesOwner() {
		stubAutoJoinCommon();
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);
		when(aiclawGroupConfigService.tryMarkApproveNotified(AICLAW_UID, ROOM_ID)).thenReturn(true);

		Set<Long> joined = participant.autoJoinInvitedAiclaws(
				roomGroup(), List.of(user(AICLAW_UID, 4)), HUMAN_UID);

		assertEquals(Set.of(AICLAW_UID), joined);
		verify(groupMemberDao, atLeastOnce()).save(any(GroupMember.class));
		verify(noticeService).createNotice(
				eq(RoomTypeEnum.GROUP), eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				eq(AICLAW_UID), eq(OWNER), eq(0L), eq(AICLAW_UID), eq(ROOM_ID), eq(GROUP_NAME));
	}

	@Test
	@DisplayName("autoJoin: 主人自己拉(inviter==owner) → 入群但不发待批准通知")
	void autoJoin_ownerInvites_noNotice() {
		stubAutoJoinCommon();
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);

		Set<Long> joined = participant.autoJoinInvitedAiclaws(
				roomGroup(), List.of(user(AICLAW_UID, 4)), OWNER);

		assertEquals(Set.of(AICLAW_UID), joined);
		verify(groupMemberDao, atLeastOnce()).save(any(GroupMember.class));
		verify(noticeService, never()).createNotice(
				any(RoomTypeEnum.class), eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("autoJoin: #153 去重命中(tryMark=false) → 入群但跳过通知")
	void autoJoin_dedupSkipsNotice() {
		stubAutoJoinCommon();
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);
		when(aiclawGroupConfigService.tryMarkApproveNotified(AICLAW_UID, ROOM_ID)).thenReturn(false);

		Set<Long> joined = participant.autoJoinInvitedAiclaws(
				roomGroup(), List.of(user(AICLAW_UID, 4)), HUMAN_UID);

		assertEquals(Set.of(AICLAW_UID), joined);
		verify(noticeService, never()).createNotice(
				any(RoomTypeEnum.class), eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("autoJoin: 被邀请人全是非 aiclaw(userType=3) → 返回空集，不入群不通知")
	void autoJoin_nonAiclawExcluded() {
		Set<Long> joined = participant.autoJoinInvitedAiclaws(
				roomGroup(), List.of(user(HUMAN_UID, 3), user(300L, 3)), OWNER);

		assertTrue(joined.isEmpty());
		verify(groupMemberDao, never()).save(any(GroupMember.class));
		verify(noticeService, never()).createNotice(
				any(RoomTypeEnum.class), any(NoticeTypeEnum.class),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("autoJoin: #153 P0-1 通知创建失败 → 补偿回滚 SETNX 去重标记并向上抛出")
	void autoJoin_notifyFails_rollsBackDedupMark() {
		stubAutoJoinCommon();
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);
		when(aiclawGroupConfigService.tryMarkApproveNotified(AICLAW_UID, ROOM_ID)).thenReturn(true);
		RuntimeException boom = new RuntimeException("notice service down");
		doThrow(boom).when(noticeService).createNotice(
				eq(RoomTypeEnum.GROUP), eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				eq(AICLAW_UID), eq(OWNER), eq(0L), eq(AICLAW_UID), eq(ROOM_ID), eq(GROUP_NAME));

		RuntimeException thrown = assertThrows(RuntimeException.class, () ->
				participant.autoJoinInvitedAiclaws(roomGroup(), List.of(user(AICLAW_UID, 4)), HUMAN_UID));

		assertSame(boom, thrown, "原始异常应原样向上抛出（不吞不包）");
		// #153 P0-1: 标记必须被回滚，否则未落地的通知会让主人 24h 收不到待批准
		verify(aiclawGroupConfigService).clearApproveNotified(AICLAW_UID, ROOM_ID);
	}

	// ---- onMembersRemoved ----

	@Test
	@DisplayName("onMembersRemoved: 仅对『是 aiclaw(ownerUid!=null)』的被移除 uid 清审批标记")
	void onMembersRemoved_clearsForAiclawOnly() {
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER); // 是 aiclaw
		when(aiclawOwnerCache.getOwnerUid(HUMAN_UID)).thenReturn(null);   // 人类

		participant.onMembersRemoved(ROOM_ID, List.of(AICLAW_UID, HUMAN_UID));

		verify(aiclawGroupConfigService, times(1)).clearApproveNotified(AICLAW_UID, ROOM_ID);
		verify(aiclawGroupConfigService, never()).clearApproveNotified(eq(HUMAN_UID), anyLong());
	}
}
