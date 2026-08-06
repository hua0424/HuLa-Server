package com.luohuo.flex.im.core.user.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.Notice;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.vo.req.NoticeReq;
import com.luohuo.flex.im.domain.vo.res.NoticeVO;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #157: 邀请「已停用/注销」用户时不得 500。
 *
 * <p>已停用的 aiclaw 其 {@code im_user.is_del=1}，{@code UserSummaryCache} 因此查不到该用户。
 * 旧 {@code convertToVO} 无脑解引用 sender/receiver → NPE→HTTP 500。此处验证空安全降级。</p>
 *
 * <p>#203: 列表响应内嵌群信息 + 页级批量化。{@code getUserNotices} 每页恰好一次
 * {@code roomGroupDao.listByRoomIds} + 一次 {@code userSummaryCache.getBatch}，
 * 禁止逐条回源（N+1）。{@code convertToVO} 为 private，通过公开入口驱动，避免为测试放宽可见性。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoticeServiceImplTest {

	@Mock private NoticeDao noticeDao;
	@Mock private PushService pushService;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private RoomGroupDao roomGroupDao;

	@InjectMocks
	private NoticeServiceImpl noticeService;

	private static final Long UID = 1L;
	private static final Long SENDER_ID = 100L;
	private static final Long RECEIVER_ID = 200L;
	private static final Long OPERATE_ID = 300L;
	private static final Long ROOM_ID = 500L;

	/** getBatch 的背衬存储：模拟真实缓存「key 缺失 → map 中该 key 值为 null」。 */
	private final Map<Long, SummeryInfoDTO> summaryStore = new HashMap<>();

	@BeforeEach
	void stubGetBatch() {
		when(userSummaryCache.getBatch(anyList())).thenAnswer(inv -> {
			List<Long> req = inv.getArgument(0);
			Map<Long, SummeryInfoDTO> result = new HashMap<>();
			for (Long id : req) {
				result.put(id, summaryStore.get(id));
			}
			return result;
		});
	}

	// ---- helpers ----

	private Notice notice() {
		Notice n = new Notice();
		n.setId(9L);
		n.setSenderId(SENDER_ID);
		n.setReceiverId(RECEIVER_ID);
		// operateId 为空 → targetUid 落回 receiverId（走 receiver 分支）
		n.setOperateId(null);
		n.setType(1);
		n.setEventType(1);
		n.setStatus(0);
		n.setIsRead(0);
		return n;
	}

	private SummeryInfoDTO summary(Long uid, String name, String avatar, Integer userType) {
		SummeryInfoDTO dto = new SummeryInfoDTO();
		dto.setUid(uid);
		dto.setName(name);
		dto.setAvatar(avatar);
		dto.setUserType(userType);
		return dto;
	}

	private RoomGroup group(Long roomId, String name, String avatar) {
		RoomGroup g = new RoomGroup();
		g.setRoomId(roomId);
		g.setName(name);
		g.setAvatar(avatar);
		return g;
	}

	/** 装配分页返回并驱动公开入口（click 默认 false → 不触发 readNotices）。 */
	private List<NoticeVO> voListFor(List<Notice> notices) {
		IPage<Notice> page = new Page<>();
		page.setRecords(notices);
		when(noticeDao.getUserNotices(anyLong(), anyBoolean(), any())).thenReturn(page);

		PageBaseResp<NoticeVO> resp = noticeService.getUserNotices(UID, new NoticeReq());
		return resp.getList();
	}

	private NoticeVO firstVoFor(Notice n) {
		return voListFor(List.of(n)).get(0);
	}

	// ==================== #157 空安全（改 getBatch 后语义回归） ====================

	@Test
	@DisplayName("receiver 为 null（被邀请人已停用）→ 不抛 NPE，receiverName/avatar 降级为 null")
	void receiverNull_noNpe_receiverFieldsNull() {
		Notice n = notice();
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		// RECEIVER_ID 不入 store → 模拟已停用用户查不到

		NoticeVO vo = assertDoesNotThrow(() -> firstVoFor(n));

		assertAll(
				() -> assertNull(vo.getReceiverName(), "receiver 缺失 → name null"),
				() -> assertNull(vo.getReceiverAvatar(), "receiver 缺失 → avatar null"),
				() -> assertNull(vo.getReceiverUserType(), "receiver 缺失 → userType 降级 null"),
				() -> assertEquals("发起人", vo.getSenderName()),
				() -> assertEquals("sender-avatar", vo.getSenderAvatar()));
	}

	@Test
	@DisplayName("sender 为 null（发起人已停用）→ 不抛 NPE，senderName/avatar 降级为 null")
	void senderNull_noNpe_senderFieldsNull() {
		Notice n = notice();
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));

		NoticeVO vo = assertDoesNotThrow(() -> firstVoFor(n));

		assertAll(
				() -> assertNull(vo.getSenderName(), "sender 缺失 → name null"),
				() -> assertNull(vo.getSenderAvatar(), "sender 缺失 → avatar null"),
				() -> assertEquals("接收人", vo.getReceiverName()),
				() -> assertEquals("receiver-avatar", vo.getReceiverAvatar()),
				() -> assertEquals(4, vo.getReceiverUserType()));
	}

	@Test
	@DisplayName("sender/receiver 均存在 → 正常填充 name/avatar/userType（回归）")
	void bothPresent_fieldsPopulated() {
		Notice n = notice();
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));

		NoticeVO vo = firstVoFor(n);

		assertAll(
				() -> assertEquals("发起人", vo.getSenderName()),
				() -> assertEquals("sender-avatar", vo.getSenderAvatar()),
				() -> assertEquals("接收人", vo.getReceiverName()),
				() -> assertEquals("receiver-avatar", vo.getReceiverAvatar()),
				() -> assertEquals(4, vo.getReceiverUserType()));
	}

	@Test
	@DisplayName("operateId 非空 → receiverUserType 取 operateId 目标（aiclaw 场景回归）")
	void operateIdPresent_userTypeFromOperateTarget() {
		Notice n = notice();
		n.setOperateId(OPERATE_ID);
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 1));
		summaryStore.put(OPERATE_ID, summary(OPERATE_ID, "aiclaw", "ai-avatar", 4));

		NoticeVO vo = firstVoFor(n);

		assertEquals(4, vo.getReceiverUserType(), "receiverUserType 应取 operateId 目标的 userType");
	}

	// ==================== #203 群信息内嵌 + 页级批量化 ====================

	@Test
	@DisplayName("#203 群通知行带 groupName/groupAvatar；好友通知行两字段 null；群表/用户缓存每页各批量查 1 次")
	void groupNotice_groupInfoFilled_friendNoticeNull_batchOnce() {
		Notice groupNotice = notice();
		groupNotice.setId(1L);
		groupNotice.setRoomId(ROOM_ID);
		Notice friendNotice = notice();
		friendNotice.setId(2L);
		friendNotice.setRoomId(null);
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));
		when(roomGroupDao.listByRoomIds(anyList())).thenReturn(List.of(group(ROOM_ID, "测试群", "group-avatar")));

		List<NoticeVO> vos = voListFor(List.of(groupNotice, friendNotice));

		NoticeVO groupVo = vos.get(0);
		NoticeVO friendVo = vos.get(1);
		assertAll(
				() -> assertEquals("测试群", groupVo.getGroupName()),
				() -> assertEquals("group-avatar", groupVo.getGroupAvatar()),
				() -> assertNull(friendVo.getGroupName(), "好友通知不带群信息"),
				() -> assertNull(friendVo.getGroupAvatar(), "好友通知不带群信息"),
				() -> assertEquals("发起人", groupVo.getSenderName(), "用户信息填充不变"),
				() -> assertEquals("接收人", groupVo.getReceiverName(), "用户信息填充不变"),
				() -> assertEquals(4, groupVo.getReceiverUserType(), "用户信息填充不变"));
		// 禁 N+1：整页恰好一次群表批量查 + 一次用户缓存批量查，逐 key get 零调用
		verify(roomGroupDao, times(1)).listByRoomIds(anyList());
		verify(userSummaryCache, times(1)).getBatch(anyList());
		verify(userSummaryCache, never()).get(any());
	}

	@Test
	@DisplayName("#203 群已解散（listByRoomIds 查不到该 roomId）→ groupName/groupAvatar 为 null")
	void dissolvedGroup_groupFieldsNull() {
		Notice groupNotice = notice();
		groupNotice.setRoomId(ROOM_ID);
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));
		// 解散群 is_del=1 → listByRoomIds 查不到
		when(roomGroupDao.listByRoomIds(anyList())).thenReturn(List.of());

		NoticeVO vo = firstVoFor(groupNotice);

		assertAll(
				() -> assertNull(vo.getGroupName()),
				() -> assertNull(vo.getGroupAvatar()));
		verify(roomGroupDao, times(1)).listByRoomIds(anyList());
	}

	@Test
	@DisplayName("#203 全好友通知页（无 roomId）→ 不查群表（空 IN 守卫）")
	void allFriendPage_noGroupQuery() {
		Notice friendNotice = notice();
		friendNotice.setRoomId(null);
		summaryStore.put(SENDER_ID, summary(SENDER_ID, "发起人", "sender-avatar", 1));
		summaryStore.put(RECEIVER_ID, summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));

		NoticeVO vo = firstVoFor(friendNotice);

		assertNull(vo.getGroupName());
		verify(roomGroupDao, never()).listByRoomIds(anyList());
		verify(userSummaryCache, times(1)).getBatch(anyList());
	}

	@Test
	@DisplayName("#203 空页 → 群表/用户缓存均不产生查询")
	void emptyPage_noQueries() {
		List<NoticeVO> vos = voListFor(List.of());

		assertEquals(0, vos.size());
		verify(roomGroupDao, never()).listByRoomIds(anyList());
		verify(userSummaryCache, never()).getBatch(anyList());
		verify(userSummaryCache, never()).get(any());
	}
}
