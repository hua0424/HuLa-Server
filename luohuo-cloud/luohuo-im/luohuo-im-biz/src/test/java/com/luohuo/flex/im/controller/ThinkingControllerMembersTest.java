package com.luohuo.flex.im.controller;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ThinkingControllerMembersTest {
	@Test
	void legacyStartResponseStillSerializesAsScalarForOldWs() throws Exception {
		ThinkingController controller = new ThinkingController();
		ThinkingInternalAuth auth = mock(ThinkingInternalAuth.class);
		ThinkingService service = mock(ThinkingService.class);
		ReflectionTestUtils.setField(controller, "internalAuth", auth);
		ReflectionTestUtils.setField(controller, "thinkingService", service);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(auth.require(request)).thenReturn(101L);
		when(service.create(101L, 10L, 77L)).thenReturn(999L);
		WSThinkingStart start = WSThinkingStart.builder().roomId("10").triggerMsgId("77").build();
		String json = new ObjectMapper().writeValueAsString(controller.start(start, request));
		assertEquals("999", new ObjectMapper().readTree(json).get("data").toString());
		verify(service, never()).create(anyLong(), anyLong(), anyLong(), anyString());
	}

	@Test
	void nonMemberCannotObtainRecipientsForStreamPush() {
		ThinkingController controller = new ThinkingController();
		ThinkingInternalAuth auth = mock(ThinkingInternalAuth.class);
		ThinkingService service = mock(ThinkingService.class);
		ReflectionTestUtils.setField(controller, "internalAuth", auth);
		ReflectionTestUtils.setField(controller, "thinkingService", service);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(auth.require(request)).thenReturn(101L);
		ContextUtil.setTenantId(9L);
		try {
			doThrow(new BizException("非房间成员")).when(service).requireActiveAgent(101L, 10L, 9L);
			assertThrows(BizException.class, () -> controller.getRoomMembers(10L, request));
		} finally {
			ContextUtil.remove();
			ContextUtil.clearTenantContext();
		}
	}
}
