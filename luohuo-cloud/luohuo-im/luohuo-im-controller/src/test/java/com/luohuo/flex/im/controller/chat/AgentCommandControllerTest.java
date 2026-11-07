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

import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentCommandControllerTest {
    @AfterEach void clear() { ContextUtil.remove(); ContextUtil.clearTenantContext(); }
    @Test void callbackCarriesBoundedEnvelopeButCannotSupplyReceiptRevisionOrProviderBag() throws Exception {
        var service = mock(AgentCommandService.class); var auth = new ThinkingInternalAuth();
        ReflectionTestUtils.setField(auth, "internalSecret", "test-only-internal-secret");
        var mvc = MockMvcBuilders.standaloneSetup(new AgentCommandController(service, auth)).build();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        when(service.complete(any())).thenAnswer(inv -> {
            AgentCommand.Callback callback = inv.getArgument(0);
            assertEquals("outcome", callback.getPhase()); assertEquals("g1", callback.getGeneration());
            assertEquals("s1", callback.getNativeRef().getSessionId()); assertEquals("reset", callback.getEffect().getKind());
            assertTrue(callback.getReconciled());
            var projected = json.valueToTree(callback);
            assertFalse(projected.has("receiptRevision")); assertFalse(projected.has("nativeResult"));
            assertFalse(projected.path("nativeRef").has("privatePath")); assertFalse(projected.path("effect").has("apiKey"));
            var result = new AgentCommand.Result(); result.setReceiptRevision(2); return result;
        });
        mvc.perform(post("/aiclaw/commands/result").contentType("application/json").content("""
                {"requestId":"r1","roomId":"20","status":"completed","phase":"outcome","generation":"g1","reconciled":true,
                 "receiptRevision":999,"nativeResult":{"secret":"discard"},"nativeRef":{"sessionId":"s1","privatePath":"discard"},
                 "effect":{"kind":"reset","sessionId":"s2","generation":"g2","apiKey":"discard"}}
                """)
                .header(ThinkingInternalAuth.SERVICE_AUTH, "test-only-internal-secret")
                .header(ThinkingInternalAuth.ACTOR, "30").header(ContextConstants.HEADER_TENANT_ID, "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.receiptRevision").value(2));
    }
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

    @Test void approvalEndpointsStayOwnerScopedAndLiterallyRouted() throws Exception {
        var service = mock(AgentCommandService.class); var auth = new ThinkingInternalAuth();
        var mvc = MockMvcBuilders.standaloneSetup(new AgentCommandController(service, auth)).build();
        var approval = new AgentCommand.Approval();
        approval.setRequestId("req-1"); approval.setRoomId("20"); approval.setAiclawUid("30");
        approval.setCapabilityId("model.list"); approval.setCallerUid("11"); approval.setExpiresAt(60_000L);
        when(service.approvals()).thenReturn(List.of(approval));
        var approved = new AgentCommand.Result(); approved.setRequestId("req-1"); approved.setApprovalStatus("approved");
        when(service.approve("req-1")).thenReturn(approved);
        var rejected = new AgentCommand.Result(); rejected.setRequestId("req-1"); rejected.setApprovalStatus("rejected");
        when(service.reject("req-1")).thenReturn(rejected);

        mvc.perform(get("/aiclaw/commands/approvals"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].callerUid").value("11"));
        verify(service, never()).get("approvals");
        mvc.perform(post("/aiclaw/commands/req-1/approve").contentType("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approvalStatus").value("approved"));
        mvc.perform(post("/aiclaw/commands/req-1/reject").contentType("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approvalStatus").value("rejected"));
        verify(service).approve("req-1");
        verify(service).reject("req-1");
    }
}
