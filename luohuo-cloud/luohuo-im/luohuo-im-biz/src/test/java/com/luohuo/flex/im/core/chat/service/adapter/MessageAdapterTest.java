package com.luohuo.flex.im.core.chat.service.adapter;

import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISS-015: 验证 {@link MessageAdapter#buildMsgSave} 对流式消息 sendTime 的处理:
 * <ul>
 *   <li>skipPush=true + sendTime 合法 → createTime 被回灌为 sendTime</li>
 *   <li>skipPush=false → 即使带 sendTime 也忽略(交给 MetaObjectHandler 填 now())</li>
 *   <li>未来 sendTime → clamp 到 now</li>
 *   <li>超出 5 分钟回溯窗口 → clamp 到 now-5min</li>
 * </ul>
 */
class MessageAdapterTest {

	private ChatMessageReq baseReq() {
		return ChatMessageReq.builder()
				.roomId(1L)
				.msgType(1)
				.body("test")
				.build();
	}

	@Test
	@DisplayName("skipPush=true + sendTime 在窗口内 → createTime 被覆盖")
	void streamPathHonorsSendTime() {
		LocalDateTime streamStart = LocalDateTime.now().minusMinutes(1);
		ChatMessageReq req = baseReq();
		req.setSkipPush(true);
		req.setSendTime(streamStart);

		Message msg = MessageAdapter.buildMsgSave(req, 100L);

		assertNotNull(msg.getCreateTime(), "createTime 应被覆盖,不应为 null");
		assertEquals(streamStart, msg.getCreateTime(),
				"createTime 应等于传入的 sendTime");
	}

	@Test
	@DisplayName("skipPush=false → sendTime 被忽略,createTime 留 null 让 MetaObjectHandler 填 now()")
	void nonStreamPathIgnoresSendTime() {
		ChatMessageReq req = baseReq();
		req.setSkipPush(false);
		req.setSendTime(LocalDateTime.now().minusMinutes(1));

		Message msg = MessageAdapter.buildMsgSave(req, 100L);

		assertNull(msg.getCreateTime(),
				"非流式路径不应让请求体伪造 createTime,留 null 交给 MetaObjectHandler");
	}

	@Test
	@DisplayName("skipPush=true + sendTime 为 null → 维持默认行为,createTime 不被强写")
	void streamPathNoSendTimeKeepsNull() {
		ChatMessageReq req = baseReq();
		req.setSkipPush(true);
		req.setSendTime(null);

		Message msg = MessageAdapter.buildMsgSave(req, 100L);

		assertNull(msg.getCreateTime(),
				"未传 sendTime 时不覆盖,留 null 走 MetaObjectHandler");
	}

	@Test
	@DisplayName("未来时间(now+1h) → clamp 到 now,防止 sendTime 倒灌未来")
	void futureSendTimeClampedToNow() {
		LocalDateTime before = LocalDateTime.now();
		ChatMessageReq req = baseReq();
		req.setSkipPush(true);
		req.setSendTime(LocalDateTime.now().plusHours(1));

		Message msg = MessageAdapter.buildMsgSave(req, 100L);
		LocalDateTime after = LocalDateTime.now();

		assertNotNull(msg.getCreateTime());
		assertTrue(!msg.getCreateTime().isBefore(before)
						&& !msg.getCreateTime().isAfter(after),
				"未来 sendTime 应被 clamp 到 [before, after] 区间内的 now,实际=" + msg.getCreateTime());
	}

	@Test
	@DisplayName("过期 6 分钟 → clamp 到 now-5min,防止把消息插到很久以前")
	void backdatedSendTimeClampedToFloor() {
		ChatMessageReq req = baseReq();
		req.setSkipPush(true);
		req.setSendTime(LocalDateTime.now().minusMinutes(6));

		LocalDateTime before = LocalDateTime.now();
		Message msg = MessageAdapter.buildMsgSave(req, 100L);
		LocalDateTime after = LocalDateTime.now();

		assertNotNull(msg.getCreateTime());
		// floor = now - 5min,因测试执行有耗时,允许 ±1s 偏差,但必须在 [before-5min, after-5min] 区间
		LocalDateTime expectedLow = before.minusMinutes(MessageAdapter.MAX_SEND_TIME_BACKDATE_MINUTES);
		LocalDateTime expectedHigh = after.minusMinutes(MessageAdapter.MAX_SEND_TIME_BACKDATE_MINUTES);
		assertTrue(!msg.getCreateTime().isBefore(expectedLow)
						&& !msg.getCreateTime().isAfter(expectedHigh),
				"过期 sendTime 应 clamp 到 now-5min,实际=" + msg.getCreateTime()
						+ " 期望 [" + expectedLow + ", " + expectedHigh + "]");
	}

	@Test
	@DisplayName("clamp 边界: 正好 4 分 59 秒之前 → 不 clamp,原样保留")
	void sendTimeWithinWindowNotClamped() {
		LocalDateTime within = LocalDateTime.now()
				.minusMinutes(4)
				.minusSeconds(59);
		ChatMessageReq req = baseReq();
		req.setSkipPush(true);
		req.setSendTime(within);

		Message msg = MessageAdapter.buildMsgSave(req, 100L);

		assertEquals(within, msg.getCreateTime(),
				"窗口内 sendTime 不应被 clamp");
	}

	@Test
	@DisplayName("clampSendTime 单元: 当前时间不被 clamp(平凡用例)")
	void clampSendTimeIdempotentNearNow() {
		LocalDateTime t = LocalDateTime.now().minusSeconds(1);
		LocalDateTime clamped = MessageAdapter.clampSendTime(t);
		long deltaMs = ChronoUnit.MILLIS.between(t, clamped);
		assertTrue(Math.abs(deltaMs) < 50,
				"距 now 1s 的 sendTime 不应被 clamp,deltaMs=" + deltaMs);
	}
}
