package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.im.core.chat.dao.*;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMsgRelMapper;
import com.luohuo.flex.im.core.chat.service.AiclawRoomMembershipService;
import com.luohuo.flex.im.core.chat.service.ContactService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.MsgCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.strategy.msg.AbstractMsgHandler;
import com.luohuo.flex.im.core.chat.service.strategy.msg.MsgHandlerFactory;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.Nested;
import org.mockito.ArgumentCaptor;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import com.luohuo.flex.im.enums.UserTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#3: aiclaw 房间成员校验（REST 入口）+ 短回复 skip 下线测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceImplTest {

	@Mock private UserFriendDao userFriendDao;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private MsgCache msgCache;
	@Mock private MessageDao messageDao;
	@Mock private MessageMarkDao messageMarkDao;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private ContactService contactService;
	@Mock private ContactDao contactDao;
	@Mock private RoomCache roomCache;
	@Mock private RoomDao roomDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserCache userCache;
	@Mock private AiclawRoomMembershipService aiclawRoomMembershipService;
	@Mock private AiclawThinkingMapper aiclawThinkingMapper;
	@Mock private AiclawThinkingMsgRelMapper aiclawThinkingMsgRelMapper;
	@Mock private UserSummaryCache userSummaryCache;

	@InjectMocks
	private ChatServiceImpl chatService;

	private static final Long AICLAW_UID = 100L;
	private static final Long NORMAL_UID = 200L;
	private static final Long ROOM_ID = 10L;

	// ==================== 辅助方法 ====================

	private ChatMessageReq baseReq(Long roomId) {
		return ChatMessageReq.builder()
				.roomId(roomId)
				.msgType(1)
				.body("hello")
				.build();
	}

	private User aiclawUser() {
		User user = new User();
		user.setId(AICLAW_UID);
		user.setUserType(UserTypeEnum.AICLAW.getValue());
		return user;
	}

	private User normalUser() {
		User user = new User();
		user.setId(NORMAL_UID);
		user.setUserType(UserTypeEnum.NORMAL.getValue());
		return user;
	}

	private GroupMember mockGroupMember() {
		GroupMember member = new GroupMember();
		member.setDeFriend(false);
		return member;
	}

	private void stubRoomCache() {
		Room groupRoom = new Room();
		groupRoom.setType(RoomTypeEnum.GROUP.getType());
		when(roomCache.get(ROOM_ID)).thenReturn(groupRoom);
	}

	/**
	 * 同时 mock MsgHandlerFactory + SpringUtils，让 sendMsg 完整走通。
	 */
	private Long sendMsgWithMockedHandler(ChatMessageReq req, Long uid) {
		try (MockedStatic<MsgHandlerFactory> mf = mockStatic(MsgHandlerFactory.class);
			 MockedStatic<SpringUtils> su = mockStatic(SpringUtils.class)) {
			AbstractMsgHandler<?> handler = mock(AbstractMsgHandler.class);
			when(handler.checkAndSaveMsg(any(), any())).thenReturn(999L);
			mf.when(() -> MsgHandlerFactory.getStrategyNoNull(1)).thenReturn(handler);
			su.when(() -> SpringUtils.publishEvent(any())).thenAnswer(inv -> null);

			return chatService.sendMsg(req, uid);
		}
	}

	// ==================== aiclaw 成员校验（委托 Service 层） ====================

	@Test
	@DisplayName("aiclaw 在群聊中且是成员 → 成员校验通过，正常发送")
	void aiclaw_member_shouldSend() {
		stubRoomCache();
		when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, 201L, 202L));
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(mockGroupMember());

		Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), AICLAW_UID);
		assertEquals(999L, msgId);
	}

	@Test
	@DisplayName("aiclaw 成员校验被 Service 拒绝 → 抛 BizException")
	void aiclaw_notMember_shouldThrow() {
		when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
		// 共享 Service 抛异常
		doThrow(new BizException("非房间成员，无法发送消息"))
				.when(aiclawRoomMembershipService).checkMembership(AICLAW_UID, ROOM_ID);

		ChatMessageReq req = baseReq(ROOM_ID);
		BizException ex = assertThrows(BizException.class, () -> chatService.sendMsg(req, AICLAW_UID));
		assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
	}

	// ==================== 普通用户绕过校验 ====================

	@Test
	@DisplayName("普通用户在群聊中 → 绕过 aiclaw 校验，正常发送")
	void normalUserInGroup_shouldBypassAiclawCheck() {
		stubRoomCache();
		when(userCache.get(NORMAL_UID)).thenReturn(normalUser());
		when(groupMemberDao.getMember(ROOM_ID, NORMAL_UID)).thenReturn(mockGroupMember());

		Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), NORMAL_UID);
		assertEquals(999L, msgId);

		// 验证共享 Service 未被调用
		verify(aiclawRoomMembershipService, never()).checkMembership(any(), any());
	}

	@Test
	@DisplayName("普通用户在私聊中 → 绕过 aiclaw 校验，正常发送")
	void normalUserInFriend_shouldBypassAiclawCheck() {
		Room friendRoom = new Room();
		friendRoom.setType(RoomTypeEnum.FRIEND.getType());
		when(roomCache.get(20L)).thenReturn(friendRoom);
		when(userCache.get(NORMAL_UID)).thenReturn(normalUser());

		RoomFriend rf = new RoomFriend();
		rf.setUid1(NORMAL_UID);
		rf.setUid2(201L);
		when(roomFriendDao.getByRoomId(20L)).thenReturn(rf);

		Long msgId = sendMsgWithMockedHandler(baseReq(20L), NORMAL_UID);
		assertEquals(999L, msgId);

		verify(aiclawRoomMembershipService, never()).checkMembership(any(), any());
	}

	// ==================== sender==null 边缘测试（顺手项） ====================

	@Test
	@DisplayName("userCache 返回 null → 视为非 aiclaw，绕过校验")
	void senderNull_shouldBypass() {
		when(userCache.get(999L)).thenReturn(null);
		stubRoomCache();
		when(groupMemberDao.getMember(ROOM_ID, 999L)).thenReturn(mockGroupMember());

		Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), 999L);
		assertEquals(999L, msgId);

		verify(aiclawRoomMembershipService, never()).checkMembership(any(), any());
	}

	// ==================== 短回复不再 skip ====================

	@Test
	@DisplayName("aiclaw 发送短消息不再被 skip，正常落库")
	void aiclawShortReply_noLongerSkipped() {
		stubRoomCache();
		when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, 201L));
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(mockGroupMember());

		ChatMessageReq req = baseReq(ROOM_ID);
		req.setBody("hi"); // 短消息

		// 不再抛 "short_reply_skip" 异常，正常返回 msgId
		Long msgId = sendMsgWithMockedHandler(req, AICLAW_UID);
		assertEquals(999L, msgId);
	}

	// ==================== REQ-004 [S4] thinking 自动关联 ====================

	@Nested
	@DisplayName("S4 thinking 自动关联")
	class ThinkingAssociation {

		private void stubAiclawGroupSend() {
			stubRoomCache();
			when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
			when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, 201L));
			when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(mockGroupMember());
		}

		@Test
		@DisplayName("extra.thinkingId 存在 → 用该 id 关联（source=extra），不查 active")
		void extraThinkingId_associatesWithThatId() {
			stubAiclawGroupSend();
			ChatMessageReq req = baseReq(ROOM_ID);
			req.setExtra(Map.of("thinkingId", "555"));

			Long msgId = sendMsgWithMockedHandler(req, AICLAW_UID);
			assertEquals(999L, msgId);

			ArgumentCaptor<AiclawThinkingMsgRel> captor = ArgumentCaptor.forClass(AiclawThinkingMsgRel.class);
			verify(aiclawThinkingMsgRelMapper).insertIgnore(captor.capture());
			assertEquals(555L, captor.getValue().getThinkingId());
			assertEquals(999L, captor.getValue().getMsgId());
			verify(aiclawThinkingMapper).updateHasResponse(555L, 1);
			verify(aiclawThinkingMapper, never()).selectActiveThinkingId(any(), any());
		}

		@Test
		@DisplayName("无 extra + 存在 active thinking → 自动关联该 id（source=auto）")
		void noExtra_activeThinkingExists_associatesAuto() {
			stubAiclawGroupSend();
			when(aiclawThinkingMapper.selectActiveThinkingId(AICLAW_UID, ROOM_ID)).thenReturn(777L);

			Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), AICLAW_UID);
			assertEquals(999L, msgId);

			ArgumentCaptor<AiclawThinkingMsgRel> captor = ArgumentCaptor.forClass(AiclawThinkingMsgRel.class);
			verify(aiclawThinkingMsgRelMapper).insertIgnore(captor.capture());
			assertEquals(777L, captor.getValue().getThinkingId());
			assertEquals(999L, captor.getValue().getMsgId());
			verify(aiclawThinkingMapper).updateHasResponse(777L, 1);
		}

		@Test
		@DisplayName("无 extra + 无 active thinking → 跳过关联，不写 rel、不更新 has_response")
		void noExtra_noActiveThinking_skipsAssociation() {
			stubAiclawGroupSend();
			when(aiclawThinkingMapper.selectActiveThinkingId(AICLAW_UID, ROOM_ID)).thenReturn(null);

			Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), AICLAW_UID);
			assertEquals(999L, msgId);

			verify(aiclawThinkingMsgRelMapper, never()).insertIgnore(any());
			verify(aiclawThinkingMapper, never()).updateHasResponse(any(), any());
		}
	}

	// ==================== REQ-004 S23: getMsgRespBatch 回填 fromUser.userType ====================

	@Nested
	@DisplayName("S23 getMsgRespBatch 回填 fromUser.userType")
	class FromUserTypeFill {

		/** 构造一条 sender 为 senderUid、msgId 为 msgId 的消息。 */
		private Message msgFrom(Long msgId, Long senderUid) {
			Message m = new Message();
			m.setId(msgId);
			m.setFromUid(senderUid);
			m.setType(1);
			m.setCreateTime(LocalDateTime.now());
			return m;
		}

		private SummeryInfoDTO summary(Long uid, Integer userType) {
			SummeryInfoDTO dto = new SummeryInfoDTO();
			dto.setUid(uid);
			dto.setUserType(userType);
			return dto;
		}

		/** getMsgRespBatch 内部经 buildMsgResp → buildMessage 调用 MsgHandlerFactory，
		 *  这里 mock 静态工厂返回 null（buildMessage 对 null handler 直接跳过 body）。 */
		private List<ChatMessageResp> batchWithMockedHandler(List<Message> messages, Long receiveUid) {
			try (MockedStatic<MsgHandlerFactory> mf = mockStatic(MsgHandlerFactory.class)) {
				mf.when(() -> MsgHandlerFactory.getStrategyNoNull(anyInt())).thenReturn(null);
				return chatService.getMsgRespBatch(messages, receiveUid);
			}
		}

		@Test
		@DisplayName("发送者为 aiclaw(4) → resp.fromUser.userType == 4")
		void getMsgRespBatchFillsUserTypeForAiclawSender() {
			when(messageMarkDao.getValidMarkByMsgIdBatch(anyList())).thenReturn(List.of());
			when(userSummaryCache.getBatch(anyList()))
					.thenReturn(Map.of(AICLAW_UID, summary(AICLAW_UID, UserTypeEnum.AICLAW.getValue())));

			List<ChatMessageResp> resps = batchWithMockedHandler(List.of(msgFrom(999L, AICLAW_UID)), NORMAL_UID);

			assertEquals(1, resps.size());
			assertEquals(UserTypeEnum.AICLAW.getValue(), resps.get(0).getFromUser().getUserType(),
					"aiclaw 发送者的 fromUser.userType 应为 4");
			assertEquals(4, UserTypeEnum.AICLAW.getValue(), "前置断言: AICLAW 枚举值应为 4");
		}

		@Test
		@DisplayName("发送者为普通用户(3) → resp.fromUser.userType == 3")
		void getMsgRespBatchFillsUserTypeForNormalSender() {
			when(messageMarkDao.getValidMarkByMsgIdBatch(anyList())).thenReturn(List.of());
			when(userSummaryCache.getBatch(anyList()))
					.thenReturn(Map.of(NORMAL_UID, summary(NORMAL_UID, UserTypeEnum.NORMAL.getValue())));

			List<ChatMessageResp> resps = batchWithMockedHandler(List.of(msgFrom(999L, NORMAL_UID)), NORMAL_UID);

			assertEquals(1, resps.size());
			assertEquals(UserTypeEnum.NORMAL.getValue(), resps.get(0).getFromUser().getUserType(),
					"普通用户发送者的 fromUser.userType 应为 3");
		}

		@Test
		@DisplayName("缓存查不到发送者 → resp.fromUser.userType == null，不抛异常")
		void getMsgRespBatchMissingCacheEntryLeavesUserTypeNull() {
			when(messageMarkDao.getValidMarkByMsgIdBatch(anyList())).thenReturn(List.of());
			when(userSummaryCache.getBatch(anyList())).thenReturn(Map.of());

			List<ChatMessageResp> resps = batchWithMockedHandler(List.of(msgFrom(999L, AICLAW_UID)), NORMAL_UID);

			assertEquals(1, resps.size());
			assertNull(resps.get(0).getFromUser().getUserType(),
					"缓存缺失时 fromUser.userType 应保持 null（不 NPE）");
		}

		@Test
		@DisplayName("混合发送者批次 [aiclaw, 普通, aiclaw] → 各 resp 按各自 uid 回填，不串号、不因 distinct 塌缩")
		void getMsgRespBatchMixedSendersMatchesEachRespByOwnUid() {
			when(messageMarkDao.getValidMarkByMsgIdBatch(anyList())).thenReturn(List.of());
			// distinct() 会把两个 aiclaw uid 折叠成一个 key，getBatch 仍按 uid 返回；
			// 每条 resp 必须重新按自己的 uid 匹配，而非沿用第一个发送者。
			when(userSummaryCache.getBatch(anyList())).thenReturn(Map.of(
					AICLAW_UID, summary(AICLAW_UID, UserTypeEnum.AICLAW.getValue()),
					NORMAL_UID, summary(NORMAL_UID, UserTypeEnum.NORMAL.getValue())));

			List<ChatMessageResp> resps = batchWithMockedHandler(
					List.of(msgFrom(1001L, AICLAW_UID), msgFrom(1002L, NORMAL_UID), msgFrom(1003L, AICLAW_UID)),
					NORMAL_UID);

			assertEquals(3, resps.size());
			assertEquals(UserTypeEnum.AICLAW.getValue(), resps.get(0).getFromUser().getUserType(),
					"resp[0] 发送者为 aiclaw → userType==4");
			assertEquals(UserTypeEnum.NORMAL.getValue(), resps.get(1).getFromUser().getUserType(),
					"resp[1] 发送者为普通用户 → userType==3（不被首个发送者串号）");
			assertEquals(UserTypeEnum.AICLAW.getValue(), resps.get(2).getFromUser().getUserType(),
					"resp[2] 发送者为 aiclaw → userType==4（distinct 折叠 key 后仍按自身 uid 匹配）");
		}
	}
}
