package com.luohuo.flex.im.core.chat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.vo.req.room.GroupMemberPageReq;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.room.GroupMemberSimpleResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REQ-009 #86: 群成员分页响应携带 userType。
 * 验证 getGroupMemberPage 把 User.userType 透传到 GroupMemberSimpleResp，
 * 前端据此识别 aiclaw（user_type=4）成员、渲染未批准/沉默徽标。
 */
@ExtendWith(MockitoExtension.class)
class RoomAppServiceImplTest {

	@Mock private RoomGroupDao roomGroupDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private UserDao userDao;
	@Mock private OnlineService onlineService;

	@InjectMocks
	private RoomAppServiceImpl roomAppService;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long AICLAW_UID = 200L;
	private static final Integer USER_TYPE_AICLAW = 4;

	@Test
	@DisplayName("getGroupMemberPage 把 aiclaw 成员的 userType=4 透传到响应，且 name 已填充")
	void getGroupMemberPage_carriesUserType() {
		GroupMemberPageReq req = new GroupMemberPageReq();
		req.setRoomId(ROOM_ID);
		req.setPageNo(1);
		req.setPageSize(10);

		// 群组存在
		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		when(roomGroupDao.getOne(any())).thenReturn(roomGroup);

		// 分页查到一名成员（aiclaw）
		GroupMember member = new GroupMember();
		member.setGroupId(GROUP_ID);
		member.setUid(AICLAW_UID);
		member.setRoleId(3);
		Page<GroupMember> page = new Page<>(1, 10);
		page.setRecords(List.of(member));
		page.setTotal(1);
		when(groupMemberDao.page(any(), any())).thenReturn((IPage) page);

		// 该成员对应的 User 携带 userType=4 与昵称
		User user = new User();
		user.setId(AICLAW_UID);
		user.setName("安洁");
		user.setUserType(USER_TYPE_AICLAW);
		when(userDao.listByIds(any())).thenReturn(List.of(user));

		// 在线状态
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
}
