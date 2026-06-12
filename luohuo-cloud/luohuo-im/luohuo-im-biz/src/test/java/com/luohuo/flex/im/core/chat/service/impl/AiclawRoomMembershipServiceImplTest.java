package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#3: aiclaw 房间成员校验服务测试。
 * 覆盖 REST (ChatServiceImpl) 和 WS (ThinkingController) 共用的校验逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AiclawRoomMembershipServiceImplTest {

	@Mock private RoomCache roomCache;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private RoomFriendDao roomFriendDao;

	@InjectMocks
	private AiclawRoomMembershipServiceImpl membershipService;

	private static final Long AICLAW_UID = 100L;
	private static final Long GROUP_ROOM_ID = 10L;
	private static final Long FRIEND_ROOM_ID = 20L;
	private static final Long UNKNOWN_ROOM_ID = 30L;
	private static final Long MISSING_ROOM_ID = 40L;

	// ==================== 群聊 ====================

	@Nested
	@DisplayName("群聊成员校验")
	class GroupRoomTests {

		private Room groupRoom() {
			Room room = new Room();
			room.setType(1); // GROUP
			return room;
		}

		@Test
		@DisplayName("aiclaw 是群成员 → 通过")
		void member_shouldPass() {
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, 201L, 202L));

			assertDoesNotThrow(() -> membershipService.checkMembership(AICLAW_UID, GROUP_ROOM_ID));
		}

		@Test
		@DisplayName("aiclaw 不是群成员 → 抛 BizException(非房间成员)")
		void notMember_shouldThrow() {
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(201L, 202L));

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, GROUP_ROOM_ID));
			assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("GroupMemberCache 返回 null → 抛 BizException(数据异常)")
		void nullMemberList_shouldThrowDataError() {
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID)).thenReturn(null);

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, GROUP_ROOM_ID));
			assertTrue(ex.getMessage().contains("数据异常"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("GroupMemberCache 返回空列表 → 抛 BizException(非房间成员)")
		void emptyMemberList_shouldThrow() {
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID)).thenReturn(List.of());

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, GROUP_ROOM_ID));
			assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
		}
	}

	// ==================== 私聊 ====================

	@Nested
	@DisplayName("私聊成员校验")
	class FriendRoomTests {

		private Room friendRoom() {
			Room room = new Room();
			room.setType(2); // FRIEND
			return room;
		}

		@Test
		@DisplayName("aiclaw 是 uid1 → 通过")
		void uid1_shouldPass() {
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(201L);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			assertDoesNotThrow(() -> membershipService.checkMembership(AICLAW_UID, FRIEND_ROOM_ID));
		}

		@Test
		@DisplayName("aiclaw 是 uid2 → 通过")
		void uid2_shouldPass() {
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(201L);
			rf.setUid2(AICLAW_UID);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			assertDoesNotThrow(() -> membershipService.checkMembership(AICLAW_UID, FRIEND_ROOM_ID));
		}

		@Test
		@DisplayName("aiclaw 不是私聊参与者 → 抛 BizException(非房间成员)")
		void notMember_shouldThrow() {
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(201L);
			rf.setUid2(202L);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, FRIEND_ROOM_ID));
			assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("RoomFriend 不存在 → 抛 BizException(数据异常)")
		void noRoomFriend_shouldThrowDataError() {
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(null);

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, FRIEND_ROOM_ID));
			assertTrue(ex.getMessage().contains("数据异常"), "实际消息: " + ex.getMessage());
		}
	}

	// ==================== 边界 ====================

	@Nested
	@DisplayName("边界场景")
	class EdgeCaseTests {

		@Test
		@DisplayName("房间不存在 → 抛 BizException(房间不存在)")
		void roomNotFound_shouldThrow() {
			when(roomCache.get(MISSING_ROOM_ID)).thenReturn(null);

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, MISSING_ROOM_ID));
			assertTrue(ex.getMessage().contains("房间不存在"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("未知房间类型 → 抛 BizException(不支持的房间类型)")
		void unknownRoomType_shouldThrow() {
			Room unknownRoom = new Room();
			unknownRoom.setType(99); // 未知类型，既非 GROUP(1) 也非 FRIEND(2)
			when(roomCache.get(UNKNOWN_ROOM_ID)).thenReturn(unknownRoom);

			BizException ex = assertThrows(BizException.class,
					() -> membershipService.checkMembership(AICLAW_UID, UNKNOWN_ROOM_ID));
			assertTrue(ex.getMessage().contains("不支持的房间类型"), "实际消息: " + ex.getMessage());
		}
	}
}
