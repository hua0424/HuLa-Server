package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.context.ContextConstants;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.controller.ThinkingInternalAuth;
import com.luohuo.flex.im.core.chat.service.AgentCommandService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AgentCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentCommandControllerTest {
    @AfterEach void clear() { ContextUtil.remove(); ContextUtil.clearTenantContext(); }
    @Test void resultCallbackRequiresGatewayProofAndUsesItsActorNotPayload() throws Exception {
        var service = mock(AgentCommandService.class); var auth = new ThinkingInternalAuth();
        ReflectionTestUtils.setField(auth, "internalSecret", "test-only-internal-secret");
        var mvc = MockMvcBuilders.standaloneSetup(new AgentCommandController(service, auth)).build();
        String body = "{\"requestId\":\"req-1\",\"roomId\":\"20\",\"status\":\"completed\",\"actorUid\":\"999\"}";
        assertThrows(Exception.class, () -> mvc.perform(post("/aiclaw/commands/result").contentType("application/json").content(body)));
        verifyNoInteractions(service);
        when(service.complete(any())).thenAnswer(inv -> { assertEquals(30L, ContextUtil.getUid()); assertEquals(1L, ContextUtil.getTenantId()); return new AgentCommand.Result(); });
        mvc.perform(post("/aiclaw/commands/result").contentType("application/json").content(body)
                .header(ThinkingInternalAuth.SERVICE_AUTH, "test-only-internal-secret")
                .header(ThinkingInternalAuth.ACTOR, "30").header(ContextConstants.HEADER_TENANT_ID, "1"))
                .andExpect(status().isOk());
        verify(service).complete(any());
    }
}
