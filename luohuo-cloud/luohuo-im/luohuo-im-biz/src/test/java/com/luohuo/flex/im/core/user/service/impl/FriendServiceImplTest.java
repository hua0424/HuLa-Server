package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.cache.redis.BaseRedis;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.user.dao.UserApplyDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.entity.UserFriend;
import com.luohuo.flex.im.domain.vo.request.friend.FriendRemarkReq;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSUserInfoChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FriendServiceImpl 单元测试 —— aichatoverview#192: updateRemark 备注变更 WS 推送。
 *
 * <p>remark 读面 = 好友分页直查 DB（friendPage + FriendAdapter），无缓存，无需失效；
 * 推送只是让设置人端立即刷新该好友的显示名。</p>
 */
@ExtendWith(MockitoExtension.class)
class FriendServiceImplTest {

	@Mock private UserFriendDao userFriendDao;
	@Mock private UserApplyDao userApplyDao;
	@Mock private RoomService roomService;
	@Mock private ChatService chatService;
	@Mock private UserDao userDao;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private BaseRedis baseRedis;
	@Mock private PushService pushService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private OnlineService onlineService;

	@InjectMocks
	private FriendServiceImpl friendService;

	private static final Long SETTER_UID = 200L;
	private static final Long TARGET_UID = 100L;

	@Test
	@DisplayName("#192 updateRemark: 推送 userInfoChange(remark) 帧，目标仅设置人本人，帧 uid = targetUid")
	@SuppressWarnings("unchecked")
	void updateRemark_pushesRemarkFrameToSetterOnly() {
		UserFriend userFriend = new UserFriend();
		userFriend.setUid(SETTER_UID);
		userFriend.setFriendUid(TARGET_UID);
		when(userFriendDao.getByFriends(eq(SETTER_UID), anyList()))
				.thenReturn(Collections.singletonList(userFriend));
		when(userFriendDao.updateById(any())).thenReturn(true);

		friendService.updateRemark(SETTER_UID, new FriendRemarkReq(TARGET_UID, "老王"));

		ArgumentCaptor<WsBaseResp> msgCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		ArgumentCaptor<List<Long>> listCaptor = ArgumentCaptor.forClass(List.class);
		verify(pushService, times(1)).sendPushMsg(msgCaptor.capture(), listCaptor.capture(), eq(SETTER_UID));

		WsBaseResp<?> frame = msgCaptor.getValue();
		assertEquals("userInfoChange", frame.getType(), "帧类型应为 userInfoChange");
		assertTrue(frame.getData() instanceof WSUserInfoChange, "帧载荷应为 WSUserInfoChange");
		WSUserInfoChange data = (WSUserInfoChange) frame.getData();
		assertEquals(String.valueOf(TARGET_UID), data.getUid(),
				"帧 uid 应为被改备注的人（前端据此刷新该好友显示名），String 承载");
		assertEquals(WSUserInfoChange.REMARK, data.getChangeType(), "changeType 应为 remark");
		assertEquals(Collections.singletonList(SETTER_UID), listCaptor.getValue(),
				"remark 推送目标应仅设置人本人");
	}

	@Test
	@DisplayName("#192 updateRemark: 落库失败（updateById=false）不推送")
	void updateRemark_updateFailed_noPush() {
		UserFriend userFriend = new UserFriend();
		userFriend.setUid(SETTER_UID);
		userFriend.setFriendUid(TARGET_UID);
		when(userFriendDao.getByFriends(eq(SETTER_UID), anyList()))
				.thenReturn(Collections.singletonList(userFriend));
		when(userFriendDao.updateById(any())).thenReturn(false);

		friendService.updateRemark(SETTER_UID, new FriendRemarkReq(TARGET_UID, "老王"));

		verify(pushService, never()).sendPushMsg(any(), anyList(), any());
	}

	// ==================== #192 P2-3: 推送异常降级（只丢推送，不回滚写路径） ====================

	@Test
	@DisplayName("#192 P2-3 updateRemark: sendPushMsg 抛异常只丢推送，写路径照常（updateById 被调、方法正常返回 true）")
	void updateRemark_pushFails_writePathStillSucceeds() {
		UserFriend userFriend = new UserFriend();
		userFriend.setUid(SETTER_UID);
		userFriend.setFriendUid(TARGET_UID);
		when(userFriendDao.getByFriends(eq(SETTER_UID), anyList()))
				.thenReturn(Collections.singletonList(userFriend));
		when(userFriendDao.updateById(any())).thenReturn(true);
		doThrow(new RuntimeException("push down")).when(pushService)
				.sendPushMsg(any(), anyList(), any());

		// 推送=低延迟优化非正确性依赖：异常绝不回滚/中断写路径（catch 后不得重抛）
		Boolean updated = assertDoesNotThrow(
				() -> friendService.updateRemark(SETTER_UID, new FriendRemarkReq(TARGET_UID, "老王")));

		assertTrue(updated, "落库成功应照常返回 true");
		verify(userFriendDao).updateById(any());
	}
}
