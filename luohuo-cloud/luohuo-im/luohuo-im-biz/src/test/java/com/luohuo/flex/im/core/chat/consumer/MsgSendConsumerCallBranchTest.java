package com.luohuo.flex.im.core.chat.consumer;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.response.msg.VideoCallMsgDTO;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import org.junit.jupiter.api.DisplayName;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #24: 验证 {@link MsgSendConsumer#onMessage} 在 AUDIO_CALL / VIDEO_CALL 分支
 * 推给其他成员（others）的消息里，发送者的 uid、userType、name 都被纠正回
 * originalFromUid 的值——而不是停留在通话创建者(creator)的 userType/name 上。
 *
 * <p>RED→GREEN 判定点：未修复前，consumer 只 setUid(originalFromUid)，
 * userType/name 仍是 creator 的（getMsgResp 在 fromUid==creator 时返回的值）；
 * 修复后复用 swap 前构建的 selfMsgResp(其 fromUser 按 originalFromUid 填好)的
 * userType/name 覆盖回去。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MsgSendConsumerCallBranchTest {

	@Mock private ChatService chatService;
	@Mock private MessageDao messageDao;
	@Mock private RoomCache roomCache;
	@Mock private RoomDao roomDao;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private OnlineService onlineService;
	@Mock private PushService pushService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private AiclawParticipant aiclawParticipant;

	@InjectMocks private MsgSendConsumer consumer;

	@BeforeEach
	void stubAiclawRecipientPassthrough() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
		// REQ-009#84: onMessage 群路径经 AiclawParticipant 过滤收件人；本类不测该门控，
		// 透传原始列表以保持既有断言语义（#169：接缝替换原 AiclawGroupConfigService @Mock）。类级 strictness=LENIENT。
		when(aiclawParticipant.filterGroupRecipients(anyList(), any()))
				.thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(pushService).withMessageTransaction(any(Runnable.class));
	}

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        com.luohuo.basic.context.ContextUtil.clearTenantContext();
        com.luohuo.basic.context.ContextUtil.remove();
    }

	private static final Long ROOM_ID = 10L;
	private static final Long MSG_ID = 1L;
	private static final Long ORIGINAL_FROM_UID = 100L;
	private static final Long OTHER_MEMBER_UID = 300L;

	/** 构造一个带 fromUser + 空 message 的 ChatMessageResp（模拟 getMsgResp 的产物）。 */
	private ChatMessageResp resp(String uid, Integer userType, String name) {
		ChatMessageResp.UserInfo fromUser = new ChatMessageResp.UserInfo();
		fromUser.setUid(uid);
		fromUser.setUserType(userType);
		fromUser.setName(name);
		ChatMessageResp r = new ChatMessageResp();
		r.setFromUser(fromUser);
		// fillRoomType 对 message==null 是 no-op，但给个空 Message 更稳
		r.setMessage(new ChatMessageResp.Message());
		return r;
	}

	private Message buildCallMessage(Long creator) {
		VideoCallMsgDTO videoCall = VideoCallMsgDTO.builder().creator(creator).build();
		MessageExtra extra = MessageExtra.builder().videoCallMsgDTO(videoCall).build();
		Message message = new Message();
		message.setId(MSG_ID);
		message.setRoomId(ROOM_ID);
		message.setType(MessageTypeEnum.VIDEO_CALL.getType());
		message.setFromUid(ORIGINAL_FROM_UID);
		message.setExtra(extra);
		return message;
	}

	private Room buildGroupRoom() {
		Room room = new Room();
		room.setId(ROOM_ID);
		room.setType(RoomTypeEnum.GROUP.getType());
		return room;
	}

	/**
	 * getMsgResp 按调用时 message.getFromUid() 的当前值返回不同 resp：
	 * - fromUid==100(originalFromUid) → {uid=100, userType=3, name=Alice}
	 * - fromUid==200(creator)         → {uid=200, userType=4, name=Bot}
	 * 每次 thenAnswer 都 new 全新对象，避免引用串味。
	 */
	private void stubGetMsgRespByFromUid(Message message) {
		when(chatService.getMsgResp(eq(message), isNull())).thenAnswer(inv -> {
			Long fromUid = message.getFromUid();
			if (ORIGINAL_FROM_UID.equals(fromUid)) {
				return resp("100", 3, "Alice");
			}
			return resp("200", 4, "Bot");
		});
	}

	private MsgSendMessageDTO buildDto() {
		return MsgSendMessageDTO.builder()
				.msgId(MSG_ID)
				.uid(ORIGINAL_FROM_UID)
				.tenantId(1L)
				.extra(null)
				.build();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private ChatMessageResp captureOthersPushedData() {
		ArgumentCaptor<WsBaseResp> othersCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		// others 推送走 List<Long> 重载；self 推送走单 Long 重载，天然区分
		verify(pushService, org.mockito.Mockito.times(2)).sendReliablePushMsg(othersCaptor.capture(), anyList(), eq(MSG_ID), any());
		return (ChatMessageResp) othersCaptor.getAllValues().get(1).getData();
	}

    @Test
    void missingTenantCannotInheritAnotherMqWorkersContext() {
        com.luohuo.basic.context.ContextUtil.setTenantId(999L);
        var missingTenant = buildDto();
        missingTenant.setTenantId(null);
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(missingTenant));
        assertNull(com.luohuo.basic.context.ContextUtil.getTenantId());
        verify(pushService, never()).withMessageTransaction(any(Runnable.class));
    }

	@Test
	@DisplayName("#24: creator≠originalFromUid 时, others 推送的 fromUser.userType/name 被纠正回 originalFromUid 的(3/Alice)")
	void callOthersPushCorrectsUserTypeAndName() {
		Message message = buildCallMessage(200L); // creator=200 ≠ original=100
		Room room = buildGroupRoom();
		when(messageDao.getById(MSG_ID)).thenReturn(message);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID))
				.thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
		// 可变 Set，因为分支里会 onlineUsersList.remove(uid)
		when(onlineService.getOnlineUsersList(any()))
				.thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
		stubGetMsgRespByFromUid(message);

		consumer.onMessage(buildDto());

		ChatMessageResp.UserInfo fu = captureOthersPushedData().getFromUser();
		assertEquals("100", fu.getUid(), "uid 应被纠正回 originalFromUid");
		assertEquals(3, fu.getUserType(),
				"userType 应被纠正回 originalFromUid 的(3)，未修复时会是 creator 的(4)");
		assertEquals("Alice", fu.getName(),
				"name 应被纠正回 originalFromUid 的(Alice)，未修复时会是 creator 的(Bot)");
	}

	@Test
	@DisplayName("#24 guard: creator==originalFromUid 时 fix 不破坏正常情形(仍是 100/3/Alice)")
	void callOthersPushWhenCreatorEqualsOriginalKeepsCorrectUserTypeAndName() {
		Message message = buildCallMessage(ORIGINAL_FROM_UID); // creator==original==100
		Room room = buildGroupRoom();
		when(messageDao.getById(MSG_ID)).thenReturn(message);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID))
				.thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
		when(onlineService.getOnlineUsersList(any()))
				.thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
		stubGetMsgRespByFromUid(message);

		consumer.onMessage(buildDto());

		ChatMessageResp.UserInfo fu = captureOthersPushedData().getFromUser();
		assertEquals("100", fu.getUid());
		assertEquals(3, fu.getUserType(), "creator==original 时 fix 用相同值覆盖，无副作用");
		assertEquals("Alice", fu.getName(), "creator==original 时 fix 用相同值覆盖，无副作用");
	}

    @Test
    void directAiclawKeepsDistinctPayloadsForBothDurableAndDelayedPaths() {
        Message message = new Message();
        message.setId(MSG_ID);
        message.setRoomId(ROOM_ID);
        message.setFromUid(ORIGINAL_FROM_UID);
        message.setType(MessageTypeEnum.TEXT.getType());
        Room room = new Room();
        room.setId(ROOM_ID);
        room.setType(RoomTypeEnum.FRIEND.getType());
        RoomFriend friendship = new RoomFriend();
        friendship.setUid1(ORIGINAL_FROM_UID);
        friendship.setUid2(OTHER_MEMBER_UID);
        when(messageDao.getById(MSG_ID)).thenReturn(message);
        when(roomCache.get(ROOM_ID)).thenReturn(room);
        when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(friendship);
        when(onlineService.getOnlineUsersList(any())).thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
        ChatMessageResp plain = resp("100", 3, "plain");
        ChatMessageResp enriched = resp("100", 3, "enriched");
        when(chatService.getMsgResp(eq(message), isNull())).thenReturn(plain);
        when(aiclawParticipant.resolveDirectChatDelivery(eq(message), eq(room), any()))
                .thenReturn(java.util.Optional.of(new AiclawParticipant.DirectChatDelivery(OTHER_MEMBER_UID, enriched)));
        consumer.onMessage(buildDto());
        ArgumentCaptor<WsBaseResp> reliable = ArgumentCaptor.forClass(WsBaseResp.class);
        verify(pushService, times(2)).sendReliablePushMsg(reliable.capture(), anyList(), eq(MSG_ID), eq(ORIGINAL_FROM_UID));
        assertEquals("plain", ((ChatMessageResp) reliable.getAllValues().get(0).getData()).getFromUser().getName());
        assertEquals("enriched", ((ChatMessageResp) reliable.getAllValues().get(1).getData()).getFromUser().getName());
        org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();
        ArgumentCaptor<WsBaseResp> delayed = ArgumentCaptor.forClass(WsBaseResp.class);
        verify(pushService, times(2)).sendPushMsgWithRetry(delayed.capture(), anyList(), eq(MSG_ID), eq(ORIGINAL_FROM_UID));
        assertEquals("plain", ((ChatMessageResp) delayed.getAllValues().get(0).getData()).getFromUser().getName());
        assertEquals("enriched", ((ChatMessageResp) delayed.getAllValues().get(1).getData()).getFromUser().getName());
    }

    @Test
    void rollbackDoesNotScheduleSecondHopDelayOrInflightCache() {
        Message message = buildCallMessage(200L);
        when(messageDao.getById(MSG_ID)).thenReturn(message);
        when(roomCache.get(ROOM_ID)).thenReturn(buildGroupRoom());
        when(groupMemberCache.getMemberExceptUidList(ROOM_ID)).thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
        when(onlineService.getOnlineUsersList(any())).thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
        stubGetMsgRespByFromUid(message);
        consumer.onMessage(buildDto());
        // A real rollback never invokes registered afterCommit callbacks.
        verify(pushService, times(2)).sendReliablePushMsg(any(), anyList(), eq(MSG_ID), eq(ORIGINAL_FROM_UID));
        verify(pushService, never()).sendPushMsgWithRetry(any(), anyList(), any(), any());
        verifyNoInteractions(cachePlusOps);
    }

    @Test
    void failedInflightCacheStillSchedulesDelayedRetry() {
        Message message = buildCallMessage(200L);
        when(messageDao.getById(MSG_ID)).thenReturn(message);
        when(roomCache.get(ROOM_ID)).thenReturn(buildGroupRoom());
        when(groupMemberCache.getMemberExceptUidList(ROOM_ID)).thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
        when(onlineService.getOnlineUsersList(any())).thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
        stubGetMsgRespByFromUid(message);
        consumer.onMessage(buildDto());
        doThrow(new IllegalStateException("cache unavailable")).when(cachePlusOps).sAdd(any(), eq(MSG_ID));
        org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();
        verify(pushService, times(2)).sendPushMsgWithRetry(any(), anyList(), eq(MSG_ID), eq(ORIGINAL_FROM_UID));
    }

    @Test
    void committedCallSeedsInflightBeforeDelayedRetry() {
        Message message = buildCallMessage(200L);
        when(messageDao.getById(MSG_ID)).thenReturn(message);
        when(roomCache.get(ROOM_ID)).thenReturn(buildGroupRoom());
        when(groupMemberCache.getMemberExceptUidList(ROOM_ID)).thenReturn(List.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID));
        when(onlineService.getOnlineUsersList(any())).thenReturn(new HashSet<>(Set.of(ORIGINAL_FROM_UID, OTHER_MEMBER_UID)));
        stubGetMsgRespByFromUid(message);
        consumer.onMessage(buildDto());
        org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();
        var order = inOrder(cachePlusOps, pushService);
        order.verify(cachePlusOps).sAdd(any(), eq(MSG_ID));
        order.verify(pushService).sendPushMsgWithRetry(any(), anyList(), eq(MSG_ID), eq(ORIGINAL_FROM_UID));
    }
}
