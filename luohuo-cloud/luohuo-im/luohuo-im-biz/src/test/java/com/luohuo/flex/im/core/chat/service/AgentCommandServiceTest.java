package com.luohuo.flex.im.core.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.mapper.GroupRunAdmissionMapper;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.*;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AgentCommand;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCommandServiceTest {
    private final GroupRunAdmissionMapper facts = mock(GroupRunAdmissionMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String,String> values = mock(ValueOperations.class);
    private final PushService push = mock(PushService.class);
    private final ChatService chat = mock(ChatService.class);
    private final GroupMemberDao members = mock(GroupMemberDao.class);
    private final GroupRunAdmissionService admission = mock(GroupRunAdmissionService.class);
    private final RoomGroupCache groups = mock(RoomGroupCache.class);
    private final AgentCommandService service = new AgentCommandService(facts, admission, members, groups, redis, push, chat);
    private final Map<String,String> storage = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private User human;
    private Room room;
    private RoomFriend friend;

    @BeforeEach void setup() {
        ContextUtil.setUid(11L); ContextUtil.setTenantId(1L);
        human = new User(); human.setId(11L); human.setTenantId(1L); human.setState(0); human.setUserType(3);
        when(facts.selectUser(11L)).thenReturn(human);
        room = new Room(); room.setId(20L); room.setType(2);
        when(facts.selectRoom(20L, 1L)).thenReturn(room);
        friend = new RoomFriend(); friend.setUid1(11L); friend.setUid2(30L);
        when(facts.selectFriendMembership(11L, 20L, 1L)).thenReturn(friend);
        when(admission.admitGroupRun(eq(30L), eq(20L), eq(1L), any())).thenReturn(
                GroupRunAdmissionService.Admission.builder().outcome("ADMITTED").authorizationEpoch("epoch-1").build());
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> storage.get(i.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), eq(Duration.ofHours(24))))
                .thenAnswer(i -> storage.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(i -> {
            String key = ((List<String>)i.getArgument(1)).get(0);
            return storage.replace(key, i.getArgument(2), i.getArgument(3)) ? 1L : 0L;
        });
        when(push.submitPushMsg(any(), eq(30L), eq(11L))).thenReturn(PushSubmission.resolved(
                PushSubmission.targetsOf("node", Map.of("device", 30L))));
        when(chat.sendMsg(any(), eq(11L))).thenReturn(123L);
    }
    @AfterEach void cleanup() { service.close(); ContextUtil.remove(); ContextUtil.clearTenantContext(); }
    private AgentCommand.Request request(String operation) {
        AgentCommand.Request r = new AgentCommand.Request(); r.setRequestId("req-1"); r.setRoomId("20");
        r.setAiclawUid("30"); r.setOperation(operation);
        if (operation.equals("invoke")) { r.setCapabilityId("model.list"); r.setCatalogVersion("catalog-1"); }
        return r;
    }
    private AgentCommand.Callback callback() {
        ContextUtil.setUid(30L);
        var c = new AgentCommand.Callback(); c.setRequestId("req-1"); c.setRoomId("20"); c.setStatus("completed"); c.setText("Available models:\nprovider/model"); return c;
    }

    @Test void discoveryIsDeliveredOnlyToAdmittedTargetAndNeverMeansCompletion() {
        assertEquals("accepted", service.submit(request("discover")).getStatus());
        verify(push).submitPushMsg(argThat(ws -> ws.getType().equals("agentCommand") &&
                json.valueToTree(ws.getData()).path("context").path("counterpartUid").asText().equals("11") &&
                json.valueToTree(ws.getData()).path("context").path("authorizationEpoch").asText().equals("epoch-1")), eq(30L), eq(11L));
        verifyNoInteractions(chat);
    }
    @ParameterizedTest @ValueSource(ints={1,2,4,0}) void onlyActiveNormalHumanCanSubmit(int type) {
        human.setUserType(type);
        assertThrows(BizException.class, () -> service.submit(request("discover")));
        verifyNoInteractions(push); assertTrue(storage.isEmpty());
    }
    @Test void disabledAndCrossTenantHumansDenied() {
        human.setState(1); assertThrows(BizException.class, () -> service.submit(request("discover")));
        human.setState(0); human.setTenantId(2L); assertThrows(BizException.class, () -> service.submit(request("discover")));
        verifyNoInteractions(push);
    }
    @Test void wrongConversationBlockedAndRevokedTargetDenied() {
        friend.setUid2(99L); assertThrows(BizException.class, () -> service.submit(request("discover")));
        friend.setUid2(30L); friend.setDeFriend1(true); assertThrows(BizException.class, () -> service.submit(request("discover")));
        friend.setDeFriend1(false);
        when(admission.admitGroupRun(30L,20L,1L,null)).thenReturn(GroupRunAdmissionService.Admission.builder().outcome("DENIED").authorizationEpoch("not-null-is-not-admission").build());
        assertThrows(BizException.class, () -> service.submit(request("discover"))); verifyNoInteractions(push);
    }
    @Test void groupRequiresBothHumanMembershipAndApprovedAgentAdmission() {
        room.setType(1);
        assertThrows(BizException.class, () -> service.submit(request("discover")));
        when(facts.selectMembership(11L,20L,1L)).thenReturn(1L);
        var member = new GroupMember(); member.setDeFriend(true); when(members.getMember(20L,11L)).thenReturn(member);
        assertThrows(BizException.class, () -> service.submit(request("discover")));
        member.setDeFriend(false);
        var group = new RoomGroup(); group.setAccount("group-account-not-room-id");
        when(groups.getByRoomIdFromDb(20L)).thenReturn(group);
        assertEquals("accepted", service.submit(request("discover")).getStatus());
        verify(push).submitPushMsg(argThat(ws -> json.valueToTree(ws.getData()).path("context").path("account")
                .asText().equals("group-account-not-room-id")), eq(30L), eq(11L));
    }
    @Test void duplicatesDoNotDispatchAndChangedRequestCannotReuseId() {
        assertEquals("accepted", service.submit(request("discover")).getStatus());
        assertEquals("accepted", service.submit(request("discover")).getStatus());
        assertThrows(BizException.class, () -> service.submit(request("invoke")));
        verify(push, times(1)).submitPushMsg(any(), eq(30L), eq(11L));
    }
    @Test void anotherHumanCannotReadOrReplayRequest() {
        service.submit(request("discover"));
        var other = new User(); other.setTenantId(1L); other.setState(0); other.setUserType(3);
        when(facts.selectUser(12L)).thenReturn(other); ContextUtil.setUid(12L);
        assertThrows(BizException.class, () -> service.get("req-1"));
        assertThrows(BizException.class, () -> service.submit(request("discover")));
    }
    @Test void offlineInvokeHasOrdinaryFeedbackButNoAgentCompletionClaim() {
        when(push.submitPushMsg(any(), eq(30L), eq(11L))).thenReturn(PushSubmission.noTarget());
        assertEquals("offline", service.submit(request("invoke")).getStatus());
        ArgumentCaptor<ChatMessageReq> sent = ArgumentCaptor.forClass(ChatMessageReq.class);
        verify(chat).sendMsg(sent.capture(), eq(11L));
        assertEquals("req-1", sent.getValue().getCommandFeedbackRequestId());
        assertTrue(sent.getValue().getBody().toString().contains("命令反馈 · req-1 · Agent 30 · offline"));
        service.get("req-1"); verify(chat, times(1)).sendMsg(any(), eq(11L));
    }
    @Test void unknownCapabilitiesAndParametersNeverReachNative() {
        var r = request("invoke"); r.setParameters(Map.of("secret", "bad"));
        assertThrows(BizException.class, () -> service.submit(r));
        r.setParameters(Map.of()); r.setCapabilityId("command:arbitrary");
        assertEquals("unsupported", service.submit(r).getStatus()); verifyNoInteractions(push);
    }
    @Test void executionParametersAreCanonicalWithoutRewritingNativeArguments() {
        var r = request("invoke"); r.setCapabilityId("skill:fixture");
        r.setParameters(Map.of("arguments", "  https://example.test/<literal>\nvalue  "));
        assertEquals("accepted", service.submit(r).getStatus());
        verify(push).submitPushMsg(argThat(ws -> json.valueToTree(ws.getData()).path("parameters").path("arguments")
                .asText().equals("  https://example.test/<literal>\nvalue  ")), eq(30L), eq(11L));
        var model = request("invoke"); model.setRequestId("model-1"); model.setCapabilityId("model.set");
        var parameters = new LinkedHashMap<String,Object>(); parameters.put("modelId", "org/model"); parameters.put("providerId", "provider");
        model.setParameters(parameters); assertEquals("accepted", service.submit(model).getStatus());
        model.setParameters(Map.of("providerId", "provider", "modelId", "org/model"));
        assertEquals("accepted", service.submit(model).getStatus());
        verify(push, times(2)).submitPushMsg(any(), eq(30L), eq(11L));
    }
    @Test void callbackBindsAgentTenantRoomThenImmutableResultAndFeedback() {
        service.submit(request("invoke")); var c = callback(); ContextUtil.setUid(31L);
        assertThrows(BizException.class, () -> service.complete(c));
        ContextUtil.setUid(30L); c.setRoomId("21"); assertThrows(BizException.class, () -> service.complete(c));
        c.setRoomId("20"); ContextUtil.setTenantId(2L); assertThrows(BizException.class, () -> service.complete(c));
        ContextUtil.setTenantId(1L);
        assertEquals("completed", service.complete(c).getStatus());
        assertEquals(30L, ContextUtil.getUid(), "human feedback must restore callback identity");
        c.setStatus("failed"); assertEquals("completed", service.complete(c).getStatus());
        verify(chat, times(1)).sendMsg(any(), eq(11L));
        ContextUtil.setUid(11L); assertEquals("completed",service.get("req-1").getStatus());
    }
    @Test void staleCatalogIsAnHonestFailureAndRawErrorsAreNeverPublic() {
        service.submit(request("invoke")); var c = callback(); c.setStatus("failed"); c.setError("catalog_stale");
        assertEquals("catalog_stale",service.complete(c).getError());
        ContextUtil.setUid(11L); var r = request("invoke"); r.setRequestId("req-2"); service.submit(r);
        c.setRequestId("req-2"); c.setError("apiKey=secret private host /root"); ContextUtil.setUid(30L);
        assertEquals("native_request_failed",service.complete(c).getError());
    }
    private AgentCommand.Callback nativeCallback(String fields) throws Exception {
        ContextUtil.setUid(30L);
        return json.readValue("{\"requestId\":\"req-1\",\"roomId\":\"20\"," + fields + "}", AgentCommand.Callback.class);
    }
    private void pastDeadline() throws Exception {
        String key = storage.keySet().iterator().next();
        var pending = json.readValue(storage.get(key), AgentCommandService.Pending.class);
        pending.setDeadline(1); storage.put(key, json.writeValueAsString(pending));
    }
    @Test void nativeAckKeepsLongSkillAcceptedPastDispatchDeadlineThenCompletes() throws Exception {
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); service.submit(r);
        var ack = nativeCallback("\"status\":\"accepted\",\"phase\":\"accepted\",\"generation\":\"g-old\",\"nativeRef\":{\"sessionId\":\"ses-old\",\"inputId\":\"user-old\"}");
        assertEquals("accepted", service.complete(ack).getStatus());
        pastDeadline(); ContextUtil.setUid(11L);
        assertEquals("accepted", service.get("req-1").getStatus()); verifyNoInteractions(chat);
        var done = nativeCallback("\"status\":\"completed\",\"phase\":\"outcome\",\"generation\":\"g-old\",\"nativeRef\":{\"sessionId\":\"ses-old\",\"inputId\":\"user-old\",\"messageId\":\"assistant-old\",\"parentId\":\"user-old\"},\"text\":\"done\"");
        var result = json.valueToTree(service.complete(done));
        assertEquals("completed", result.path("status").asText()); assertEquals("outcome", result.path("phase").asText());
        assertEquals(1, result.path("receiptRevision").asInt());
        assertEquals("ses-old", result.path("nativeRef").path("sessionId").asText());
        verify(chat, times(1)).sendMsg(any(), eq(11L));
    }
    @Test void lostAckUnknownReconcilesOnlyOnceWithMatchingBindingAndSeparateFeedbackReceipt() throws Exception {
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); service.submit(r); pastDeadline();
        assertEquals("unknown", service.get("req-1").getStatus());
        var ack = nativeCallback("\"status\":\"accepted\",\"phase\":\"accepted\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\"}");
        assertEquals("unknown", service.complete(ack).getStatus());
        var done = nativeCallback("\"status\":\"completed\",\"phase\":\"outcome\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"a1\",\"parentId\":\"u1\"},\"text\":\"done\"");
        assertEquals("unknown", service.complete(done).getStatus(), "uncertainty is not permission to infer completion");
        done.setReconciled(true);
        var result = service.complete(done);
        assertEquals("completed", result.getStatus()); assertEquals(2, result.getReceiptRevision()); assertTrue(result.getReconciled());
        assertEquals("completed", service.complete(done).getStatus());
        done.setStatus("failed"); assertEquals("completed", service.complete(done).getStatus());
        var messages = ArgumentCaptor.forClass(ChatMessageReq.class); verify(chat, times(2)).sendMsg(messages.capture(), eq(11L));
        assertNotEquals(messages.getAllValues().get(0).getRequestId(), messages.getAllValues().get(1).getRequestId());
        assertTrue(messages.getAllValues().get(0).getBody().toString().contains("unknown"));
        assertTrue(messages.getAllValues().get(1).getBody().toString().contains("completed"));
        verify(push, times(1)).submitPushMsg(any(), eq(30L), eq(11L));
    }
    @Test void nativeBindingAndSkillParentCannotBeChangedByAnotherHttpCompletion() throws Exception {
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); service.submit(r);
        service.complete(nativeCallback("\"status\":\"accepted\",\"phase\":\"accepted\",\"generation\":\"old\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\"}"));
        var wrongGeneration = nativeCallback("\"status\":\"completed\",\"generation\":\"new\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"a1\",\"parentId\":\"u1\"}");
        assertThrows(BizException.class, () -> service.complete(wrongGeneration));
        var wrongParent = nativeCallback("\"status\":\"completed\",\"generation\":\"old\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"same-http-assistant\",\"parentId\":\"u2\"}");
        assertThrows(BizException.class, () -> service.complete(wrongParent)); verifyNoInteractions(chat);
    }
    @Test void modelSelectionHasTypedNextRequestEffectWithoutInventingNativeSession() throws Exception {
        var r = request("invoke"); r.setCapabilityId("model.set");
        r.setParameters(Map.of("providerId", "p", "modelId", "org/model")); service.submit(r);
        var done = nativeCallback("\"status\":\"completed\",\"effect\":{\"kind\":\"model\",\"providerId\":\"p\",\"modelId\":\"org/model\",\"effective\":\"next_request\",\"sessionId\":\"unrelated\",\"apiKey\":\"discard\"}");
        var result = json.valueToTree(service.complete(done));
        assertEquals("next_request", result.path("effect").path("effective").asText());
        assertEquals("org/model", result.path("effect").path("modelId").asText());
        assertFalse(result.has("nativeRef")); assertFalse(result.toString().contains("unrelated")); assertFalse(result.toString().contains("discard"));
    }
    @Test void resetPublishesNewBindingAndOldSkillCanStillSettleItsOwnRequest() throws Exception {
        var skill = request("invoke"); skill.setCapabilityId("skill:fixture"); service.submit(skill);
        service.complete(nativeCallback("\"status\":\"accepted\",\"phase\":\"accepted\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\"}"));
        ContextUtil.setUid(11L); var reset = request("invoke"); reset.setRequestId("reset-1"); reset.setCapabilityId("session.reset"); service.submit(reset);
        var resetDone = nativeCallback("\"status\":\"completed\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\"},\"effect\":{\"kind\":\"reset\",\"sessionId\":\"s2\",\"generation\":\"g2\"}"); resetDone.setRequestId("reset-1");
        var result = service.complete(resetDone);
        assertEquals("s1", result.getNativeRef().getSessionId()); assertEquals("s2", result.getEffect().getSessionId());
        var oldDone = nativeCallback("\"status\":\"completed\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"a1\",\"parentId\":\"u1\"}");
        assertEquals("completed", service.complete(oldDone).getStatus());
        ContextUtil.setUid(11L); assertEquals("s2", service.get("reset-1").getEffect().getSessionId());
    }
    @Test void compactHttpSuccessCannotReplaceSummaryEvidence() throws Exception {
        var r = request("invoke"); r.setCapabilityId("session.compact"); service.submit(r);
        var noEvidence = nativeCallback("\"status\":\"completed\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\"}");
        assertThrows(BizException.class, () -> service.complete(noEvidence));
        var done = nativeCallback("\"status\":\"completed\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\"},\"effect\":{\"kind\":\"compact\",\"controlId\":\"c1\",\"summaryId\":\"a1\",\"summary\":true}");
        assertTrue(service.complete(done).getEffect().getSummary());
    }
    @Test void completionRereadsCompetingNativeAckInsteadOfLosingOutcome() throws Exception {
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); service.submit(r);
        var done = nativeCallback("\"status\":\"completed\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"a1\",\"parentId\":\"u1\"}");
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i -> {
            String key = ((List<String>) i.getArgument(1)).get(0);
            if (first.getAndSet(false)) {
                var ack = json.readValue(storage.get(key), AgentCommandService.Pending.class);
                ack.getResult().setPhase("accepted"); ack.getResult().setGeneration("g1");
                ack.getResult().setNativeRef(json.readValue("{\"sessionId\":\"s1\",\"inputId\":\"u1\"}", AgentCommand.NativeRef.class));
                storage.put(key, json.writeValueAsString(ack)); return 0L;
            }
            return storage.replace(key, i.getArgument(2), i.getArgument(3)) ? 1L : 0L;
        }).when(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        assertEquals("completed", service.complete(done).getStatus()); verify(chat, times(1)).sendMsg(any(), eq(11L));
    }
    @Test void feedbackCasRaceCannotOverwriteReconciledFinalOrCollideSqlReceipts() throws Exception {
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); service.submit(r); pastDeadline();
        var done = nativeCallback("\"status\":\"completed\",\"reconciled\":true,\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\",\"inputId\":\"u1\",\"messageId\":\"a1\",\"parentId\":\"u1\"}");
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        Map<String,String> receipts = new LinkedHashMap<>();
        doAnswer(i -> {
            ChatMessageReq message = i.getArgument(0);
            String body = message.getBody().toString();
            String old = receipts.putIfAbsent(message.getRequestId(), body);
            if (old != null) assertEquals(old, body, "SQL receipt body cannot change under a CAS race");
            if (first.getAndSet(false)) {
                ContextUtil.setUid(30L); assertEquals("completed", service.complete(done).getStatus()); ContextUtil.setUid(11L);
            }
            return body.contains("completed") ? 124L : 123L;
        }).when(chat).sendMsg(any(), eq(11L));
        ContextUtil.setUid(11L); service.get("req-1");
        assertEquals("completed", service.get("req-1").getStatus());
        assertEquals(2, receipts.size()); assertTrue(receipts.values().stream().anyMatch(v -> v.contains("unknown")));
        assertTrue(receipts.values().stream().anyMatch(v -> v.contains("completed")));
        verify(push, times(1)).submitPushMsg(any(), eq(30L), eq(11L));
    }
    @Test void originalAuthorizationEpochCannotBeReplacedDuringUnknownReconciliation() throws Exception {
        var r = request("invoke"); r.setCapabilityId("model.set"); r.setParameters(Map.of("providerId", "p", "modelId", "m")); service.submit(r);
        pastDeadline(); assertEquals("unknown", service.get("req-1").getStatus());
        when(admission.admitGroupRun(30L,20L,1L,"epoch-1")).thenReturn(GroupRunAdmissionService.Admission.builder().outcome("DENIED").build());
        var done = nativeCallback("\"status\":\"completed\",\"reconciled\":true,\"effect\":{\"kind\":\"model\",\"providerId\":\"p\",\"modelId\":\"m\",\"effective\":\"next_request\"}");
        assertEquals("unknown", service.complete(done).getStatus());
        verify(admission, times(1)).admitGroupRun(30L,20L,1L,null);
        verify(chat, times(1)).sendMsg(any(), eq(11L));
    }
    @Test void revokedAuthorizationOutcomeRereadsConcurrentAck() throws Exception {
        var r = request("invoke"); r.setCapabilityId("session.reset"); service.submit(r);
        when(admission.admitGroupRun(30L,20L,1L,"epoch-1")).thenReturn(GroupRunAdmissionService.Admission.builder().outcome("DENIED").build());
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i -> {
            String key = ((List<String>) i.getArgument(1)).get(0);
            if (first.getAndSet(false)) {
                var ack = json.readValue(storage.get(key), AgentCommandService.Pending.class);
                ack.getResult().setPhase("accepted");
                storage.put(key, json.writeValueAsString(ack)); return 0L;
            }
            return storage.replace(key, i.getArgument(2), i.getArgument(3)) ? 1L : 0L;
        }).when(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        var result = service.complete(callback());
        assertEquals("unknown", result.getStatus()); assertEquals("authorization_changed", result.getError());
        ContextUtil.setUid(11L); assertEquals("unknown", service.get("req-1").getStatus()); verifyNoInteractions(chat);
    }
    @Test void ambiguousCompactKeepsSafeDiagnosticWithoutRawNativeErrors() throws Exception {
        var r = request("invoke"); r.setCapabilityId("session.compact"); service.submit(r);
        var unknown = nativeCallback("\"status\":\"unknown\",\"error\":\"ambiguous_compaction\",\"text\":\"secret private native data\",\"generation\":\"g1\",\"nativeRef\":{\"sessionId\":\"s1\"}");
        var result = service.complete(unknown);
        assertEquals("ambiguous_compaction", result.getError());
        assertNotNull(result.getText()); assertTrue(result.getText().contains("无法确认")); assertFalse(result.getText().contains("secret"));
        verify(chat).sendMsg(argThat(msg -> msg.getBody().toString().contains("无法确认")), eq(11L));
    }
    @Test void overdueCallbackCannotResurrectUnknownOrDispatchAgain() throws Exception {
        service.submit(request("invoke"));
        String key=storage.keySet().iterator().next(); var pending=json.readValue(storage.get(key), AgentCommandService.Pending.class);
        pending.setDeadline(1); storage.put(key,json.writeValueAsString(pending));
        assertEquals("unknown",service.complete(callback()).getStatus());
        assertEquals("unknown",service.complete(callback()).getStatus());
        verify(push,times(1)).submitPushMsg(any(),eq(30L),eq(11L)); verify(chat,times(1)).sendMsg(any(),eq(11L));
    }
    @Test void revokedHumanProducesUnknownWithoutForgedFeedback() {
        service.submit(request("invoke")); human.setState(1);
        assertEquals("unknown",service.complete(callback()).getStatus()); verifyNoInteractions(chat);
    }
    @Test void authorizationEpochIsRecheckedAtCompletion() {
        service.submit(request("invoke"));
        when(admission.admitGroupRun(30L,20L,1L,"epoch-1")).thenReturn(GroupRunAdmissionService.Admission.builder().outcome("DENIED").build());
        assertEquals("authorization_changed",service.complete(callback()).getError());
    }
    @Test void losingCallbackKeepsCompetingTimeoutAndStablePrivateFeedbackReceipt() throws Exception {
        service.submit(request("invoke"));
        String key = storage.keySet().iterator().next();
        var winner = json.readValue(storage.get(key), AgentCommandService.Pending.class);
        winner.getResult().setStatus("unknown"); winner.getResult().setError("command_timeout");
        String terminal = json.writeValueAsString(winner);
        doAnswer(i -> { storage.put(key, terminal); return 0L; }).when(redis)
                .execute(any(RedisScript.class), anyList(), anyString(), anyString());
        assertEquals("unknown", service.complete(callback()).getStatus());
        assertEquals("unknown", service.complete(callback()).getStatus());
        var sent = ArgumentCaptor.forClass(ChatMessageReq.class);
        verify(chat, times(2)).sendMsg(sent.capture(), eq(11L));
        var first = sent.getAllValues().get(0); var second = sent.getAllValues().get(1);
        assertEquals(first.getBody(), second.getBody());
        assertEquals(first.getRequestId(), second.getRequestId());
        assertEquals(winner.getFeedbackReceiptId(), first.getRequestId());
        assertNotEquals("command:req-1", first.getRequestId(), "public command IDs cannot preoccupy the private receipt");
        assertTrue(first.getBody().toString().contains("unknown"));
    }
    @Test void redisOutageFailsClosedBeforeDispatch() {
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->service.submit(request("invoke"))); verifyNoInteractions(push,chat);
    }
    @Test void modelDiscoveryProjectsOnePageAndEnablesOnlySupportedCapabilityKinds() throws Exception {
        var r = request("discover"); r.setCapabilityId("model.set"); r.setParameters(Map.of("query", "org/", "offset", 100));
        assertEquals("accepted", service.submit(r).getStatus());
        var c = callback(); c.setCatalog(json.readTree("""
                {"version":"full-inventory","framework":"opencode","runtimeVersion":"1.17.9","capabilities":[
                 {"id":"model.set","kind":"model","name":"Model","supported":true,"executable":true},
                 {"id":"skill:fixture","kind":"skill","source":"skill","name":"Fixture","supported":true,"executable":true},
                 {"id":"session.reset","kind":"command","name":"Reset","supported":true,"executable":true},
                 {"id":"command:reset","kind":"command","name":"Collision","supported":true,"executable":true}],
                 "models":{"items":[{"providerId":"provider","modelId":"org/model","label":"Model","connected":false,"apiKey":"discard"}],
                   "total":8944,"offset":100,"nextOffset":101,"privatePath":"discard"}}
                """));
        var catalog = service.complete(c).getCatalog();
        assertTrue(catalog.path("capabilities").get(0).path("executable").asBoolean());
        assertTrue(catalog.path("capabilities").get(1).path("executable").asBoolean());
        assertEquals("skill", catalog.path("capabilities").get(1).path("source").asText());
        assertTrue(catalog.path("capabilities").get(2).path("executable").asBoolean());
        assertFalse(catalog.path("capabilities").get(3).path("executable").asBoolean());
        assertEquals(8944, catalog.path("models").path("total").asInt());
        assertEquals("org/model", catalog.path("models").path("items").get(0).path("modelId").asText());
        assertFalse(catalog.path("models").path("items").get(0).path("connected").asBoolean());
        assertFalse(catalog.toString().contains("discard")); verifyNoInteractions(chat);
    }
    @Test void invalidParameterTypesAndExtraKeysNeverDispatch() {
        for (Object value : Arrays.asList(null, List.of("x"), Map.of("x", "y"), 1, "x".repeat(4097), "x\u0000y")) {
            var r = request("invoke"); r.setCapabilityId("skill:fixture");
            var params = new HashMap<String,Object>(); params.put("arguments", value); r.setParameters(params);
            assertThrows(BizException.class, () -> service.submit(r));
        }
        for (Object offset : List.of(-1, 1.5, "1", 9007199254740992L)) {
            var r = request("discover"); r.setCapabilityId("model.set"); r.setParameters(Map.of("offset", offset));
            assertThrows(BizException.class, () -> service.submit(r));
        }
        var r = request("invoke"); r.setCapabilityId("session.reset"); r.setParameters(Map.of("cwd", "/untrusted"));
        assertThrows(BizException.class, () -> service.submit(r)); verifyNoInteractions(push);
    }
    @Test void modelPagesRejectOversizeAndSilentTruncationButAllowOutOfRangeEmptyPage() throws Exception {
        var r = request("discover"); r.setCapabilityId("model.set"); service.submit(r);
        var c = callback();
        var catalog = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"version":"v1","framework":"opencode","runtimeVersion":"1.17.9","capabilities":[],
                 "models":{"items":[{"providerId":"p","modelId":"m","label":"m","connected":true}],"total":8944,"offset":0}}
                """);
        c.setCatalog(catalog); assertThrows(BizException.class, () -> service.complete(c), "missing nextOffset cannot silently hide remaining inventory");
        var page = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.path("models");
        page.put("nextOffset", 101); var items = page.putArray("items");
        for (int i = 0; i < 101; i++) items.addObject().put("providerId", "p").put("modelId", "m" + i).put("label", "M").put("connected", false);
        assertThrows(BizException.class, () -> service.complete(c));
        page.remove("nextOffset"); page.put("offset", 9000); items.removeAll();
        assertEquals(0, service.complete(c).getCatalog().path("models").path("items").size());
    }
    @Test void catalogPreservesAdvisoryParametersWithoutEnablingExecution() throws Exception {
        service.submit(request("discover")); var c = callback();
        c.setCatalog(json.readTree("{\"version\":\"v1\",\"framework\":\"opencode\",\"runtimeVersion\":\"1\",\"capabilities\":[{\"id\":\"command:reset\",\"kind\":\"command\",\"name\":\"Reset\",\"supported\":true,\"executable\":true,\"parameters\":[{\"name\":\"mode\",\"type\":\"string\",\"required\":false,\"private\":\"discard\"}]}]}"));
        var entry = service.complete(c).getCatalog().path("capabilities").get(0);
        assertFalse(entry.path("executable").asBoolean());
        assertEquals(json.readTree("[{\"name\":\"mode\",\"type\":\"string\",\"required\":false}]"), entry.path("parameters"));
    }
    @Test void catalogRejectsMalformedOrUnboundedParameterMetadata() throws Exception {
        service.submit(request("discover")); var c = callback();
        var catalog = json.readTree("{\"version\":\"v1\",\"framework\":\"opencode\",\"runtimeVersion\":\"1\",\"capabilities\":[{\"id\":\"command:reset\",\"kind\":\"command\",\"name\":\"Reset\",\"parameters\":{}}]}");
        c.setCatalog(catalog);
        assertThrows(BizException.class, () -> service.complete(c));
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.path("capabilities").get(0);
        var params = entry.putArray("parameters");
        params.addObject().put("name", "x").put("type", "string").put("required", "false");
        assertThrows(BizException.class, () -> service.complete(c));
        params.removeAll();
        for (int n = 0; n < 33; n++) params.addObject().put("name", "p" + n).put("type", "string").put("required", false);
        assertThrows(BizException.class, () -> service.complete(c));
        verifyNoInteractions(chat);
    }
    @Test void discoveryWhitelistsMetadataAndCannotAdvertiseExtraExecutables() throws Exception {
        service.submit(request("discover")); var c=callback();
        c.setCatalog(json.readTree("{\"version\":\"v1\",\"framework\":\"opencode\",\"runtimeVersion\":\"1\",\"privatePath\":\"/secret\",\"capabilities\":[{\"id\":\"command:reset\",\"kind\":\"command\",\"name\":\"Reset\",\"supported\":true,\"executable\":true,\"defaultVisibility\":\"public\",\"scope\":\"workspace\",\"parameters\":[]}]}"));
        var catalog=service.complete(c).getCatalog();
        assertFalse(catalog.has("privatePath")); assertFalse(catalog.path("capabilities").get(0).path("executable").asBoolean());
        verifyNoInteractions(chat);
    }
}
