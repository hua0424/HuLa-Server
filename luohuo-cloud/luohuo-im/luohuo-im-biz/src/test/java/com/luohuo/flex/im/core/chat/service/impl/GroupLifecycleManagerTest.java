package com.luohuo.flex.im.core.chat.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.GroupRoleEnum;
import com.luohuo.flex.im.domain.vo.req.room.DisbandGroupReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #170: 群生命周期管理器单测 —— 覆盖 exitGroup 的两条状态转移（成员退群 vs 群主/解散分支）
 * 以及 disbandGroup 走无注解 core 的解散链路。
 *
 * <p>普通 Mockito 无 Spring 代理，故直接调用 exitGroup 即执行 exitGroupInternal 主体；
 * 断言聚焦「哪些持久化/推送副作用被触发」以区分退群 vs 解散两条分支。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupLifecycleManagerTest {

	@Mock private RoomCache roomCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private UserCache userCache;
	@Mock private MessageDao messageDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private ChatService chatService;
	@Mock private RoomService roomService;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private PushService pushService;
	@Mock private OnlineService onlineService;
	@Mock private TransactionTemplate transactionTemplate;
	@Mock private ContactDao contactDao;
	@Mock private AiclawParticipant aiclawParticipant;

	@InjectMocks
	private GroupLifecycleManager lifecycleManager;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long OWNER = 999L;
	private static final Long MEMBER = 300L;

	private RoomGroup roomGroup() {
		RoomGroup rg = new RoomGroup();
		rg.setId(GROUP_ID);
		rg.setRoomId(ROOM_ID);
		rg.setAccount("acc");
		return rg;
	}

	private Room room() {
		Room room = new Room();
		room.setId(ROOM_ID);
		return room;
	}

	private void runTxInline() {
		when(transactionTemplate.execute(any())).thenAnswer(
				inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
	}

	@Test
	@DisplayName("exitGroup 普通成员退群(非群主, >3人)：删自身会话/成员 + 清 aiclaw 标记，不删房间")
	void exitGroup_memberLeaves_nonLord() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(roomService.getById(ROOM_ID)).thenReturn(room());
		when(groupMemberDao.isGroupShip(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.isLord(GROUP_ID, MEMBER)).thenReturn(false);
		when(groupMemberCache.getMemberExceptUidList(ROOM_ID)).thenReturn(List.of(OWNER));
		when(cachePlusOps.sCard(any())).thenReturn(5L); // >3 → 走退群分支
		runTxInline();
		when(contactDao.removeByRoomId(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.removeByGroupId(eq(GROUP_ID), any())).thenReturn(true);

		MemberExitReq req = new MemberExitReq();
		req.setRoomId(ROOM_ID);
		req.setAccount("acc");

		lifecycleManager.exitGroup(false, MEMBER, req);

		// 退群：只删自身会话/成员
		verify(contactDao).removeByRoomId(ROOM_ID, Collections.singletonList(MEMBER));
		verify(groupMemberDao).removeByGroupId(GROUP_ID, Collections.singletonList(MEMBER));
		verify(aiclawParticipant).onMembersRemoved(ROOM_ID, Collections.singletonList(MEMBER));
		// 非解散：不删房间/消息
		verify(roomService, never()).removeById(any());
		verify(messageDao, never()).removeByRoomId(any(), any());
	}

	@Test
	@DisplayName("exitGroup 群主/解散分支(isGroup=true)：删房间+群+会话+消息 + 推送解散广播")
	void exitGroup_ownerDisbands() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(roomService.getById(ROOM_ID)).thenReturn(room());
		when(groupMemberDao.isGroupShip(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.isLord(GROUP_ID, OWNER)).thenReturn(true);
		when(groupMemberDao.getMemberUidList(eq(GROUP_ID), any())).thenReturn(List.of(OWNER, MEMBER));
		User owner = new User();
		owner.setId(OWNER);
		owner.setName("群主");
		when(userCache.get(OWNER)).thenReturn(owner);
		runTxInline();
		when(roomService.removeById(ROOM_ID)).thenReturn(true);
		when(contactDao.removeByRoomId(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.removeByGroupId(eq(GROUP_ID), any())).thenReturn(true);
		when(messageDao.removeByRoomId(eq(ROOM_ID), any())).thenReturn(true);

		MemberExitReq req = new MemberExitReq();
		req.setRoomId(ROOM_ID);
		req.setAccount("acc");

		lifecycleManager.exitGroup(true, OWNER, req);

		verify(roomService).removeById(ROOM_ID);
		verify(messageDao).removeByRoomId(ROOM_ID, Collections.EMPTY_LIST);
		verify(pushService).sendPushMsg(any(), anyList(), eq(OWNER));
	}

	@Test
	@DisplayName("disbandGroup：解析群主后走无注解 core 解散（删房间/消息），验证解散链路端到端")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void disbandGroup_resolvesLord_runsDisbandCore() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());

		// mock MyBatis-Plus lambdaQuery 链：.eq().eq().one() → 群主
		GroupMember lord = new GroupMember();
		lord.setGroupId(GROUP_ID);
		lord.setUid(OWNER);
		lord.setRoleId(GroupRoleEnum.LEADER.getType());
		LambdaQueryChainWrapper<GroupMember> chain = org.mockito.Mockito.mock(LambdaQueryChainWrapper.class);
		when(groupMemberDao.lambdaQuery()).thenReturn(chain);
		when(chain.eq(any(), any())).thenReturn(chain);
		when(chain.one()).thenReturn(lord);

		// 下游解散核心
		when(roomService.getById(ROOM_ID)).thenReturn(room());
		when(groupMemberDao.isGroupShip(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.isLord(GROUP_ID, OWNER)).thenReturn(true);
		when(groupMemberDao.getMemberUidList(eq(GROUP_ID), any())).thenReturn(List.of(OWNER, MEMBER));
		User owner = new User();
		owner.setId(OWNER);
		owner.setName("群主");
		when(userCache.get(OWNER)).thenReturn(owner);
		runTxInline();
		when(roomService.removeById(ROOM_ID)).thenReturn(true);
		when(contactDao.removeByRoomId(eq(ROOM_ID), any())).thenReturn(true);
		when(groupMemberDao.removeByGroupId(eq(GROUP_ID), any())).thenReturn(true);
		when(messageDao.removeByRoomId(eq(ROOM_ID), any())).thenReturn(true);

		DisbandGroupReq req = new DisbandGroupReq();
		req.setRoomId(ROOM_ID);

		lifecycleManager.disbandGroup(req);

		// 端到端到达解散 core（isGroup=true 分支）
		verify(roomService).removeById(ROOM_ID);
		verify(messageDao).removeByRoomId(ROOM_ID, Collections.EMPTY_LIST);
	}
}
