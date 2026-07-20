package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.exception.BizException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

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

		// REQ-004 [S7] 安全(P2)：所有拒绝分支必须抛出完全相同的异常（同 message + 同 code），
		// 否则 "记录不存在" 与 "存在但无权查看" 可被区分，构成 thinkingId 枚举预言机。
		// 下列 helper 触发各拒绝分支并返回抛出的 BizException，供不可区分性断言。

		private BizException rejectFromNotFound() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(null);
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromGroupNonMember() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 1));
			when(roomCache.get(GROUP_ROOM_ID)).thenReturn(groupRoom());
			// 成员列表里有 aiclaw 但没有 caller —— 用 aiclaw 成员身份不能授权 caller
			when(groupMemberCache.getMemberUidList(GROUP_ROOM_ID))
					.thenReturn(List.of(AICLAW_UID, 999L));
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromFriendNonMember() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(FRIEND_ROOM_ID, 1));
			when(roomCache.get(FRIEND_ROOM_ID)).thenReturn(friendRoom());
			RoomFriend rf = new RoomFriend();
			rf.setUid1(AICLAW_UID);
			rf.setUid2(999L);
			when(roomFriendDao.getByRoomId(FRIEND_ROOM_ID)).thenReturn(rf);
			return assertThrows(BizException.class,
					() -> thinkingService.reviewThinking(THINKING_ID, CALLER_UID));
		}

		private BizException rejectFromRoomMissing() {
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 1));
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
			when(thinkingMapper.selectById(THINKING_ID)).thenReturn(record(GROUP_ROOM_ID, 4));
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

		/** 构造一行 thinking（元数据）；mapper 实际不 select content，这里 content 留空即可。 */
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
			// mapper 也刻意不 select content，此处以 mapper 契约 + resp 类型双重保证。
			verify(thinkingMapper).selectThinkingListByTriggerMsgIds(ROOM_ID, triggerIds);
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

		private final Validator validator;

		ByTriggerReqValidation() {
			try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
				this.validator = factory.getValidator();
			}
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
