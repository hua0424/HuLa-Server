package com.luohuo.flex.im.core.chat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.dao.UserApplyDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.service.NoticeService;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.GroupRoleEnum;
import com.luohuo.flex.im.domain.vo.req.room.GroupMemberPageReq;
import com.luohuo.flex.im.domain.vo.request.admin.AdminSetReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberAddReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberDelReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupMemberSimpleResp;
import com.luohuo.flex.model.entity.ws.WSNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #170: 群成员管理器单测。承接原 RoomAppServiceImplTest 中随 addMember / getGroupMemberPage
 * 迁入 {@link GroupMembershipManager} 的逻辑（断言意图一字不改），并新增 delMember / addAdmin /
 * revokeAdmin 的关键状态转移。
 *
 * <p>REQ-009 #86: 群成员分页响应携带 userType。REQ-009 #88: addMember 对被邀请 aiclaw 的自动入群委派接缝。</p>
 *
 * <p><b>#170 AOP core 断言</b>：delMember 小群（≤3）自动解散路径必须调用
 * {@link GroupLifecycleManager#exitGroupInternal}（无注解 core），<b>不得</b>调用公开的
 * {@code exitGroup}（@RedissonLock）——保持拆分前 delMember 自调用绕过 exitGroup 锁的语义。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupMembershipManagerTest {

	@Mock private RoomGroupDao roomGroupDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserDao userDao;
	@Mock private OnlineService onlineService;

	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private UserApplyDao userApplyDao;
	@Mock private NoticeDao noticeDao;
	@Mock private NoticeService noticeService;
	@Mock private ContactDao contactDao;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private PushService pushService;
	@Mock private TransactionTemplate transactionTemplate;
	@Mock private AiclawParticipant aiclawParticipant;
	@Mock private com.baidu.fsg.uid.UidGenerator uidGenerator;
	@Mock private GroupLifecycleManager groupLifecycleManager;
	@Mock private PresenceSyncHelper presenceSyncHelper;

	@InjectMocks
	private GroupMembershipManager membershipManager;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long AICLAW_UID = 200L;
	private static final Integer USER_TYPE_AICLAW = 4;

	private static final Long INVITER = 100L;
	private static final Long OWNER = 999L;
	private static final String GROUP_NAME = "测试群";

	@BeforeEach
	void setUpSpringContext() {
		// aiclaw 入群会经 SpringUtils.publishEvent（静态调用），单测无 Spring 容器，注入 mock 让其 no-op。
		SpringUtils.setApplicationContext(
				org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class));
	}

	// ---- helpers ----

	private MemberAddReq addReq(Long... uids) {
		MemberAddReq req = new MemberAddReq();
		req.setRoomId(ROOM_ID);
		req.setUidList(new HashSet<>(List.of(uids)));
		return req;
	}

	private User user(Long uid, Integer userType) {
		User u = new User();
		u.setId(uid);
		u.setUserType(userType);
		u.setName("u" + uid);
		return u;
	}

	private RoomGroup roomGroup() {
		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		roomGroup.setName(GROUP_NAME);
		roomGroup.setAccount("acc");
		return roomGroup;
	}

	/**
	 * 共用的 addMember mock 装配：房间/群存在，邀请者是群成员，被邀请人尚未入群/未邀请过，
	 * transactionTemplate 真正执行回调，缓存/事件侧写宽松放过。
	 */
	private void stubAddMemberCommon() {
		Room room = new Room();
		room.setId(ROOM_ID);
		when(roomCache.get(ROOM_ID)).thenReturn(room);

		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroup());

		// 邀请者自己是群成员；被邀请人查不到 → 尚未入群
		when(groupMemberDao.getMemberByGroupId(eq(GROUP_ID), anyLong())).thenAnswer(inv -> {
			Long uid = inv.getArgument(1);
			if (uid.equals(INVITER) || uid.equals(OWNER)) {
				GroupMember self = new GroupMember();
				self.setGroupId(GROUP_ID);
				self.setUid(uid);
				return self;
			}
			return null;
		});

		when(groupMemberDao.getMemberBatch(eq(GROUP_ID), any())).thenReturn(List.of());
		when(userApplyDao.getExistingUsers(eq(ROOM_ID), any())).thenReturn(List.of());

		// transactionTemplate.execute 真正运行回调（aiclaw 入群 / 保存邀请记录都经它）
		lenient().when(transactionTemplate.execute(any())).thenAnswer(
				inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

		lenient().when(cachePlusOps.sCard(any())).thenReturn(1L);
	}

	// ==================== 迁移自 RoomAppServiceImplTest ====================

	@Test
	@DisplayName("getGroupMemberPage 把 aiclaw 成员的 userType=4 透传到响应，且 name 已填充")
	void getGroupMemberPage_carriesUserType() {
		GroupMemberPageReq req = new GroupMemberPageReq();
		req.setRoomId(ROOM_ID);
		req.setPageNo(1);
		req.setPageSize(10);

		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		when(roomGroupDao.getOne(any())).thenReturn(roomGroup);

		GroupMember member = new GroupMember();
		member.setGroupId(GROUP_ID);
		member.setUid(AICLAW_UID);
		member.setRoleId(3);
		Page<GroupMember> page = new Page<>(1, 10);
		page.setRecords(List.of(member));
		page.setTotal(1);
		when(groupMemberDao.page(any(), any())).thenReturn((IPage) page);

		User user = new User();
		user.setId(AICLAW_UID);
		user.setName("安洁");
		user.setUserType(USER_TYPE_AICLAW);
		when(userDao.listByIds(any())).thenReturn(List.of(user));

		when(onlineService.getUsersOnlineStatus(any())).thenReturn(Map.of(AICLAW_UID, true));

		PageBaseResp<GroupMemberSimpleResp> resp = membershipManager.getGroupMemberPage(req);

		assertNotNull(resp);
		List<GroupMemberSimpleResp> list = resp.getList();
		assertNotNull(list);
		assertEquals(1, list.size());

		GroupMemberSimpleResp simple = list.get(0);
		assertEquals(USER_TYPE_AICLAW, simple.getUserType(), "userType 应透传 aiclaw 类型 4");
		assertEquals("安洁", simple.getName(), "name 应填充");
		assertEquals(String.valueOf(AICLAW_UID), simple.getUid());
	}

	@Test
	@DisplayName("被邀请含 aiclaw：委派 AiclawParticipant.autoJoinInvitedAiclaws 并把返回的 aiclaw 从普通邀请流程剔除")
	void addMember_delegatesAiclawAutoJoinToSeam_andRemovesFromNormalInvite() {
		stubAddMemberCommon();
		when(userDao.listByIds(any())).thenReturn(List.of(user(AICLAW_UID, USER_TYPE_AICLAW)));
		when(aiclawParticipant.autoJoinInvitedAiclaws(any(RoomGroup.class), anyList(), eq(INVITER)))
				.thenReturn(new HashSet<>(List.of(AICLAW_UID)));

		membershipManager.addMember(INVITER, addReq(AICLAW_UID));

		verify(aiclawParticipant).autoJoinInvitedAiclaws(any(RoomGroup.class), anyList(), eq(INVITER));
		verify(userApplyDao, never()).saveBatch(any());
	}

	@Test
	@DisplayName("混合邀请（aiclaw + 人类）：aiclaw 交接缝自动入群，人类仍走普通邀请流程（仅人类落 UserApply）")
	@SuppressWarnings("unchecked")
	void addMember_mixedInvite_aiclawToSeam_humanToNormalInvite() {
		stubAddMemberCommon();
		Long humanInvitee = 300L;
		when(userDao.listByIds(any()))
				.thenReturn(List.of(user(AICLAW_UID, USER_TYPE_AICLAW), user(humanInvitee, 3)));
		when(aiclawParticipant.autoJoinInvitedAiclaws(any(RoomGroup.class), anyList(), eq(INVITER)))
				.thenReturn(new HashSet<>(List.of(AICLAW_UID)));

		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setName("inviter");
		lenient().when(userSummaryCache.get(anyLong())).thenReturn(summary);
		lenient().when(noticeDao.getUnReadCount(anyLong(), anyLong())).thenReturn(new WSNotice());
		lenient().when(groupMemberDao.getGroupUsers(eq(GROUP_ID), eq(true))).thenReturn(List.of());

		membershipManager.addMember(INVITER, addReq(AICLAW_UID, humanInvitee));

		org.mockito.ArgumentCaptor<List<com.luohuo.flex.im.domain.entity.UserApply>> captor =
				org.mockito.ArgumentCaptor.forClass(List.class);
		verify(userApplyDao).saveBatch(captor.capture());
		List<Long> invitedTargets = captor.getValue().stream()
				.map(com.luohuo.flex.im.domain.entity.UserApply::getTargetId).toList();
		assertEquals(List.of(humanInvitee), invitedTargets, "仅人类进入普通邀请流程，aiclaw 已交接缝");
	}

	@Test
	@DisplayName("被邀全是非 aiclaw：接缝返回空集，正常跑普通邀请流程（UserApply 落库）")
	void addMember_noAiclaw_seamReturnsEmpty_runsNormalInvite() {
		stubAddMemberCommon();
		when(userDao.listByIds(any())).thenReturn(List.of(user(AICLAW_UID, 3)));
		when(aiclawParticipant.autoJoinInvitedAiclaws(any(RoomGroup.class), anyList(), eq(INVITER)))
				.thenReturn(new HashSet<>());

		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setName("inviter");
		lenient().when(userSummaryCache.get(anyLong())).thenReturn(summary);
		lenient().when(noticeDao.getUnReadCount(anyLong(), anyLong())).thenReturn(new WSNotice());
		lenient().when(groupMemberDao.getGroupUsers(eq(GROUP_ID), eq(true))).thenReturn(List.of());

		membershipManager.addMember(INVITER, addReq(AICLAW_UID));

		verify(aiclawParticipant).autoJoinInvitedAiclaws(any(RoomGroup.class), anyList(), eq(INVITER));
		verify(userApplyDao).saveBatch(any());
	}

	// ==================== 新增：delMember 状态转移（含 #170 AOP core） ====================

	@Test
	@DisplayName("delMember 小群(≤3)自动解散：委派 exitGroupInternal（无注解 core），不走 @RedissonLock 的公开 exitGroup")
	void delMember_smallGroup_delegatesToExitGroupCore_notPublicWrapper() {
		Room room = new Room();
		room.setId(ROOM_ID);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroup());

		GroupMember self = new GroupMember();
		self.setGroupId(GROUP_ID);
		self.setUid(OWNER);
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER)).thenReturn(self);

		// 群人数 ≤3 → 触发自动解散分支
		when(cachePlusOps.sCard(any())).thenReturn(2L);

		MemberDelReq req = new MemberDelReq();
		req.setRoomId(ROOM_ID);
		req.setUidList(List.of(300L));

		membershipManager.delMember(OWNER, req);

		// #170 关键断言：走无注解 core（跨 bean，无代理），保持「不新增锁」
		verify(groupLifecycleManager).exitGroupInternal(eq(true), eq(OWNER), any(MemberExitReq.class));
		verify(groupLifecycleManager, never()).exitGroup(any(), any(), any());
		// 未进入逐个踢人分支
		verify(groupMemberDao, never()).removeById(any());
	}

	@Test
	@DisplayName("delMember 正常踢人(>3人)：删成员+删会话，并清 aiclaw 待批准标记")
	void delMember_kicksNormalMember() {
		Long removed = 300L;
		Room room = new Room();
		room.setId(ROOM_ID);
		when(roomCache.get(ROOM_ID)).thenReturn(room);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroup());

		GroupMember self = new GroupMember();
		self.setGroupId(GROUP_ID);
		self.setUid(OWNER);
		self.setRoleId(GroupRoleEnum.LEADER.getType());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER)).thenReturn(self);

		GroupMember removedMember = new GroupMember();
		removedMember.setId(555L);
		removedMember.setGroupId(GROUP_ID);
		removedMember.setUid(removed);
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, removed)).thenReturn(removedMember);

		when(cachePlusOps.sCard(any())).thenReturn(5L); // >3
		when(groupMemberDao.isLord(GROUP_ID, removed)).thenReturn(false);
		when(groupMemberDao.isManager(GROUP_ID, removed)).thenReturn(false);
		when(transactionTemplate.execute(any())).thenAnswer(
				inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID)).thenReturn(new java.util.ArrayList<>(List.of(OWNER)));
		when(uidGenerator.getUid()).thenReturn(1L);
		when(groupMemberDao.getGroupUsers(GROUP_ID, true)).thenReturn(List.of());

		MemberDelReq req = new MemberDelReq();
		req.setRoomId(ROOM_ID);
		req.setUidList(List.of(removed));

		membershipManager.delMember(OWNER, req);

		verify(groupMemberDao).removeById(555L);
		verify(contactDao).removeByRoomId(ROOM_ID, List.of(removed));
		verify(aiclawParticipant).onMembersRemoved(ROOM_ID, List.of(removed));
		// 未触发小群自动解散
		verify(groupLifecycleManager, never()).exitGroupInternal(any(), any(), any());
	}

	// ==================== 新增：管理员增/撤状态转移 ====================

	@Test
	@DisplayName("addAdmin：群主给成员加管理员 → 落库 + 推送 + 通知被操作人")
	void addAdmin_setsAndNotifies() {
		Long target = 300L;
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.isLord(GROUP_ID, OWNER)).thenReturn(true);
		when(groupMemberDao.isGroupShip(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.getManageUidList(GROUP_ID)).thenReturn(new java.util.ArrayList<>());
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(OWNER, target));
		when(uidGenerator.getUid()).thenReturn(1L);

		AdminSetReq req = new AdminSetReq();
		req.setRoomId(ROOM_ID);
		req.setUidList(List.of(target));

		membershipManager.addAdmin(OWNER, req);

		verify(groupMemberDao).addAdmin(eq(GROUP_ID), eq(List.of(target)));
		verify(pushService).sendPushMsg(any(), anyList(), eq(OWNER));
		// target 不在原管理员集合 → 应给它发通知
		verify(noticeService).createNotice(any(), any(), eq(OWNER), eq(target), anyLong(), eq(target), eq(ROOM_ID), eq(""));
	}

	@Test
	@DisplayName("revokeAdmin：群主撤销成员管理员 → 落库 + 推送")
	void revokeAdmin_revokesAndNotifies() {
		Long target = 300L;
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.isLord(GROUP_ID, OWNER)).thenReturn(true);
		when(groupMemberDao.isGroupShip(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(OWNER, target));
		when(uidGenerator.getUid()).thenReturn(1L);

		AdminSetReq req = new AdminSetReq();
		req.setRoomId(ROOM_ID);
		req.setUidList(List.of(target));

		membershipManager.revokeAdmin(OWNER, req);

		verify(groupMemberDao).revokeAdmin(eq(GROUP_ID), eq(List.of(target)));
		verify(pushService).sendPushMsg(any(), anyList(), eq(OWNER));
	}
}
