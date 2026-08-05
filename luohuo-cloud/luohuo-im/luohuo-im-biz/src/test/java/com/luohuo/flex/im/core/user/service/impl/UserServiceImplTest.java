package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.im.core.chat.service.RoomAppService;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.BlackDao;
import com.luohuo.flex.im.core.user.dao.ItemConfigDao;
import com.luohuo.flex.im.core.user.dao.UserBackpackDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.service.FeedService;
import com.luohuo.flex.im.core.user.service.cache.DefUserCache;
import com.luohuo.flex.im.core.user.service.cache.ItemCache;
import com.luohuo.flex.im.core.user.service.cache.UserCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.common.utils.sensitiveword.SensitiveWordBs;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.vo.req.user.ModifyAvatarReq;
import com.luohuo.flex.im.domain.vo.req.user.ModifyNameReq;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSUserInfoChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试 —— aichatoverview#192: modifyInfo 资料变更 WS 推送。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

	@Mock private RoomAppService roomAppService;
	@Mock private DefUserCache defUserCache;
	@Mock private UserBackpackDao userBackpackDao;
	@Mock private UserDao userDao;
	@Mock private ItemConfigDao itemConfigDao;
	@Mock private ItemCache itemCache;
	@Mock private BlackDao blackDao;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private UserCache userCache;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private SensitiveWordBs sensitiveWordBs;
	@Mock private FeedService feedService;
	@Mock private AiclawDao aiclawDao;
	@Mock private PushService pushService;

	@InjectMocks
	private UserServiceImpl userService;

	private static final Long UID = 100L;

	@Test
	@DisplayName("#192 modifyInfo: 推送 userInfoChange(profile) 帧，目标 = 反向好友 ∪ {本人}，cuid = 本人")
	@SuppressWarnings("unchecked")
	void modifyInfo_pushesUserInfoChangeToFriendsAndSelf() {
		ModifyNameReq req = new ModifyNameReq();
		req.setName("新名字");
		req.setSex(1);

		User existing = new User();
		existing.setId(UID);
		existing.setAvatar("old-avatar");
		when(userDao.getById(UID)).thenReturn(existing);
		// modifyInfo 尾部 evictFriend 需要 account
		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setAccount("acc-100");
		when(userSummaryCache.get(UID)).thenReturn(summary);

		Set<Object> reverseFriends = new HashSet<>(Arrays.asList("300", "301"));
		when(cachePlusOps.sMembers(any())).thenReturn(reverseFriends);

		userService.modifyInfo(UID, req);

		ArgumentCaptor<WsBaseResp> msgCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		ArgumentCaptor<List<Long>> listCaptor = ArgumentCaptor.forClass(List.class);
		verify(pushService, times(1)).sendPushMsg(msgCaptor.capture(), listCaptor.capture(), eq(UID));

		WsBaseResp<?> frame = msgCaptor.getValue();
		assertEquals("userInfoChange", frame.getType(), "帧类型应为 userInfoChange");
		assertTrue(frame.getData() instanceof WSUserInfoChange, "帧载荷应为 WSUserInfoChange");
		WSUserInfoChange data = (WSUserInfoChange) frame.getData();
		assertEquals(String.valueOf(UID), data.getUid(), "uid 应以 String 承载（防 JS 精度丢失）");
		assertEquals(WSUserInfoChange.PROFILE, data.getChangeType(), "changeType 应为 profile");
		List<Long> targets = listCaptor.getValue();
		assertTrue(targets.contains(UID), "推送目标应含本人 uid");
		assertTrue(targets.contains(300L) && targets.contains(301L), "推送目标应含全部反向好友");
		assertEquals(3, targets.size(), "推送目标 = 反向好友 ∪ {本人}，不多不少");
	}

	// ==================== #192 P2-2: modifyAvatar 补推 profile 帧 ====================

	@Test
	@DisplayName("#192 P2-2 modifyAvatar: 推送 userInfoChange(profile) 帧，目标 = 反向好友 ∪ {本人}，cuid = 本人（与 modifyInfo 一致）")
	@SuppressWarnings("unchecked")
	void modifyAvatar_pushesUserInfoChangeToFriendsAndSelf() {
		User existing = new User();
		existing.setId(UID);
		existing.setAccount("acc-100");
		when(userDao.getById(UID)).thenReturn(existing);

		Set<Object> reverseFriends = new HashSet<>(Arrays.asList("300", "301"));
		when(cachePlusOps.sMembers(any())).thenReturn(reverseFriends);

		userService.modifyAvatar(UID, ModifyAvatarReq.builder().avatar("new-avatar").build());

		ArgumentCaptor<WsBaseResp> msgCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		ArgumentCaptor<List<Long>> listCaptor = ArgumentCaptor.forClass(List.class);
		verify(pushService, times(1)).sendPushMsg(msgCaptor.capture(), listCaptor.capture(), eq(UID));

		WsBaseResp<?> frame = msgCaptor.getValue();
		assertEquals("userInfoChange", frame.getType(), "帧类型应为 userInfoChange");
		assertTrue(frame.getData() instanceof WSUserInfoChange, "帧载荷应为 WSUserInfoChange");
		WSUserInfoChange data = (WSUserInfoChange) frame.getData();
		assertEquals(String.valueOf(UID), data.getUid(), "uid 应以 String 承载（防 JS 精度丢失）");
		assertEquals(WSUserInfoChange.PROFILE, data.getChangeType(), "changeType 应为 profile");
		List<Long> targets = listCaptor.getValue();
		assertTrue(targets.contains(UID), "推送目标应含本人 uid");
		assertTrue(targets.contains(300L) && targets.contains(301L), "推送目标应含全部反向好友");
		assertEquals(3, targets.size(), "推送目标 = 反向好友 ∪ {本人}，不多不少");
	}

	// ==================== #192 P2-3: 推送计算异常降级（Redis 故障只丢推送，不回滚写路径） ====================

	@Test
	@DisplayName("#192 P2-3 modifyInfo: 推送计算异常（sMembers 抛）只丢推送，写路径照常（updateById + 缓存双删）")
	void modifyInfo_pushComputationFails_writePathStillSucceeds() {
		ModifyNameReq req = new ModifyNameReq();
		req.setName("新名字");
		req.setSex(1);

		User existing = new User();
		existing.setId(UID);
		existing.setAvatar("old-avatar");
		when(userDao.getById(UID)).thenReturn(existing);
		SummeryInfoDTO summary = new SummeryInfoDTO();
		summary.setAccount("acc-100");
		when(userSummaryCache.get(UID)).thenReturn(summary);
		when(cachePlusOps.sMembers(any())).thenThrow(new RuntimeException("redis down"));

		assertDoesNotThrow(() -> userService.modifyInfo(UID, req));

		verify(userDao).updateById(any());
		verify(userCache).delete(UID);
		verify(userSummaryCache).delete(UID);
	}

	@Test
	@DisplayName("#192 P2-3 modifyAvatar: 推送计算异常（sMembers 抛）只丢推送，写路径照常")
	void modifyAvatar_pushComputationFails_writePathStillSucceeds() {
		User existing = new User();
		existing.setId(UID);
		existing.setAccount("acc-100");
		when(userDao.getById(UID)).thenReturn(existing);
		when(cachePlusOps.sMembers(any())).thenThrow(new RuntimeException("redis down"));

		assertDoesNotThrow(() ->
				userService.modifyAvatar(UID, ModifyAvatarReq.builder().avatar("new-avatar").build()));

		verify(userDao).updateById(any());
		verify(userCache).delete(UID);
		verify(userSummaryCache).delete(UID);
	}
}
