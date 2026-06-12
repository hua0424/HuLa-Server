package com.luohuo.flex.ws.websocket.processor;

import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.WSBaseReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-004 [S4] ThinkingProcessor.supports：DELTA(21) 不再受理，仅 START(20)/END(22) 受理。
 * 纯 JUnit 单元测试，supports() 不触碰任何 @Resource 字段。
 */
class ThinkingProcessorTest {

	private final ThinkingProcessor processor = new ThinkingProcessor();

	private WSBaseReq reqOfType(int type) {
		WSBaseReq req = new WSBaseReq();
		req.setType(type);
		return req;
	}

	@Test
	@DisplayName("supports: THINKING_DELTA(21) → false（S4 已废弃）")
	void supports_thinkingDelta_false() {
		assertFalse(processor.supports(reqOfType(WSReqTypeEnum.THINKING_DELTA.getType())));
	}

	@Test
	@DisplayName("supports: THINKING_START(20) → true")
	void supports_thinkingStart_true() {
		assertTrue(processor.supports(reqOfType(WSReqTypeEnum.THINKING_START.getType())));
	}

	@Test
	@DisplayName("supports: THINKING_END(22) → true")
	void supports_thinkingEnd_true() {
		assertTrue(processor.supports(reqOfType(WSReqTypeEnum.THINKING_END.getType())));
	}

	// TODO: ctx-null handleEnd path covered by tester E2E (cross-restart) —
	// 单元测试无法在不真实联网的前提下驱动：handleEnd 的成员重建依赖 resolveImServiceUrl()
	// 返回真实 URL，而一旦返回真实 URL，同一路径上的 finalizeViaHttp()（self-instantiated final
	// WebClient）会发起真实 reactive 调用；queryRoomMembersViaHttp 又是静态 Hutool 链式调用。
	// 干净单测需对 ThinkingProcessor 做 HTTP/discovery 接缝重构，超出本次 review 修复范围。
}
