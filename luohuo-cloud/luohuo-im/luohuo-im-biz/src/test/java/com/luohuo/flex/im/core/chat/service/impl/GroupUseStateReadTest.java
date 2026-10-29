package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomDao;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.enums.GroupRoleEnum;
import com.luohuo.flex.im.domain.vo.req.room.GroupUseStateReq;
import com.luohuo.flex.im.domain.vo.resp.room.GroupUseStateResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#484 (T3)：群成员/权限「当前事实」窄读的边界测试。
 *
 * <p>禁止事项的守卫：当前读必须绕开 RoomCache / RoomGroupCache(roomId 维度) / GroupMemberCache，
 * 所以本测试把这三个缓存都 mock 成「返回陈旧值」，并断言读结果仍来自事实表 DAO。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupUseStateReadTest {

	@Mock private RoomDao roomDao;
	@Mock private RoomGroupDao roomGroupDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private TransactionTemplate transactionTemplate;

	// 缓存一律 mock 成「陈旧」：若被读用，断言会失败
	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache groupMemberCache;

	@InjectMocks private GroupMembershipManager membershipManager;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long SELF_UID = 100L;
	private static final Long OTHER_UID = 200L;

	private void stubTransaction() {
		lenient().when(transactionTemplate.execute(any())).thenAnswer(
				inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
	}

	private Room groupRoom() {
		Room room = new Room();
		room.setId(ROOM_ID);
		room.setType(com.luohuo.flex.im.domain.enums.RoomTypeEnum.GROUP.getType());
		return room;
	}

	private RoomGroup roomGroup() {
		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		roomGroup.setName("测试群");
		roomGroup.setAccount("acc");
		return roomGroup;
	}

	private GroupMember member(Long uid, Integer roleId) {
		GroupMember m = new GroupMember();
		m.setGroupId(GROUP_ID);
		m.setUid(uid);
		m.setRoleId(roleId);
		return m;
	}

	private GroupUseStateReq req(Long... uids) {
		GroupUseStateReq request = new GroupUseStateReq();
		request.setRoomId(ROOM_ID);
		request.setMemberUids(new ArrayList<>(List.of(uids)));
		return request;
	}

	@Test
	@DisplayName("#484 当前读：绕过三处缓存，仍取到事实表的新成员与新人数量")
	void readsCurrentFactsIgnoringStaleCaches() {
		stubTransaction();
		when(roomDao.getById(ROOM_ID)).thenReturn(groupRoom());
		when(roomGroupDao.getByRoomId(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, SELF_UID))
				.thenReturn(member(SELF_UID, GroupRoleEnum.LEADER.getType()));
		when(groupMemberDao.count(any())).thenReturn(7L);
		when(groupMemberDao.getMemberBatch(GROUP_ID, List.of(OTHER_UID)))
				.thenReturn(List.of(member(OTHER_UID, GroupRoleEnum.MANAGER.getType())));
		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setName("新成员");
		summary.setAccount("acc2");
		when(userSummaryCache.getBatch(List.of(OTHER_UID))).thenReturn(Map.of(OTHER_UID, summary));

		// 陈旧缓存：室/群/成员缓存都指向旧值（群不存在、无成员），当前读不得采用
		lenient().when(roomCache.get(ROOM_ID)).thenReturn(null);
		lenient().when(roomGroupCache.get(ROOM_ID)).thenReturn(null);
		lenient().when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of());

		GroupUseStateResp resp = membershipManager.readGroupUseState(req(OTHER_UID), SELF_UID);

		assertEquals(GroupUseStateResp.OUTCOME_MEMBER, resp.getOutcome());
		assertEquals(7L, resp.getMemberCount());
		assertEquals(GroupRoleEnum.LEADER.getType(), resp.getSelfRoleId());
		assertEquals(1, resp.getRequestedMembers().size());
		GroupUseStateResp.MemberProjection projection = resp.getRequestedMembers().get(0);
		assertEquals(OTHER_UID, projection.getUid());
		assertTrue(projection.getExists());
		assertEquals(GroupRoleEnum.MANAGER.getType(), projection.getRoleId());
		assertEquals("新成员", projection.getName());
	}

	@Test
	@DisplayName("#484 当前读：profile 摘要缺失不改写成员资格（exists 只认事实表）")
	void missingProfileKeepsMembership() {
		stubTransaction();
		when(roomDao.getById(ROOM_ID)).thenReturn(groupRoom());
		when(roomGroupDao.getByRoomId(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, SELF_UID))
				.thenReturn(member(SELF_UID, GroupRoleEnum.MEMBER.getType()));
		when(groupMemberDao.count(any())).thenReturn(2L);
		when(groupMemberDao.getMemberBatch(GROUP_ID, List.of(OTHER_UID)))
				.thenReturn(List.of(member(OTHER_UID, GroupRoleEnum.MEMBER.getType())));
		when(userSummaryCache.getBatch(List.of(OTHER_UID))).thenReturn(Map.of());

		GroupUseStateResp resp = membershipManager.readGroupUseState(req(OTHER_UID), SELF_UID);

		GroupUseStateResp.MemberProjection projection = resp.getRequestedMembers().get(0);
		assertTrue(projection.getExists());
		assertNull(projection.getName());
	}

	@Test
	@DisplayName("#484 当前读：群不存在/已解散与本人不在群，对外同形 NOT_ACCESSIBLE")
	void notAccessibleIsUniform() {
		stubTransaction();
		// 群已解散（room 查不到）
		when(roomDao.getById(ROOM_ID)).thenReturn(null);
		GroupUseStateResp dissolved = membershipManager.readGroupUseState(req(OTHER_UID), SELF_UID);
		assertEquals(GroupUseStateResp.OUTCOME_NOT_ACCESSIBLE, dissolved.getOutcome());
		assertNull(dissolved.getSelfRoleId());
		assertNull(dissolved.getMemberCount());

		// 群在、但本人不在群
		when(roomDao.getById(ROOM_ID)).thenReturn(groupRoom());
		when(roomGroupDao.getByRoomId(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, SELF_UID)).thenReturn(null);
		GroupUseStateResp notMember = membershipManager.readGroupUseState(req(OTHER_UID), SELF_UID);
		assertEquals(GroupUseStateResp.OUTCOME_NOT_ACCESSIBLE, notMember.getOutcome());
		// 两种不同原因对外不可区分：都不得携带任何群内事实
		assertNull(notMember.getRequestedMembers());
		assertEquals(dissolved.getOutcome(), notMember.getOutcome());
	}

	@Test
	@DisplayName("#484 当前读：未请求的 UID 不作否定，超上限截断由 coverage 显式标记")
	void coverageMarksTruncationWithoutNegatingOthers() {
		stubTransaction();
		when(roomDao.getById(ROOM_ID)).thenReturn(groupRoom());
		when(roomGroupDao.getByRoomId(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, SELF_UID))
				.thenReturn(member(SELF_UID, GroupRoleEnum.LEADER.getType()));
		when(groupMemberDao.count(any())).thenReturn(150L);
		lenient().when(groupMemberDao.getMemberBatch(anyLong(), any())).thenReturn(List.of());
		lenient().when(userSummaryCache.getBatch(any())).thenReturn(Map.of());

		// 请求 0 个 UID：不产生任何否定结论
		GroupUseStateResp none = membershipManager.readGroupUseState(req(), SELF_UID);
		assertNotNull(none.getCoverage());
		assertEquals(0, none.getCoverage().getRequested());
		assertTrue(none.getRequestedMembers().isEmpty());

		// 请求超过上限：截断到 MAX_MEMBER_UIDS，并显式标记 truncated
		Long[] many = new Long[GroupUseStateReq.MAX_MEMBER_UIDS + 5];
		for (int i = 0; i < many.length; i++) {
			many[i] = 1000L + i;
		}
		GroupUseStateResp truncated = membershipManager.readGroupUseState(req(many), SELF_UID);
		assertEquals(GroupUseStateReq.MAX_MEMBER_UIDS + 5, truncated.getCoverage().getRequested());
		assertEquals(GroupUseStateReq.MAX_MEMBER_UIDS, truncated.getCoverage().getLimit());
		assertTrue(truncated.getCoverage().getTruncated());
		assertEquals(GroupUseStateReq.MAX_MEMBER_UIDS, truncated.getRequestedMembers().size());
	}

	@Test
	@DisplayName("#484 当前读：roomId 缺失不炸、不返回任何群内事实")
	void missingRoomIdIsNotAccessible() {
		GroupUseStateReq request = new GroupUseStateReq();
		GroupUseStateResp resp = membershipManager.readGroupUseState(request, SELF_UID);
		assertEquals(GroupUseStateResp.OUTCOME_NOT_ACCESSIBLE, resp.getOutcome());
		assertNull(resp.getMemberCount());
	}
}
