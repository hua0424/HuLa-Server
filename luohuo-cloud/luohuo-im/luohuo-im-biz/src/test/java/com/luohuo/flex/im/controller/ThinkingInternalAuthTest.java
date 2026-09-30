package com.luohuo.flex.im.controller;

import com.luohuo.basic.context.ContextConstants;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThinkingInternalAuthTest {
	private final ThinkingInternalAuth auth = new ThinkingInternalAuth();

	@AfterEach void clear() {
		ContextUtil.remove();
		ContextUtil.clearTenantContext();
	}

	private HttpServletRequest request(String secret, String tenant) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(ThinkingInternalAuth.SERVICE_AUTH)).thenReturn(secret);
		when(request.getHeader(ThinkingInternalAuth.ACTOR)).thenReturn("42");
		when(request.getHeader(ContextConstants.HEADER_TENANT_ID)).thenReturn(tenant);
		return request;
	}

	@Test void onlyServiceCanSetExplicitCallerAndTenant() {
		ReflectionTestUtils.setField(auth, "internalSecret", "test-internal-secret");
		assertThrows(BizException.class, () -> auth.require(request("forged", "9")));
		assertThrows(BizException.class, () -> auth.require(request("test-internal-secret", null)));
		assertEquals(42L, auth.require(request("test-internal-secret", "9")));
		assertEquals(9L, ContextUtil.getTenantId());
	}

	@Test void timeoutNeedsExplicitServiceIdentity() {
		ReflectionTestUtils.setField(auth, "internalSecret", "test-internal-secret");
		HttpServletRequest request = request("test-internal-secret", "9");
		assertThrows(BizException.class, () -> auth.requireTimeout(request));
		when(request.getHeader(ThinkingInternalAuth.SERVICE_TIMEOUT)).thenReturn("true");
		assertThrows(BizException.class, () -> auth.require(request));
		assertEquals(42L, auth.requireTimeout(request));
	}
}
