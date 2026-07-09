package com.luohuo.flex.im.core.chat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.utils.SpringUtils;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.dao.UserApplyDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.service.NoticeService;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.NoticeTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.domain.vo.req.room.GroupMemberPageReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberAddReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupMemberSimpleResp;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-009 #86: 群成员分页响应携带 userType（既有用例，保持绿）。
 * REQ-009 #88: addMember 对被邀请 aiclaw 的自动入群 + AICLAW_GROUP_APPROVE 待批通知。
 *
 * 「沉默直到批准」（#84 gate：未建 approved 配置行 → isApproved=false → aiclaw 不发言）
 * 由 AiclawGroupConfigServiceImplTest 覆盖，这里不重复；本类只验证「自动入群（pending）+ 通知主人」。
 */
@ExtendWith(MockitoExtension.class)
class RoomAppServiceImplTest {

	@Mock private RoomGroupDao roomGroupDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserDao userDao;
	@Mock private OnlineService onlineService;

	// addMember 触达的额外依赖
	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private UserApplyDao userApplyDao;
	@Mock private NoticeDao noticeDao;
	@Mock private AiclawOwnerCache aiclawOwnerCache;
	@Mock private NoticeService noticeService;
	@Mock private ChatService chatService;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private PushService pushService;
	@Mock private TransactionTemplate transactionTemplate;
	@Mock private AiclawGroupConfigService aiclawGroupConfigService;

	@InjectMocks
	private RoomAppServiceImpl roomAppService;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long AICLAW_UID = 200L;
	private static final Integer USER_TYPE_AICLAW = 4;

	private static final Long INVITER = 100L;
	private static final Long OWNER = 999L;
	private static final String GROUP_NAME = "测试群";

	@BeforeEach
	void setUpSpringContext() {
		// aiclaw 入群会经 SpringUtils.publishEvent(GroupMemberAddEvent)（静态调用），
		// 单测无 Spring 容器，注入一个 mock ApplicationContext 让事件发布成为 no-op。
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

	/**
	 * 共用的 addMember mock 装配：房间/群存在，邀请者是群成员，被邀请人尚未入群/未邀请过，
	 * transactionTemplate 真正执行回调，缓存/事件侧写宽松放过。
	 */
	private void stubAddMemberCommon() {
		Room room = new Room();
		room.setId(ROOM_ID);
		when(roomCache.get(ROOM_ID)).thenReturn(room);

		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		roomGroup.setName(GROUP_NAME);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroup);

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

		// 缓存/事件侧写宽松放过
		lenient().when(cachePlusOps.sCard(any())).thenReturn(1L);
	}

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

		PageBaseResp<GroupMemberSimpleResp> resp = roomAppService.getGroupMemberPage(req);

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
	@DisplayName("别人拉你的 aiclaw（inviter != owner）：aiclaw 自动入群 + 发 AICLAW_GROUP_APPROVE 给主人")
	void addMember_invitesOthersAiclaw_autoJoinsAndNotifiesOwner() {
		stubAddMemberCommon();
		// 被邀请的 200 是 aiclaw（userType=4），主人是 999，邀请者是 100
		when(userDao.listByIds(any())).thenReturn(List.of(user(AICLAW_UID, USER_TYPE_AICLAW)));
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);
		// #171: REQ-009 去重门控（tryMarkApproveNotified 用 Redis SETNX 占位）在本用例需返回 true 才发通知
		when(aiclawGroupConfigService.tryMarkApproveNotified(any(), any())).thenReturn(true);

		roomAppService.addMember(INVITER, addReq(AICLAW_UID));

		// aiclaw 已入群
		verify(groupMemberDao, atLeastOnce()).save(any(GroupMember.class));
		// 发了一条待批准通知给主人
		verify(noticeService).createNotice(
				eq(RoomTypeEnum.GROUP),
				eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				eq(AICLAW_UID),   // senderId
				eq(OWNER),        // receiverId
				eq(0L),           // applyId
				eq(AICLAW_UID),   // operate
				eq(ROOM_ID),      // roomId
				eq(GROUP_NAME));  // content
	}

	@Test
	@DisplayName("主人自己拉自己的 aiclaw（inviter == owner）：入群但不发 AICLAW_GROUP_APPROVE")
	void addMember_ownerInvitesOwnAiclaw_autoJoinsNoNotice() {
		stubAddMemberCommon();
		when(userDao.listByIds(any())).thenReturn(List.of(user(AICLAW_UID, USER_TYPE_AICLAW)));
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(OWNER);

		// 邀请者就是主人 999
		roomAppService.addMember(OWNER, addReq(AICLAW_UID));

		verify(groupMemberDao, atLeastOnce()).save(any(GroupMember.class));
		verify(noticeService, never()).createNotice(
				any(RoomTypeEnum.class),
				eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("非 aiclaw 被邀（userType=3）：不发 AICLAW_GROUP_APPROVE（走普通邀请流程）")
	void addMember_invitesNonAiclaw_noAutoJoinNoNotice() {
		stubAddMemberCommon();
		// 200 是普通用户（userType=3）→ 不应被当作 aiclaw 自动入群
		when(userDao.listByIds(any())).thenReturn(List.of(user(AICLAW_UID, 3)));

		// 普通邀请流程触达的依赖
		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setName("inviter");
		lenient().when(userSummaryCache.get(anyLong())).thenReturn(summary);
		lenient().when(noticeDao.getUnReadCount(anyLong(), anyLong())).thenReturn(new WSNotice());
		lenient().when(groupMemberDao.getGroupUsers(eq(GROUP_ID), eq(true))).thenReturn(List.of());

		roomAppService.addMember(INVITER, addReq(AICLAW_UID));

		// 没有发待批准通知（200 不是 aiclaw）
		verify(noticeService, never()).createNotice(
				any(RoomTypeEnum.class),
				eq(NoticeTypeEnum.AICLAW_GROUP_APPROVE),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
	}
}
