package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.im.core.chat.dao.*;
import com.luohuo.flex.im.core.chat.service.ContactService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.MsgCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.strategy.msg.AbstractMsgHandler;
import com.luohuo.flex.im.core.chat.service.strategy.msg.MsgHandlerFactory;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#3: aiclaw 房间成员校验 + 短回复 skip 下线测试。
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
    @Mock private PushService pushService;

    @InjectMocks
    private ChatServiceImpl chatService;

    private static final Long AICLAW_UID = 100L;
    private static final Long NORMAL_UID = 200L;
    private static final Long ROOM_ID = 10L;
    private static final Long FRIEND_ROOM_ID = 20L;

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

        Room friendRoom = new Room();
        friendRoom.setType(RoomTypeEnum.FRIEND.getType());
        when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom);
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

    // ==================== Task 1: aiclaw 群聊成员校验 ====================

    @Test
    @DisplayName("aiclaw 在群聊中且是成员 → 正常发送")
    void aiclawInGroup_member_shouldSend() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, 201L, 202L));
        when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(mockGroupMember());

        Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), AICLAW_UID);
        assertEquals(999L, msgId);
    }

    @Test
    @DisplayName("aiclaw 在群聊中但不是成员 → 抛 BizException(非房间成员)")
    void aiclawInGroup_notMember_shouldThrow() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(201L, 202L)); // 不含 aiclaw

        ChatMessageReq req = baseReq(ROOM_ID);
        BizException ex = assertThrows(BizException.class, () -> chatService.sendMsg(req, AICLAW_UID));
        assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
    }

    @Test
    @DisplayName("aiclaw 在群聊中 groupMemberCache 返回 null → 抛 BizException(非房间成员)")
    void aiclawInGroup_nullMemberList_shouldThrow() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(null);

        ChatMessageReq req = baseReq(ROOM_ID);
        BizException ex = assertThrows(BizException.class, () -> chatService.sendMsg(req, AICLAW_UID));
        assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
    }

    // ==================== Task 1: aiclaw 私聊成员校验 ====================

    @Test
    @DisplayName("aiclaw 在私聊中且是成员(uid1) → 正常发送")
    void aiclawInFriend_uid1_shouldSend() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());

        RoomFriend rf = new RoomFriend();
        rf.setUid1(AICLAW_UID);
        rf.setUid2(201L);
        when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

        Long msgId = sendMsgWithMockedHandler(baseReq(FRIEND_ROOM_ID), AICLAW_UID);
        assertEquals(999L, msgId);
    }

    @Test
    @DisplayName("aiclaw 在私聊中且是成员(uid2) → 正常发送")
    void aiclawInFriend_uid2_shouldSend() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());

        RoomFriend rf = new RoomFriend();
        rf.setUid1(201L);
        rf.setUid2(AICLAW_UID);
        when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

        Long msgId = sendMsgWithMockedHandler(baseReq(FRIEND_ROOM_ID), AICLAW_UID);
        assertEquals(999L, msgId);
    }

    @Test
    @DisplayName("aiclaw 在私聊中但不是成员 → 抛 BizException(非房间成员)")
    void aiclawInFriend_notMember_shouldThrow() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());

        RoomFriend rf = new RoomFriend();
        rf.setUid1(201L);
        rf.setUid2(202L);
        when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

        ChatMessageReq req = baseReq(FRIEND_ROOM_ID);
        BizException ex = assertThrows(BizException.class, () -> chatService.sendMsg(req, AICLAW_UID));
        assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
    }

    @Test
    @DisplayName("aiclaw 在私聊中 RoomFriend 不存在 → 抛 BizException(非房间成员)")
    void aiclawInFriend_noRoomFriend_shouldThrow() {
        stubRoomCache();
        when(userCache.get(AICLAW_UID)).thenReturn(aiclawUser());
        when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(null);

        ChatMessageReq req = baseReq(FRIEND_ROOM_ID);
        BizException ex = assertThrows(BizException.class, () -> chatService.sendMsg(req, AICLAW_UID));
        assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
    }

    // ==================== Task 1: 普通用户绕过校验 ====================

    @Test
    @DisplayName("普通用户在群聊中 → 绕过 aiclaw 校验，正常发送")
    void normalUserInGroup_shouldBypassAiclawCheck() {
        stubRoomCache();
        when(userCache.get(NORMAL_UID)).thenReturn(normalUser());
        when(groupMemberDao.getMember(ROOM_ID, NORMAL_UID)).thenReturn(mockGroupMember());

        Long msgId = sendMsgWithMockedHandler(baseReq(ROOM_ID), NORMAL_UID);
        assertEquals(999L, msgId);

        // 普通用户不触发 groupMemberCache.getMemberUidList（aiclaw 校验专用）
        // 但 syncContactLastMsgId 会调用，所以这里只验证 sendMsg 成功即可
    }

    @Test
    @DisplayName("普通用户在私聊中 → 绕过 aiclaw 校验，正常发送")
    void normalUserInFriend_shouldBypassAiclawCheck() {
        stubRoomCache();
        when(userCache.get(NORMAL_UID)).thenReturn(normalUser());

        RoomFriend rf = new RoomFriend();
        rf.setUid1(NORMAL_UID);
        rf.setUid2(201L);
        when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

        Long msgId = sendMsgWithMockedHandler(baseReq(FRIEND_ROOM_ID), NORMAL_UID);
        assertEquals(999L, msgId);
    }

    // ==================== Task 2: 短回复不再 skip ====================

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
}
