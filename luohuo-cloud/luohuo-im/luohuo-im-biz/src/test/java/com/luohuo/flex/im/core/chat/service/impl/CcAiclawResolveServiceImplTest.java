package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.CcAiclawResolveResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * REQ-010 S9 (#100): CC aiclaw 解析 + owner 鉴权。
 *
 * <p>覆盖 errorCode 全枚举（NO_CC / AMBIGUOUS / UID_NOT_CC / NOT_OWNER）+ 成功路径
 * （单一 CC 群聊、消歧群聊、单聊 counterpart 推导），以及房间不存在的 BizException。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CcAiclawResolveServiceImplTest {

	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private AiclawDao aiclawDao;

	@InjectMocks
	private CcAiclawResolveServiceImpl service;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long OWNER = 1L;
	private static final Long HUMAN = 2L;
	private static final Long CC_UID = 200L;
	private static final Long CC_UID_2 = 201L;
	private static final Long NON_CC_UID = 202L;

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

	private Aiclaw aiclaw(Long uid, Long ownerUid, String adapterType) {
		return Aiclaw.builder().uid(uid).ownerUid(ownerUid).adapterType(adapterType).build();
	}

	private void mockGroupMembers(List<Long> uids) {
		when(roomCache.get(ROOM_ID)).thenReturn(groupRoom());
		RoomGroup rg = new RoomGroup();
		rg.setId(GROUP_ID);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(rg);
		when(groupMemberDao.getMemberUidList(any(), any())).thenReturn(uids);
	}

	// ---- tests ----

	@Test
	@DisplayName("房间不存在 → BizException")
	void roomNotFound() {
		when(roomCache.get(ROOM_ID)).thenReturn(null);
		assertThrows(BizException.class, () -> service.resolveCcAiclawForRoom(ROOM_ID, OWNER, null));
	}

	@Test
	@DisplayName("群无 CC 助理 → NO_CC")
	void noCc() {
		mockGroupMembers(List.of(OWNER, HUMAN, NON_CC_UID));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(aiclaw(NON_CC_UID, OWNER, "openclaw")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, null);
		assertFalse(resp.getOk());
		assertEquals("NO_CC", resp.getErrorCode());
	}

	@Test
	@DisplayName("群多个 CC 助理且未指定 uid → AMBIGUOUS")
	void ambiguous() {
		mockGroupMembers(List.of(OWNER, CC_UID, CC_UID_2));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(
				aiclaw(CC_UID, OWNER, "cc"), aiclaw(CC_UID_2, OWNER, "cc")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, null);
		assertFalse(resp.getOk());
		assertEquals("AMBIGUOUS", resp.getErrorCode());
	}

	@Test
	@DisplayName("指定 uid 不是房间 CC 助理 → UID_NOT_CC")
	void uidNotCc() {
		mockGroupMembers(List.of(OWNER, CC_UID, CC_UID_2));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(
				aiclaw(CC_UID, OWNER, "cc"), aiclaw(CC_UID_2, OWNER, "cc")));

		// 指定一个不在 CC 集合里的 uid
		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, 999L);
		assertFalse(resp.getOk());
		assertEquals("UID_NOT_CC", resp.getErrorCode());
	}

	@Test
	@DisplayName("请求者非 owner → NOT_OWNER")
	void notOwner() {
		mockGroupMembers(List.of(OWNER, CC_UID));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(aiclaw(CC_UID, OWNER, "cc")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, /*requester*/ HUMAN, null);
		assertFalse(resp.getOk());
		assertEquals("NOT_OWNER", resp.getErrorCode());
	}

	@Test
	@DisplayName("群单一 CC 助理 + owner → 成功，counterpartUid 为 null")
	void groupSuccess() {
		mockGroupMembers(List.of(OWNER, HUMAN, CC_UID));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(aiclaw(CC_UID, OWNER, "cc")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, null);
		assertTrue(resp.getOk());
		assertEquals(CC_UID, resp.getAiclawUid());
		assertEquals(RoomTypeEnum.GROUP.getType(), resp.getRoomType());
		assertNull(resp.getCounterpartUid());
	}

	@Test
	@DisplayName("群多 CC 指定 uid + owner → 成功解析到指定 CC")
	void groupDisambiguatedSuccess() {
		mockGroupMembers(List.of(OWNER, CC_UID, CC_UID_2));
		when(aiclawDao.listByUids(any())).thenReturn(List.of(
				aiclaw(CC_UID, OWNER, "cc"), aiclaw(CC_UID_2, OWNER, "cc")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, CC_UID_2);
		assertTrue(resp.getOk());
		assertEquals(CC_UID_2, resp.getAiclawUid());
	}

	@Test
	@DisplayName("单聊 + owner → 成功，counterpartUid = 对端真人")
	void friendSuccess() {
		when(roomCache.get(ROOM_ID)).thenReturn(friendRoom());
		RoomFriend friend = new RoomFriend();
		friend.setUid1(CC_UID);
		friend.setUid2(HUMAN);
		when(roomFriendDao.getByRoomId(ROOM_ID)).thenReturn(friend);
		when(aiclawDao.listByUids(any())).thenReturn(List.of(aiclaw(CC_UID, OWNER, "cc")));

		CcAiclawResolveResp resp = service.resolveCcAiclawForRoom(ROOM_ID, OWNER, null);
		assertTrue(resp.getOk());
		assertEquals(CC_UID, resp.getAiclawUid());
		assertEquals(RoomTypeEnum.FRIEND.getType(), resp.getRoomType());
		assertEquals(HUMAN, resp.getCounterpartUid());
	}
}
