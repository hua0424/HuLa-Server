package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawThinkingByTriggerReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ThinkingService 写入授权、终态 CAS 与 UTF-8 截断单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ThinkingServiceTest {

	private static final int MAX_BYTES = 200 * 1024;

	@Mock
	private AiclawThinkingMapper thinkingMapper;
	@Mock private AiclawDao aiclawDao;
	@Mock private UserDao userDao;
	@Mock private com.luohuo.flex.common.config.AiclawProperties aiclawProperties;
	@Mock private com.luohuo.flex.im.core.user.service.impl.PushService pushService;
	@Mock private MessageDao messageDao;

	@BeforeEach
	void tenant() { ContextUtil.setTenantId(1L); }

	@AfterEach
	void clearTenant() { ContextUtil.remove(); }

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

	private void activeAgent() { activeAgent(1L); }

	private void activeAgent(Long tenantId) {
		User user = new User();
		user.setUserType(UserTypeEnum.AICLAW.getValue());
		user.setTenantId(tenantId);
		user.setState(0);
		Aiclaw aiclaw = new Aiclaw();
		aiclaw.setTenantId(tenantId);
		aiclaw.setAuthStatus(1);
		when(userDao.getById(100L)).thenReturn(user);
		when(aiclawDao.getByUid(100L)).thenReturn(aiclaw);
		when(thinkingMapper.isCurrentMember(100L, 10L, tenantId)).thenReturn(1);
	}

	@Test
	void startInTenantTwoWritesExplicitTenantTwoNotDefaultOne() {
		ContextUtil.setTenantId(2L);
		activeAgent(2L);
		when(thinkingMapper.insertThinking(anyLong(), eq(2L), eq(100L), eq(10L), isNull())).thenReturn(1);
		Long id = thinkingService.create(100L, 10L, null);
		verify(thinkingMapper).insertThinking(id, 2L, 100L, 10L, null);
	}

	@Test
	void createRejectsCrossRoomTriggerWithoutInsertion() {
		activeAgent();
		Message trigger = new Message();
		trigger.setRoomId(99L);
		trigger.setTenantId(1L);
		when(messageDao.getById(42L)).thenReturn(trigger);
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, 42L));
		verify(thinkingMapper, never()).insertThinking(any(), any(), any(), any(), any());
	}

	@Test
	void startRejectsMissingTenantAndDisabledAgentBeforeInsertion() {
		ContextUtil.remove();
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null));
		verifyNoInteractions(thinkingMapper, userDao);
		ContextUtil.setTenantId(1L);
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null));
		verify(thinkingMapper, never()).insertThinking(any(), any(), any(), any(), any());
	}

	@Test
	void unknownEndStatusRejectedWithoutUpdate() {
		activeAgent();
		assertThrows(BizException.class, () -> thinkingService.finalize(1L, 100L, 10L, "text", 12, "pending", null));
		verify(thinkingMapper, never()).finalizeActive(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void endRejectsForeignRecordBeforeAnyUpdate() {
		activeAgent();
		assertThrows(BizException.class, () -> thinkingService.finalize(1L, 100L, 10L, "text", 12, "complete", null));
		verify(thinkingMapper).selectOwned(1L, 1L, 100L, 10L);
		verify(thinkingMapper, never()).finalizeActive(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void endCasWinnerAndIdenticalRetryNoSecondUpdate() {
		activeAgent();
		AiclawThinking active = existing(1L);
		AiclawThinking completed = existing(1L);
		completed.setStatus(1);
		completed.setContent("text");
		completed.setDurationMs(12);
		when(thinkingMapper.selectOwned(1L, 1L, 100L, 10L)).thenReturn(active, completed, completed);
		when(thinkingMapper.finalizeActive(1L, 1L, 100L, 10L, "text", 12, 1, null)).thenReturn(1);
		assertTrue(thinkingService.finalize(1L, 100L, 10L, "text", 12, "complete", null));
		assertFalse(thinkingService.finalize(1L, 100L, 10L, "text", 12, "complete", null));
		assertThrows(BizException.class, () -> thinkingService.finalize(1L, 100L, 10L, "different", 12, "complete", null));
		verify(thinkingMapper, times(1)).finalizeActive(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void casLoserChecksPersistedWinnerAndTruncatesUtf8() {
		activeAgent();
		String content = "思".repeat(MAX_BYTES / 3 + 1000);
		String expected = "思".repeat(MAX_BYTES / 3);
		AiclawThinking active = existing(1L);
		AiclawThinking completed = existing(1L);
		completed.setStatus(4);
		completed.setContent(expected);
		completed.setDurationMs(12);
		when(thinkingMapper.selectOwned(1L, 1L, 100L, 10L)).thenReturn(active, completed);
		assertFalse(thinkingService.finalize(1L, 100L, 10L, content, 12, "complete", null));
		verify(thinkingMapper).finalizeActive(1L, 1L, 100L, 10L, expected, 12, 4, null);
	}

	@Test
	void agentErrorTextCannotClaimServiceTimeoutStatus() {
		activeAgent();
		when(thinkingMapper.selectOwned(1L, 1L, 100L, 10L)).thenReturn(existing(1L));
		when(thinkingMapper.finalizeActive(1L, 1L, 100L, 10L, "", 12, 2, "request_timeout"))
				.thenReturn(1);
		assertTrue(thinkingService.finalize(1L, 100L, 10L, null, 12, "error", "request_timeout"));
		verify(thinkingMapper).finalizeActive(1L, 1L, 100L, 10L, "", 12, 2, "request_timeout");
	}

	@Test
	void serviceTimeoutOnlyUpdatesMatchingOwnedRecord() {
		when(thinkingMapper.selectOwned(1L, 1L, 100L, 10L)).thenReturn(existing(1L));
		when(thinkingMapper.finalizeActive(1L, 1L, 100L, 10L, "", null, 3, "timeout")).thenReturn(1);
		assertTrue(thinkingService.markError(1L, 100L, 10L, "timeout", true));
		verifyNoInteractions(userDao);
	}

	@Test
	void onlySameOwnedPersistedTerminalProvesRemoteCompletion() {
		AiclawThinking completed = existing(1L);
		completed.setStatus(2);
		when(thinkingMapper.selectOwned(1L, 1L, 100L, 10L)).thenReturn(completed);
		assertTrue(thinkingService.isOwnedTerminal(1L, 100L, 10L));
		assertFalse(thinkingService.isOwnedTerminal(1L, 200L, 10L));
		verify(thinkingMapper).selectOwned(1L, 1L, 200L, 10L);
	}

	@Test
	void sameRunReturnsOriginalIdAndRejectsDifferentRoomOrTrigger() {
		activeAgent();
		AiclawThinking original = existing(456L);
		original.setTriggerMsgId(42L);
		original.setClientRunId("run-a");
		when(thinkingMapper.selectByRun(1L, 100L, "run-a")).thenReturn(original);
		assertEquals(456L, thinkingService.create(100L, 10L, 42L, "run-a").thinkingId());
		assertTrue(thinkingService.create(100L, 10L, 42L, "run-a").replayed());
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, 99L, "run-a"));
		when(thinkingMapper.selectByRun(1L, 100L, "RUN-A")).thenReturn(original);
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, 42L, "RUN-A"));
		when(thinkingMapper.isCurrentMember(100L, 11L, 1L)).thenReturn(1);
		assertThrows(BizException.class, () -> thinkingService.create(100L, 11L, 42L, "run-a"));
		verify(thinkingMapper, never()).insertWithRun(any(), any(), any(), any(), any(), any());
	}

	@Test
	void concurrentStartDuplicateReadsWinningRowAndOtherDuplicateFailsClosed() {
		activeAgent();
		AiclawThinking original = existing(456L);
		original.setClientRunId("run-a");
		when(thinkingMapper.selectByRun(1L, 100L, "run-a")).thenReturn(null, original);
		when(thinkingMapper.insertWithRun(anyLong(), eq(1L), eq(100L), eq(10L), isNull(), eq("run-a")))
				.thenThrow(new DuplicateKeyException("concurrent START"));
		assertEquals(456L, thinkingService.create(100L, 10L, null, "run-a").thinkingId());
		when(thinkingMapper.selectByRun(1L, 100L, "other")).thenReturn(null);
		when(thinkingMapper.insertWithRun(anyLong(), eq(1L), eq(100L), eq(10L), isNull(), eq("other")))
				.thenThrow(new DuplicateKeyException("other constraint"));
		assertThrows(DuplicateKeyException.class, () -> thinkingService.create(100L, 10L, null, "other"));
	}

	@Test
	void endForOldRunCannotFinishNewRunEvenWithSameActorAndRoom() {
		activeAgent();
		AiclawThinking newer = existing(2L);
		newer.setClientRunId("new-run");
		when(thinkingMapper.selectOwned(2L, 1L, 100L, 10L)).thenReturn(newer);
		assertThrows(BizException.class,
				() -> thinkingService.finalize(2L, 100L, 10L, "", 1, "complete", null, "old-run"));
		verify(thinkingMapper, never()).finalizeActive(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void runEndWaitsForPersistedStartReadiness() {
		activeAgent();
		AiclawThinking pending = existing(7L);
		pending.setClientRunId("run-a");
		pending.setStartReady(false);
		when(thinkingMapper.selectOwned(7L, 1L, 100L, 10L)).thenReturn(pending);
		BizException notReady = assertThrows(BizException.class,
				() -> thinkingService.finalize(7L, 100L, 10L, "text", 1, "complete", null, "run-a"));
		assertEquals("thinking_start_pending", notReady.getMessage());
		assertThrows(BizException.class,
				() -> thinkingService.finalize(7L, 100L, 10L, "text", 1, "complete", null));
		verify(thinkingMapper, never()).finalizeActive(any(), any(), any(), any(), any(), any(), any(), any());
		when(thinkingMapper.markStartReady(7L, 1L, 100L, 10L, "run-a")).thenReturn(1);
		assertTrue(thinkingService.markStartReady(7L, 100L, 10L, "run-a"));
		pending.setStartReady(true);
		when(thinkingMapper.finalizeActive(7L, 1L, 100L, 10L, "text", 1, 1, null)).thenReturn(1);
		assertTrue(thinkingService.finalize(7L, 100L, 10L, "text", 1, "complete", null, "run-a"));
	}

	@Test
	void invalidRunAndMissingTenantFailBeforeInsert() {
		ContextUtil.remove();
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null, "run-a"));
		ContextUtil.setTenantId(1L);
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null, " "));
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null, "x".repeat(129)));
		assertThrows(BizException.class, () -> thinkingService.create(100L, 10L, null, "run-a "));
		verify(thinkingMapper, never()).insertWithRun(any(), any(), any(), any(), any(), any());
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
			AiclawThinking row = record(GROUP_ROOM_ID, 1);
			row.setTriggerMsgId(9007199254740993L);
			row.setClientRunId("run-123");
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(row);
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, CALLER_UID, 999L));

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals("555", resp.getThinkingId());
			assertEquals("10", resp.getRoomId());
			assertEquals("100", resp.getAiclawUid());
			assertEquals("9007199254740993", resp.getTriggerMsgId());
			assertEquals("run-123", resp.getClientRunId());
			assertEquals("完整的思考内容", resp.getContent());
			assertEquals(1, resp.getStatus());
			assertEquals(1234, resp.getDurationMs());
			// aichatoverview#351：detail ETag 与元数据同一正文字节算出。
			assertEquals(ThinkingBodyHash.sha256Hex("完整的思考内容"), resp.getBodyETag());
		}

		@Test
		@DisplayName("私聊：caller 是 uid1 → 返回内容")
		void friendMemberUid1_returnsContent() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(FRIEND_ROOM_ID, 1));
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
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(CALLER_UID);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals("完整的思考内容", resp.getContent());
		}

		// REQ-004 [S7] 安全(P2)：所有拒绝分支必须抛出完全相同的异常（同 message + 同 code），
		// 否则 "记录不存在" 与 "存在但无权查看" 可被区分，构成 thinkingId 枚举预言机。
		// 下列 helper 触发各拒绝分支并返回抛出的 BizException，供不可区分性断言。

		private BizException rejectFromNotFound() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(null);
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromGroupNonMember() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			// 成员列表里有 aiclaw 但没有 caller —— 用 aiclaw 成员身份不能授权 caller
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, 999L));
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromFriendNonMember() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(999L);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromRoomMissing() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(null);
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		@Test
		@DisplayName("群聊：caller 非群成员 → 拒绝 BizException，不返回内容（IDOR 核心）")
		void groupNonMember_rejected() {
			BizException ex = rejectFromGroupNonMember();
			// 安全：对外消息为统一拒绝文案，绝不暴露 "非成员" 这类可区分原因
			assertEquals("思考记录不存在或无权查看", ex.getMessage(), "拒绝消息必须是统一文案，不得泄露真实原因");
		}

		@Test
		@DisplayName("其他房间的 thinking 即使 ID 已知，也不能泄漏新增 run/trigger 关联")
		void foreignRoomMemberCannotReadCorrelation() {
			AiclawThinking foreign = record(99L, 1);
			foreign.setTriggerMsgId(9007199254740993L);
			foreign.setClientRunId("secret-run-context");
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(foreign);
			when(roomCache.get(99L)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(99L)).thenReturn(List.of(AICLAW_UID, 999L));
			BizException denied = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertEquals("思考记录不存在或无权查看", denied.getMessage());
		}

		@Test
		@DisplayName("私聊：caller 非参与者 → 拒绝 BizException，不返回内容（IDOR 核心）")
		void friendNonMember_rejected() {
			BizException ex = rejectFromFriendNonMember();
			assertEquals("思考记录不存在或无权查看", ex.getMessage(), "拒绝消息必须是统一文案，不得泄露真实原因");
		}

		@Test
		@DisplayName("thinkingId 不存在 → 统一拒绝 BizException，不触碰房间校验")
		void notFound_rejected() {
			BizException ex = rejectFromNotFound();
			assertEquals("思考记录不存在或无权查看", ex.getMessage(), "拒绝消息必须是统一文案，不得泄露真实原因");
			verifyNoInteractions(roomCache, groupMemberCache, roomFriendDao);
		}

		// ==================== REQ-004 [S7] 安全核心：枚举预言机不可区分性 ====================

		@Test
		@DisplayName("租户缺失或记录位于其他租户时，不读取房间或元数据")
		void missingOrForeignTenantCannotReadCorrelation() {
			ContextUtil.remove();
			assertThrows(BizException.class, () -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			verifyNoInteractions(thinkingMapper, roomCache);
			ContextUtil.setTenantId(2L);
			BizException denied = assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
			assertEquals("思考记录不存在或无权查看", denied.getMessage());
			verify(thinkingMapper).selectInTenant(THINKING_ID, 2L);
			verifyNoInteractions(roomCache);
		}

		@Test
		@DisplayName("安全：不存在 与 非成员 的拒绝异常必须完全一致（消息+code 不可区分）")
		void notFound_and_nonMember_areIndistinguishable() {
			BizException notFound = rejectFromNotFound();

			// 重置 mapper 桩，复用同一实例触发非成员分支
			reset(thinkingMapper);
			BizException groupNonMember = rejectFromGroupNonMember();

			// 安全属性：两条拒绝路径的 message 与 code 必须完全相同，调用方无从分辨记录是否存在
			assertEquals(notFound.getMessage(), groupNonMember.getMessage(),
					"不存在 与 非成员 的拒绝消息必须相同（不可区分性是本测试的安全属性）");
			assertEquals(notFound.getCode(), groupNonMember.getCode(),
					"不存在 与 非成员 的拒绝 code 必须相同");
		}

		@Test
		@DisplayName("安全：全部四个拒绝分支（不存在/房间缺失/群非成员/私聊非参与者）的 message+code 两两一致")
		void allRejectionBranches_areIndistinguishable() {
			BizException notFound = rejectFromNotFound();
			reset(thinkingMapper, roomCache, groupMemberCache, roomFriendDao);

			BizException roomMissing = rejectFromRoomMissing();
			reset(thinkingMapper, roomCache, groupMemberCache, roomFriendDao);

			BizException groupNonMember = rejectFromGroupNonMember();
			reset(thinkingMapper, roomCache, groupMemberCache, roomFriendDao);

			BizException friendNonMember = rejectFromFriendNonMember();

			List<BizException> all = List.of(notFound, roomMissing, groupNonMember, friendNonMember);
			String expectedMsg = notFound.getMessage();
			int expectedCode = notFound.getCode();
			for (BizException ex : all) {
				assertEquals(expectedMsg, ex.getMessage(),
						"所有拒绝分支必须使用同一 message，否则可枚举 thinkingId");
				assertEquals(expectedCode, ex.getCode(),
						"所有拒绝分支必须使用同一 code，否则可枚举 thinkingId");
			}
		}

		@Test
		@DisplayName("status==4（超长截断）：成员 caller 拿到 status=4，前端据此显示截断提示")
		void status4_truncationHintExposed() {
			when(thinkingMapper.selectInTenant(THINKING_ID, 1L)).thenReturn(record(GROUP_ROOM_ID, 4));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(CALLER_UID));

			AiclawThinkingDetailResp resp = thinkingService.reviewThinking(THINKING_ID, CALLER_UID);

			assertEquals(4, resp.getStatus(), "status=4 必须透传给前端以提示截断");
			assertEquals("完整的思考内容", resp.getContent());
		}

		@Test
		@DisplayName("房间不存在 → 统一拒绝，不返回内容（不暴露 '房间不存在' 这类可区分原因）")
		void roomMissing_rejected() {
			BizException ex = rejectFromRoomMissing();
			assertEquals("思考记录不存在或无权查看", ex.getMessage(), "拒绝消息必须是统一文案，不得泄露真实原因");
		}
	}

	// ==================== issue #180：按触发消息批量反查 thinking 元数据（metadata only） ====================

	@Nested
	@DisplayName("listThinkingByTriggerMsgIds 批量反查（成员闸门 + 元数据 only + id 升序）")
	class ListThinkingByTriggerMsgIds {

		private static final Long ROOM_ID = 10L;
		private static final Long CALLER_UID = 300L;

		private Room groupRoom() {
			Room room = new Room();
			room.setType(1); // GROUP
			return room;
		}

		/** caller 是群成员：桩通 checkCurrentUserMembership 群分支。 */
		private void stubMember() {
			when(roomCache.get(ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(100L, CALLER_UID, 999L));
		}

		/** 构造一行 thinking（元数据）；content 仅 mapper 附带查出供服务端算 ETag，不进入响应体。 */
		private AiclawThinking row(long id, long aiclawUid, long triggerMsgId) {
			AiclawThinking t = AiclawThinking.builder()
					.aiclawUid(aiclawUid)
					.roomId(ROOM_ID)
					.triggerMsgId(triggerMsgId)
					.status(1)
					.durationMs(1234)
					.hasResponse(1)
					.build();
			t.setId(id);
			t.setCreateTime(LocalDateTime.now());
			return t;
		}

		@Test
		@DisplayName("成员：mapper 返回行 → 映射为元数据（含全部字段，且不查/不设 content）")
		void member_returnsMappedRows() {
			stubMember();
			List<Long> triggerIds = List.of(7001L, 7002L);
			when(thinkingMapper.selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds))
					.thenReturn(List.of(row(1L, 100L, 7001L), row(2L, 100L, 7002L)));

			List<AiclawThinkingListItemResp> resp =
					thinkingService.listThinkingByTriggerMsgIds(ROOM_ID, CALLER_UID, triggerIds);

			assertEquals(2, resp.size());
			AiclawThinkingListItemResp first = resp.get(0);
			assertEquals(1L, first.getId());
			assertEquals(100L, first.getAiclawUid());
			assertEquals(7001L, first.getTriggerMsgId());
			assertEquals(1, first.getStatus());
			assertEquals(1234, first.getDurationMs());
			assertEquals(1, first.getHasResponse());
			assertNotNull(first.getCreateTime());
			// 元数据 only：resp 项没有 content 字段（AiclawThinkingListItemResp 本身不含 content）——
			// mapper 附带查出的 content 只用于服务端算 bodyETag，此处以 mapper 契约 + resp 类型双重保证。
			verify(thinkingMapper).selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds);
		}

		@Test
		@DisplayName("#351：元数据 bodyETag 与同一正文的 detail ETag 一致（只判相等）")
		void member_bodyETagMatchesDetailBytes() {
			stubMember();
			List<Long> triggerIds = List.of(7001L);
			AiclawThinking withBody = row(7L, 100L, 7001L);
			withBody.setContent("完整的思考内容");
			AiclawThinking emptyBody = row(8L, 200L, 7001L);
			emptyBody.setContent("");
			AiclawThinking noBody = row(9L, 300L, 7001L);
			noBody.setContent(null);
			when(thinkingMapper.selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds))
					.thenReturn(List.of(withBody, emptyBody, noBody));

			List<AiclawThinkingListItemResp> resp =
					thinkingService.listThinkingByTriggerMsgIds(ROOM_ID, CALLER_UID, triggerIds);

			assertEquals(3, resp.size());
			assertEquals(ThinkingBodyHash.sha256Hex("完整的思考内容"), resp.get(0).getBodyETag());
			// 成功空正文有效：空串有确定 ETag；null 正文无 ETag。
			assertEquals(ThinkingBodyHash.sha256Hex(""), resp.get(1).getBodyETag());
			assertNull(resp.get(2).getBodyETag());
		}

		@Test
		@DisplayName("非成员：群成员列表不含 caller → BizException（统一文案），且 mapper 从不被调用（授权先于查询）")
		void nonMember_rejected_mapperNeverCalled() {
			when(roomCache.get(ROOM_ID)).thenReturn(groupRoom());
			when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(100L, 999L));

			BizException ex = assertThrows(BizException.class,
					() -> thinkingService.listThinkingByTriggerMsgIds(ROOM_ID, CALLER_UID, List.of(7001L)));
			assertEquals("思考记录不存在或无权查看", ex.getMessage(), "拒绝消息必须是统一文案");

			verify(thinkingMapper, never()).selectThinkingListByTriggerMsgIds(anyLong(), anyList());
		}

		@Test
		@DisplayName("空结果：mapper 返回空 → service 返回空列表")
		void emptyResult() {
			stubMember();
			List<Long> triggerIds = List.of(7001L, 7002L);
			when(thinkingMapper.selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds))
					.thenReturn(new ArrayList<>());

			List<AiclawThinkingListItemResp> resp =
					thinkingService.listThinkingByTriggerMsgIds(ROOM_ID, CALLER_UID, triggerIds);

			assertTrue(resp.isEmpty());
		}

		@Test
		@DisplayName("同一 triggerMsgId 多 aiclaw → 多行按 id 升序全部返回")
		void multiAiclaw_sameTrigger_returnsAllInIdAsc() {
			stubMember();
			List<Long> triggerIds = List.of(7001L);
			// 两行相同 triggerMsgId、不同 aiclawUid、id 升序（mapper 契约为 ORDER BY id ASC）
			when(thinkingMapper.selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds))
					.thenReturn(List.of(row(1L, 100L, 7001L), row(2L, 200L, 7001L)));

			List<AiclawThinkingListItemResp> resp =
					thinkingService.listThinkingByTriggerMsgIds(ROOM_ID, CALLER_UID, triggerIds);

			assertEquals(2, resp.size());
			assertEquals(1L, resp.get(0).getId());
			assertEquals(100L, resp.get(0).getAiclawUid());
			assertEquals(2L, resp.get(1).getId());
			assertEquals(200L, resp.get(1).getAiclawUid());
			// 两行共享 triggerMsgId
			assertEquals(7001L, resp.get(0).getTriggerMsgId());
			assertEquals(7001L, resp.get(1).getTriggerMsgId());
		}
	}

	// ==================== issue #180：AiclawThinkingByTriggerReq 校验（jakarta bean validation） ====================

	@Nested
	@DisplayName("AiclawThinkingByTriggerReq 校验（roomId 非空 / triggerMsgIds 非空 + 上限 100）")
	class ByTriggerReqValidation {

		private static ValidatorFactory factory;
		private static Validator validator;

		@BeforeAll
		static void setUpValidator() {
			factory = Validation.buildDefaultValidatorFactory();
			validator = factory.getValidator();
		}

		@AfterAll
		static void tearDownValidator() {
			factory.close();
		}

		private Set<String> violatedProps(AiclawThinkingByTriggerReq req) {
			Set<ConstraintViolation<AiclawThinkingByTriggerReq>> violations = validator.validate(req);
			return violations.stream()
					.map(v -> v.getPropertyPath().toString())
					.collect(Collectors.toSet());
		}

		@Test
		@DisplayName("101 个 id → triggerMsgIds 违约")
		void overCap_violatesTriggerMsgIds() {
			List<Long> ids = LongStream.rangeClosed(1, 101).boxed().collect(Collectors.toList());
			AiclawThinkingByTriggerReq req = AiclawThinkingByTriggerReq.builder()
					.roomId(10L).triggerMsgIds(ids).build();
			assertTrue(violatedProps(req).contains("triggerMsgIds"), "101 个 id 应触发 triggerMsgIds 上限违约");
		}

		@Test
		@DisplayName("空列表 → triggerMsgIds 违约")
		void emptyList_violatesTriggerMsgIds() {
			AiclawThinkingByTriggerReq req = AiclawThinkingByTriggerReq.builder()
					.roomId(10L).triggerMsgIds(new ArrayList<>()).build();
			assertTrue(violatedProps(req).contains("triggerMsgIds"), "空列表应触发 @NotEmpty");
		}

		@Test
		@DisplayName("roomId 为 null → roomId 违约")
		void nullRoomId_violatesRoomId() {
			AiclawThinkingByTriggerReq req = AiclawThinkingByTriggerReq.builder()
					.roomId(null).triggerMsgIds(List.of(1L, 2L, 3L)).build();
			assertTrue(violatedProps(req).contains("roomId"), "roomId 为 null 应触发 @NotNull");
		}

		@Test
		@DisplayName("合法 {roomId, [1,2,3]} → 零违约")
		void valid_noViolations() {
			AiclawThinkingByTriggerReq req = AiclawThinkingByTriggerReq.builder()
					.roomId(10L).triggerMsgIds(List.of(1L, 2L, 3L)).build();
			assertTrue(violatedProps(req).isEmpty(), "合法请求不应有任何违约");
		}
	}
}
