package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#344：主人管理读取助理与好友的私聊历史。
 *
 * <p>契约（只 assert 外部行为，不锁定内部调用顺序）：
 * 主人可读自有 aiclaw 与好友的私聊（含分页），且不依赖主人拥有该房间联系人行；
 * 成功空结果返回空页；无效目标/无权返回可识别业务错误；读取只读（不经过参与者上限语义）。
 */
@ExtendWith(MockitoExtension.class)
class AiclawConversationMessagesTest {

	@Mock private AiclawDao aiclawDao;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private MessageDao messageDao;
	@Mock private ChatService chatService;

	@InjectMocks
	private AiclawServiceImpl aiclawService;

	private static final Long OWNER_UID = 200L;
	private static final Long AICLAW_UID = 100L;
	private static final Long FRIEND_UID = 300L;
	private static final Long ROOM_ID = 7001L;

	private void ownedAiclaw() {
		when(aiclawDao.getByOwnerAndUid(OWNER_UID, AICLAW_UID)).thenReturn(
				Aiclaw.builder().uid(AICLAW_UID).ownerUid(OWNER_UID).build());
	}

	private void friendRoom() {
		RoomFriend rf = new RoomFriend();
		rf.setRoomId(ROOM_ID);
		rf.setUid1(AICLAW_UID);
		rf.setUid2(FRIEND_UID);
		when(roomFriendDao.getByKey(AICLAW_UID + "," + FRIEND_UID)).thenReturn(rf);
	}

	@Test
	@DisplayName("#344 主人读取助理与好友私聊历史成功：分页透传、不设参与者上限、不走参与者语义")
	void ownerReadsHistory_ok() {
		ownedAiclaw();
		friendRoom();
		CursorPageBaseReq pageReq = new CursorPageBaseReq();
		pageReq.setPageSize(20);

		CursorPageBaseResp<Message> daoPage = new CursorPageBaseResp<>();
		daoPage.setList(List.of(new Message(), new Message()));
		daoPage.setCursor("cursor-1");
		daoPage.setIsLast(false);
		daoPage.setTotal(2L);
		when(messageDao.getCursorPage(eq(ROOM_ID), eq(pageReq), isNull())).thenReturn(daoPage);
		ChatMessageResp vo1 = mock(ChatMessageResp.class);
		ChatMessageResp vo2 = mock(ChatMessageResp.class);
		when(chatService.getMsgRespBatch(eq(daoPage.getList()), eq(OWNER_UID)))
				.thenReturn(List.of(vo1, vo2));

		CursorPageBaseResp<ChatMessageResp> result =
				aiclawService.getConversationMessages(AICLAW_UID, FRIEND_UID, pageReq, OWNER_UID);

		assertEquals(List.of(vo1, vo2), result.getList());
		assertEquals("cursor-1", result.getCursor());
		assertFalse(result.getIsLast());
		// 管理读取不经过参与者语义的 getMsgPage（该路径以调用方 uid 查联系人上限，主人无联系人行即 NPE）
		verify(chatService, never()).getMsgPage(any(), any());
		verifyNoMoreInteractions(messageDao, chatService);
	}

	@Test
	@DisplayName("#344 成功空结果返回空页（isLast=true），与失败可区分")
	void emptyHistory_returnsEmptyPage() {
		ownedAiclaw();
		friendRoom();
		CursorPageBaseReq pageReq = new CursorPageBaseReq();
		when(messageDao.getCursorPage(eq(ROOM_ID), eq(pageReq), isNull()))
				.thenReturn(CursorPageBaseResp.empty());

		CursorPageBaseResp<ChatMessageResp> result =
				aiclawService.getConversationMessages(AICLAW_UID, FRIEND_UID, pageReq, OWNER_UID);

		assertTrue(result.isEmpty());
		assertTrue(result.getIsLast());
		verify(chatService, never()).getMsgRespBatch(anyList(), any());
	}

	@Test
	@DisplayName("#344 非主人读取被拒绝，不触及消息查询")
	void nonOwner_rejected() {
		when(aiclawDao.getByOwnerAndUid(999L, AICLAW_UID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class, () ->
				aiclawService.getConversationMessages(AICLAW_UID, FRIEND_UID, new CursorPageBaseReq(), 999L));

		assertEquals("AI助理不存在或无权操作", ex.getMessage());
		verifyNoInteractions(messageDao, chatService);
	}

	@Test
	@DisplayName("#344 非好友目标返回可识别业务错误，不抛 NPE/500")
	void nonFriendTarget_recognizableError() {
		ownedAiclaw();
		when(roomFriendDao.getByKey(AICLAW_UID + "," + FRIEND_UID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class, () ->
				aiclawService.getConversationMessages(AICLAW_UID, FRIEND_UID, new CursorPageBaseReq(), OWNER_UID));

		assertEquals("该用户不是AI助理的好友", ex.getMessage());
		verifyNoInteractions(messageDao, chatService);
	}
}
