package com.luohuo.flex.im.controller;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ThinkingControllerMembersTest {
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
