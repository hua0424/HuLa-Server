package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.request.member.MemberReq;
import com.luohuo.flex.im.domain.vo.resp.room.AiclawMemberResp;
import com.luohuo.flex.model.entity.ws.ChatMemberResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-010 S4 (#94): aiclaw 群成员列表端点 —— 服务层硬鉴权 + 在线过滤 + 精简响应。
 *
 * <p>#170: aiclawListMembers 从 RoomAppServiceImpl 下沉到 {@link GroupMembershipManager}，
 * 本用例整体 repoint 到该协作者，断言与错误文案契约一字不改。</p>
 *
 * <p>ADR-0002「server 侧硬鉴权」：AI agent 不得读取自己未加入的群（或私聊）的成员。
 * 错误文案即结构化错误契约（plugins 侧透传 msg 给 agent），故对文案做精确断言。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupMembershipManagerAiclawMembersTest {

	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private OnlineService onlineService;
	@Mock private GroupLifecycleManager groupLifecycleManager;
	@Mock private PresenceSyncHelper presenceSyncHelper;

	@InjectMocks
	private GroupMembershipManager membershipManager;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long AICLAW_UID = 200L;
	private static final Long ONLINE_UID = 301L;
	private static final Long OFFLINE_UID = 302L;

	// ---- helpers ----

	private Room groupRoom() {
		Room room = new Room();
		room.setId(ROOM_ID);
		room.setType(RoomTypeEnum.GROUP.getType());
		return room;
	}

	private Room friendRoom() {
		Room room = new Room();
		room.setId(ROOM_ID);
		room.setType(RoomTypeEnum.FRIEND.getType());
		return room;
	}

	private GroupMember member() {
		GroupMember m = new GroupMember();
		m.setGroupId(GROUP_ID);
		m.setUid(AICLAW_UID);
		return m;
	}

	private ChatMemberResp memberResp(Long uid, Integer roleId) {
		// 模拟 getMemberListByGroupId 的 DB 投影：uid + roleId 已带，name/account/activeStatus 待填充
		return ChatMemberResp.builder()
				.uid(String.valueOf(uid))
				.roleId(roleId)
				.build();
	}

	private SummeryInfoDTO summary(Long uid, String name, String account) {
		SummeryInfoDTO dto = new SummeryInfoDTO();
		dto.setUid(uid);
		dto.setName(name);
		dto.setAccount(account);
		dto.setLastOptTime(LocalDateTime.of(2026, 9, 28, 12, 0));
		return dto;
	}

	/**
	 * 装配 listMember 内部链路：房间→群→成员投影→用户信息→在线集合。
	 * onlineUids 决定哪些 uid 被判为在线（activeStatus=1）。
	 */
	private void stubMemberFetch(List<ChatMemberResp> projection,
								 Map<Long, SummeryInfoDTO> summaries,
								 Set<Long> onlineUids) {
		when(roomCache.get(ROOM_ID)).thenReturn(groupRoom());

		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroup);

		when(groupMemberDao.getMemberListByGroupId(GROUP_ID)).thenReturn(projection);
		when(userSummaryCache.getBatch(anyList())).thenReturn(summaries);
		when(onlineService.getOnlineUsersList(anyList())).thenReturn(onlineUids);
	}

	// ==================== 鉴权闸门 ====================

	@Test
	@DisplayName("私聊房间 → 抛 BizException「当前不在群聊中」")
	void friendRoom_throwsNotInGroup() {
		when(roomCache.get(ROOM_ID)).thenReturn(friendRoom());

		BizException ex = assertThrows(BizException.class,
				() -> membershipManager.aiclawListMembers(ROOM_ID, false, AICLAW_UID));
		assertEquals("当前不在群聊中", ex.getMessage());
	}

	@Test
	@DisplayName("房间不存在（null）→ 抛 BizException「当前不在群聊中」")
	void nullRoom_throwsNotInGroup() {
		when(roomCache.get(ROOM_ID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class,
				() -> membershipManager.aiclawListMembers(ROOM_ID, false, AICLAW_UID));
		assertEquals("当前不在群聊中", ex.getMessage());
	}

	@Test
	@DisplayName("群聊但 aiclaw 非成员（getMember 返 null）→ 抛 BizException「未加入该群聊，无法查询成员」")
	void groupButNotMember_throwsNotJoined() {
		when(roomCache.get(ROOM_ID)).thenReturn(groupRoom());
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class,
				() -> membershipManager.aiclawListMembers(ROOM_ID, false, AICLAW_UID));
		assertEquals("未加入该群聊，无法查询成员", ex.getMessage());
	}

	// ==================== 成员列表 ====================

	@Test
	@DisplayName("#332: 删除用户仍有活跃成员行时，缺失摘要不返回 Rust 无法解码的 null 字段")
	void deletedUserWithActiveMembership_doesNotBreakMemberList() {
		Long deletedUid = 303L;
		stubMemberFetch(
				List.of(memberResp(ONLINE_UID, 3), memberResp(deletedUid, 3)),
				Map.of(ONLINE_UID, summary(ONLINE_UID, "有效用户", "valid")),
				Set.of(ONLINE_UID, deletedUid));

		List<ChatMemberResp> list = membershipManager.listMember(MemberReq.builder().roomId(ROOM_ID).build());
		assertEquals(1, list.size());
		assertEquals(String.valueOf(ONLINE_UID), list.get(0).getUid());
		assertEquals("有效用户", list.get(0).getName());
		assertEquals(LocalDateTime.of(2026, 9, 28, 12, 0), list.get(0).getLastOptTime());
	}

	@Test
	@DisplayName("#332: DB 已过滤删除用户时，残留的摘要缓存也不带入成员结果")
	void staleSummaryCannotReviveDeletedMembership() {
		Long deletedUid = 303L;
		stubMemberFetch(List.of(memberResp(ONLINE_UID, 3)),
				Map.of(ONLINE_UID, summary(ONLINE_UID, "有效用户", "valid"),
						deletedUid, summary(deletedUid, "旧缓存", "stale")),
				Set.of(ONLINE_UID));

		assertEquals(List.of(String.valueOf(ONLINE_UID)), membershipManager.listMember(MemberReq.builder().roomId(ROOM_ID).build())
				.stream().map(ChatMemberResp::getUid).toList());
		verify(userSummaryCache).getBatch(List.of(ONLINE_UID));
	}

	@Test
	@DisplayName("#332: aiclaw 成员复用同一缺失摘要过滤")
	void aiclawListMembers_omitsDeletedUser() {
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(member());
		stubMemberFetch(
				List.of(memberResp(ONLINE_UID, 3), memberResp(303L, 3)),
				Map.of(ONLINE_UID, summary(ONLINE_UID, "有效用户", "valid")),
				Set.of(ONLINE_UID));
		assertEquals(List.of(String.valueOf(ONLINE_UID)), membershipManager.aiclawListMembers(ROOM_ID, false, AICLAW_UID)
				.stream().map(AiclawMemberResp::getUid).toList());
	}

	@Test
	@DisplayName("群聊 + aiclaw 是成员 → 返回成员列表，正确映射 uid/name/account/online/roleId")
	void memberPresent_returnsMappedList() {
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(member());
		stubMemberFetch(
				List.of(memberResp(ONLINE_UID, 3), memberResp(OFFLINE_UID, 1)),
				Map.of(
						ONLINE_UID, summary(ONLINE_UID, "在线用户", "acc-on"),
						OFFLINE_UID, summary(OFFLINE_UID, "离线用户", "acc-off")),
				Set.of(ONLINE_UID));

		List<AiclawMemberResp> list = membershipManager.aiclawListMembers(ROOM_ID, false, AICLAW_UID);

		assertEquals(2, list.size());

		AiclawMemberResp on = list.stream()
				.filter(m -> String.valueOf(ONLINE_UID).equals(m.getUid())).findFirst().orElseThrow();
		assertEquals("在线用户", on.getName());
		assertEquals("acc-on", on.getAccount());
		assertEquals(3, on.getRoleId());
		assertTrue(on.getOnline(), "ONLINE_UID 应为在线");

		AiclawMemberResp off = list.stream()
				.filter(m -> String.valueOf(OFFLINE_UID).equals(m.getUid())).findFirst().orElseThrow();
		assertEquals("离线用户", off.getName());
		assertFalse(off.getOnline(), "OFFLINE_UID 应为离线");
	}

	@Test
	@DisplayName("online=true → 仅返回在线成员，过滤离线")
	void onlineTrue_filtersOnlyOnline() {
		when(groupMemberDao.getMember(ROOM_ID, AICLAW_UID)).thenReturn(member());
		stubMemberFetch(
				List.of(memberResp(ONLINE_UID, 3), memberResp(OFFLINE_UID, 3)),
				Map.of(
						ONLINE_UID, summary(ONLINE_UID, "在线用户", "acc-on"),
						OFFLINE_UID, summary(OFFLINE_UID, "离线用户", "acc-off")),
				Set.of(ONLINE_UID));

		List<AiclawMemberResp> list = membershipManager.aiclawListMembers(ROOM_ID, true, AICLAW_UID);

		assertEquals(1, list.size(), "online=true 时仅保留在线成员");
		assertEquals(String.valueOf(ONLINE_UID), list.get(0).getUid());
		assertTrue(list.get(0).getOnline());
	}
}
