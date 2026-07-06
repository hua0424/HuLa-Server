package com.luohuo.flex.im.core.user.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.luohuo.flex.im.core.user.dao.NoticeDao;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.dto.SummeryInfoDTO;
import com.luohuo.flex.im.domain.entity.Notice;
import com.luohuo.flex.im.domain.vo.req.NoticeReq;
import com.luohuo.flex.im.domain.vo.res.NoticeVO;
import com.luohuo.flex.im.domain.vo.res.PageBaseResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * #157: 邀请「已停用/注销」用户时不得 500。
 *
 * <p>已停用的 aiclaw 其 {@code im_user.is_del=1}，{@code UserSummaryCache.get} 因此返回 null。
 * 旧 {@code convertToVO} 无脑解引用 sender/receiver → NPE→HTTP 500。此处验证空安全降级。</p>
 *
 * <p>{@code convertToVO} 为 private，通过公开入口 {@link NoticeServiceImpl#getUserNotices} 驱动
 * （它对每条记录调用 convertToVO），避免为测试放宽可见性。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoticeServiceImplTest {

	@Mock private NoticeDao noticeDao;
	@Mock private PushService pushService;
	@Mock private UserSummaryCache userSummaryCache;

	@InjectMocks
	private NoticeServiceImpl noticeService;

	private static final Long UID = 1L;
	private static final Long SENDER_ID = 100L;
	private static final Long RECEIVER_ID = 200L;

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

	/** 装配单条记录的分页返回（click 默认 false → 不触发 readNotices）。 */
	private NoticeVO firstVoFor(Notice n) {
		IPage<Notice> page = new Page<>();
		page.setRecords(List.of(n));
		when(noticeDao.getUserNotices(anyLong(), anyBoolean(), any())).thenReturn(page);

		PageBaseResp<NoticeVO> resp = noticeService.getUserNotices(UID, new NoticeReq());
		return resp.getList().get(0);
	}

	// ==================== #157 空安全 ====================

	@Test
	@DisplayName("receiver 为 null（被邀请人已停用）→ 不抛 NPE，receiverName/avatar 降级为 null")
	void receiverNull_noNpe_receiverFieldsNull() {
		Notice n = notice();
		when(userSummaryCache.get(SENDER_ID)).thenReturn(summary(SENDER_ID, "发起人", "sender-avatar", 1));
		when(userSummaryCache.get(RECEIVER_ID)).thenReturn(null);

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
		when(userSummaryCache.get(SENDER_ID)).thenReturn(null);
		when(userSummaryCache.get(RECEIVER_ID)).thenReturn(summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));

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
		when(userSummaryCache.get(SENDER_ID)).thenReturn(summary(SENDER_ID, "发起人", "sender-avatar", 1));
		when(userSummaryCache.get(RECEIVER_ID)).thenReturn(summary(RECEIVER_ID, "接收人", "receiver-avatar", 4));

		NoticeVO vo = firstVoFor(n);

		assertAll(
				() -> assertEquals("发起人", vo.getSenderName()),
				() -> assertEquals("sender-avatar", vo.getSenderAvatar()),
				() -> assertEquals("接收人", vo.getReceiverName()),
				() -> assertEquals("receiver-avatar", vo.getReceiverAvatar()),
				() -> assertEquals(4, vo.getReceiverUserType()));
	}
}
