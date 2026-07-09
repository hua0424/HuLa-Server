package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
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
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#169 边界钉子（manager 强制）：证明对「纯人类」单聊/群聊，AiclawParticipant 接缝是 no-op。
 *
 * <p>即：从通用 IM 路径抽走 aiclaw 特判后，纯人类场景自洽——
 * isAiclaw=false、filterGroupRecipients 原样返回、resolveDirectChatDelivery 为空，
 * 且不触碰 owner 缓存 / 审批门控的任何「aiclaw 专属」查询。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiclawParticipantBoundaryTest {

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
	@Mock private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
	@Mock private NoticeService noticeService;

	@InjectMocks private AiclawParticipantImpl participant;

	private static final Long ROOM_ID = 10L;
	private static final Long HUMAN_A = 100L;
	private static final Long HUMAN_B = 300L;

	private SummeryInfoDTO human(String name) {
		return SummeryInfoDTO.builder().name(name).userType(3).build();
	}

	@Test
	@DisplayName("边界: 纯人类单聊/群聊 → 全员非 aiclaw、群过滤原样返回、单聊定向解析为空、不触碰 owner 缓存")
	void seamIsNoOpForPureHumanRooms() {
		when(userSummaryCache.get(anyLong())).thenReturn(human("人"));

		// 1) 全员非 aiclaw
		assertFalse(participant.isAiclaw(HUMAN_A));
		assertFalse(participant.isAiclaw(HUMAN_B));

		// 2) 群过滤：委派返回原列表（纯人类，门控无剔除）
		List<Long> members = List.of(HUMAN_A, HUMAN_B);
		when(aiclawGroupConfigService.filterUnapprovedAiclawRecipients(members, ROOM_ID)).thenReturn(members);
		assertSame(members, participant.filterGroupRecipients(members, ROOM_ID));

		// 3) 纯人类单聊 → 定向解析为空
		Room friend = new Room();
		friend.setId(ROOM_ID);
		friend.setType(RoomTypeEnum.FRIEND.getType());
		RoomFriend rf = new RoomFriend();
		rf.setUid1(HUMAN_A);
		rf.setUid2(HUMAN_B);
		when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(rf);
		Message message = new Message();
		message.setId(1L);
		message.setRoomId(ROOM_ID);
		message.setFromUid(HUMAN_A);
		MsgSendMessageDTO dto = MsgSendMessageDTO.builder().uid(HUMAN_A).build();

		Optional<AiclawParticipant.DirectChatDelivery> out =
				participant.resolveDirectChatDelivery(message, friend, dto);
		assertTrue(out.isEmpty());

		// 4) 纯人类群聊 → 定向解析为空，且不查 RoomFriend
		Room group = new Room();
		group.setId(ROOM_ID);
		group.setType(RoomTypeEnum.GROUP.getType());
		assertTrue(participant.resolveDirectChatDelivery(message, group, dto).isEmpty());

		// 关键钉子：纯人类路径不触碰 owner 缓存与 aiclaw 富化查询
		verifyNoInteractions(aiclawOwnerCache);
		verify(aiclawDao, never()).getByUid(anyLong());
		verify(aiclawFriendExtDao, never()).getByAiclawAndFriend(anyLong(), anyLong());
		verify(chatService, never()).getMsgResp(any(Message.class), any());
	}
}
