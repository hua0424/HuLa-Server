package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.domain.vo.req.room.DisbandGroupReq;
import com.luohuo.flex.im.domain.vo.request.GroupAddReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberAddReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberDelReq;
import com.luohuo.flex.im.domain.vo.request.member.MemberExitReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.Mockito.verify;

/**
 * #170: RoomAppServiceImpl 已拆为 shell，群成员/群生命周期方法委派给两个内部协作者。
 * 原本验证 addMember/getGroupMemberPage/aiclawListMembers 具体逻辑的用例已迁至
 * {@link GroupMembershipManagerTest} / {@link GroupMembershipManagerAiclawMembersTest}
 * （断言原样保留）。本类只保留 shell → manager 的<b>薄委派</b>验证：
 * 委派方法不带注解、跨 bean 调用对应 manager，代理仍在 manager 边界生效（不多不少）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomAppServiceImplTest {

	@Mock private GroupMembershipManager membershipManager;
	@Mock private GroupLifecycleManager lifecycleManager;

	@InjectMocks
	private RoomAppServiceImpl shell;

	private static final Long UID = 100L;
	private static final Long ROOM_ID = 10L;

	@Test
	@DisplayName("addMember 委派给 GroupMembershipManager")
	void addMember_delegatesToMembershipManager() {
		MemberAddReq req = new MemberAddReq();
		req.setRoomId(ROOM_ID);
		shell.addMember(UID, req);
		verify(membershipManager).addMember(UID, req);
	}

	@Test
	@DisplayName("delMember 委派给 GroupMembershipManager")
	void delMember_delegatesToMembershipManager() {
		MemberDelReq req = new MemberDelReq();
		req.setRoomId(ROOM_ID);
		shell.delMember(UID, req);
		verify(membershipManager).delMember(UID, req);
	}

	@Test
	@DisplayName("addGroup 委派给 GroupLifecycleManager")
	void addGroup_delegatesToLifecycleManager() {
		GroupAddReq req = new GroupAddReq();
		shell.addGroup(UID, req);
		verify(lifecycleManager).addGroup(UID, req);
	}

	@Test
	@DisplayName("exitGroup 委派给 GroupLifecycleManager（公开入口，代理在 manager 边界生效）")
	void exitGroup_delegatesToLifecycleManager() {
		MemberExitReq req = new MemberExitReq();
		req.setRoomId(ROOM_ID);
		shell.exitGroup(true, UID, req);
		verify(lifecycleManager).exitGroup(true, UID, req);
	}

	@Test
	@DisplayName("disbandGroup 委派给 GroupLifecycleManager")
	void disbandGroup_delegatesToLifecycleManager() {
		DisbandGroupReq req = new DisbandGroupReq();
		req.setRoomId(ROOM_ID);
		shell.disbandGroup(req);
		verify(lifecycleManager).disbandGroup(req);
	}
}
