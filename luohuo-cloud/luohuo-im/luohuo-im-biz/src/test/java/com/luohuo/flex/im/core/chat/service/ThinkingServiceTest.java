package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * REQ-004 [S4] ThinkingService.finalize 全文落库 + 200KB UTF-8 安全截断 + 状态映射，
 * 以及 resolveActiveThinking 反查的纯 Mockito 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ThinkingServiceTest {

	private static final int MAX_BYTES = 200 * 1024;

	@Mock
	private AiclawThinkingMapper thinkingMapper;

	@Mock
	private RoomCache roomCache;

	@Mock
	private GroupMemberCache groupMemberCache;

	@Mock
	private RoomFriendDao roomFriendDao;

	@InjectMocks
	private ThinkingService thinkingService;

	private AiclawThinking existing(Long id) {
		AiclawThinking t = AiclawThinking.builder()
				.aiclawUid(100L)
				.roomId(10L)
				.content("")
				.hasResponse(0)
				.status(0)
				.build();
		t.setId(id);
		return t;
	}

	// ==================== slice 1：content 未超长 + complete → status=1 ====================

	@Test
	@DisplayName("finalize: 内容未超长且 complete → 全文落库 + duration + status=1")
	void finalize_underLimit_complete_persistsFullContentStatus1() {
		Long id = 1L;
		when(thinkingMapper.selectById(id)).thenReturn(existing(id));

		String content = "这是一段完整的思考文本";
		thinkingService.finalize(id, content, 1234, "complete", null);

		ArgumentCaptor<AiclawThinking> captor = ArgumentCaptor.forClass(AiclawThinking.class);
		verify(thinkingMapper).updateById(captor.capture());
		AiclawThinking saved = captor.getValue();
		assertEquals(content, saved.getContent());
		assertEquals(1234, saved.getDurationMs());
		assertEquals(1, saved.getStatus());
	}

	// ==================== slice 2：content 超长 + complete → 截断 + status=4，无损 ====================

	@Test
	@DisplayName("finalize: 内容超 200KB 且 complete → UTF-8 安全截断 + status=4 + 不破坏多字节字符")
	void finalize_overLimit_complete_truncatesStatus4_noCorruption() {
		Long id = 2L;
		when(thinkingMapper.selectById(id)).thenReturn(existing(id));

		// "思" = 3 字节，重复至超过 200*1024 字节（边界落在多字节字符中间以验证不破坏字符）
		int repeat = (MAX_BYTES / 3) + 1000;
		String content = "思".repeat(repeat);
		assertTrue(content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES, "构造的内容应超过上限");

		thinkingService.finalize(id, content, 500, "complete", null);

		ArgumentCaptor<AiclawThinking> captor = ArgumentCaptor.forClass(AiclawThinking.class);
		verify(thinkingMapper).updateById(captor.capture());
		String stored = captor.getValue().getContent();

		assertTrue(stored.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "截断后字节数应 <= 200KB");
		assertEquals(4, captor.getValue().getStatus());
		// 无损：每个字符都是 "思"，没有 U+FFFD 或半个字符
		assertTrue(stored.chars().allMatch(c -> c == '思'), "截断结果不得损坏多字节字符");
		// 字节上限内能容纳的完整 "思" 个数
		int expectedChars = MAX_BYTES / 3;
		assertEquals(expectedChars, stored.length(), "应保留尽可能多的完整字符");
	}

	// ==================== slice 3：error + 超长 → 保持 status=2，仍截断 ====================

	@Test
	@DisplayName("finalize: error 且内容超长 → 保持 status=2 + 仍截断 + errorCode 落库")
	void finalize_error_overLimit_keepsStatus2_stillTruncates() {
		Long id = 3L;
		when(thinkingMapper.selectById(id)).thenReturn(existing(id));

		int repeat = (MAX_BYTES / 3) + 1000;
		String content = "思".repeat(repeat);

		thinkingService.finalize(id, content, 500, "error", "some_error");

		ArgumentCaptor<AiclawThinking> captor = ArgumentCaptor.forClass(AiclawThinking.class);
		verify(thinkingMapper).updateById(captor.capture());
		AiclawThinking saved = captor.getValue();

		assertEquals(2, saved.getStatus(), "error 路径不得降级为 4");
		assertEquals("some_error", saved.getErrorCode());
		assertTrue(saved.getContent().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
	}

	// ==================== slice 4：error 含 timeout → status=3 ====================

	@Test
	@DisplayName("finalize: error 含 timeout → status=3")
	void finalize_error_timeout_status3() {
		Long id = 4L;
		when(thinkingMapper.selectById(id)).thenReturn(existing(id));

		thinkingService.finalize(id, "short", 100, "error", "request_timeout");

		ArgumentCaptor<AiclawThinking> captor = ArgumentCaptor.forClass(AiclawThinking.class);
		verify(thinkingMapper).updateById(captor.capture());
		assertEquals(3, captor.getValue().getStatus());
		assertEquals("request_timeout", captor.getValue().getErrorCode());
	}

	// ==================== slice 5：resolveActiveThinking ====================

	@Test
	@DisplayName("resolveActiveThinking: mapper 返回 id → 透传")
	void resolveActiveThinking_returnsId() {
		when(thinkingMapper.selectActiveThinkingId(100L, 10L)).thenReturn(777L);
		assertEquals(777L, thinkingService.resolveActiveThinking(100L, 10L));
	}

	@Test
	@DisplayName("resolveActiveThinking: mapper 返回 null → null")
	void resolveActiveThinking_returnsNull() {
		when(thinkingMapper.selectActiveThinkingId(100L, 10L)).thenReturn(null);
		assertNull(thinkingService.resolveActiveThinking(100L, 10L));
	}

	// ==================== REQ-004 [S7]：reviewThinking IDOR 安全授权 ====================

	@Nested
	@DisplayName("reviewThinking 回看授权（IDOR 防护）")
	class ReviewThinkingTests {

		private static final Long THINKING_ID = 555L;
		private static final Long GROUP_ROOM_ID = 10L;
		private static final Long FRIEND_ROOM_ID = 20L;
		// caller（当前登录用户），与产生 thinking 的 aiclaw 是不同主体
		private static final Long CALLER_UID = 300L;
		private static final Long AICLAW_UID = 100L;

		private Room groupRoom() {
			Room room = new Room();
			room.setType(1); // GROUP
			return room;
		}

		private Room friendRoom() {
			Room room = new Room();
			room.setType(2); // FRIEND
			return room;
		}

		private AiclawThinking record(Long roomId, Integer status) {
			AiclawThinking t = AiclawThinking.builder()
					.aiclawUid(AICLAW_UID)
					.roomId(roomId)
					.content("完整的思考内容")
					.durationMs(1234)
					.status(status)
					.build();
			t.setId(THINKING_ID);
			return t;
		}

		@Test
		@DisplayName("群聊：caller 是群成员 → 返回 content/status/durationMs")
		void groupMember_returnsContent() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, CALLER_UID, 999L));

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals("完整的思考内容", resp.getContent());
			assertEquals(1, resp.getStatus());
			assertEquals(1234, resp.getDurationMs());
		}

		@Test
		@DisplayName("私聊：caller 是 uid1 → 返回内容")
		void friendMemberUid1_returnsContent() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(CALLER_UID);
			rf.setUid2(AICLAW_UID);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals("完整的思考内容", resp.getContent());
			assertEquals(1, resp.getStatus());
		}

		@Test
		@DisplayName("私聊：caller 是 uid2 → 返回内容")
		void friendMemberUid2_returnsContent() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(CALLER_UID);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals("完整的思考内容", resp.getContent());
		}

		@Test
		@DisplayName("群聊：caller 非群成员 → 拒绝 BizException，不返回内容（IDOR 核心）")
		void groupNonMember_rejected() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			// 成员列表里有 aiclaw 但没有 caller —— 用 aiclaw 成员身份不能授权 caller
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, 999L));

			BizException ex = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("私聊：caller 非参与者 → 拒绝 BizException，不返回内容（IDOR 核心）")
		void friendNonMember_rejected() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(999L);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			BizException ex = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertTrue(ex.getMessage().contains("非房间成员"), "实际消息: " + ex.getMessage());
		}

		@Test
		@DisplayName("thinkingId 不存在 → BizException(不存在)，不触碰房间校验")
		void notFound_rejected() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(null);

			BizException ex = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertTrue(ex.getMessage().contains("不存在"), "实际消息: " + ex.getMessage());
			verifyNoInteractions(roomCache, groupMemberCache, roomFriendDao);
		}

		@Test
		@DisplayName("status==4（超长截断）：成员 caller 拿到 status=4，前端据此显示截断提示")
		void status4_truncationHintExposed() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 4));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(CALLER_UID));

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals(4, resp.getStatus(), "status=4 必须透传给前端以提示截断");
			assertEquals("完整的思考内容", resp.getContent());
		}

		@Test
		@DisplayName("房间不存在 → 拒绝，不返回内容")
		void roomMissing_rejected() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(null);

			BizException ex = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertTrue(ex.getMessage().contains("房间不存在"), "实际消息: " + ex.getMessage());
		}
	}
}
