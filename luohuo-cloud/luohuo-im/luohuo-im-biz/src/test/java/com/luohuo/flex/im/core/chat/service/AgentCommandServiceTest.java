package com.luohuo.flex.im.core.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.mapper.GroupRunAdmissionMapper;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
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
import org.springframework.data.redis.core.SetOperations;
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
    private final SetOperations<String,String> sets = mock(SetOperations.class);
    private final PushService push = mock(PushService.class);
    private final ChatService chat = mock(ChatService.class);
    private final GroupMemberDao members = mock(GroupMemberDao.class);
    private final GroupRunAdmissionService admission = mock(GroupRunAdmissionService.class);
    private final RoomGroupCache groups = mock(RoomGroupCache.class);
    private final AiclawCommandPolicyService policies = mock(AiclawCommandPolicyService.class);
    private final AiclawOwnerCache ownerCache = mock(AiclawOwnerCache.class);
    private final AgentCommandService service = new AgentCommandService(facts, admission, members, groups, redis, push, chat, policies, ownerCache);
    private final Map<String,String> storage = new HashMap<>();
    private final Map<String,Long> ttl = new HashMap<>();
    private long clock = 1_000_000L;
    private Runnable beforeGrant;
    private Runnable beforeAccept;
    private boolean failObserve;
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
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.add(anyString(), anyString())).thenReturn(1L);
        when(values.get(anyString())).thenAnswer(i -> storage.get(i.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (storage.containsKey(key)) return false;
            storage.put(key, i.getArgument(1));
            ttl.put(key, clock + ((Duration) i.getArgument(2)).toMillis());
            return true;
        });
        doAnswer(i -> {
            String key = i.getArgument(0);
            storage.put(key, i.getArgument(1));
            ttl.put(key, clock + ((Duration) i.getArgument(2)).toMillis());
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            ttl.remove(key);
            return storage.remove(key) != null;
        });
        // 脚本按 Lua 文本区分：CLOCK/ACCEPT/OBSERVE/GRANT/EXPIRE/CAS；TTL 用测试时钟解码。
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(i -> redisScript(i));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenAnswer(i -> redisScript(i));
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(i -> redisScript(i));
        when(push.submitPushMsg(any(), eq(30L), eq(11L))).thenReturn(PushSubmission.resolved(
                PushSubmission.targetsOf("node", Map.of("device", 30L))));
        when(chat.sendMsg(any(), eq(11L))).thenReturn(123L);
        // aichatoverview#504: 默认放行（各用例按需覆盖具体判定）。
        lenient().when(policies.decide(any(), anyLong(), any(), any(), anyBoolean())).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.OPEN, "test-open"));
        lenient().when(policies.permissionsFor(any(), anyLong(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            Map<String, String> permissions = new LinkedHashMap<>();
            Collection<String> ids = inv.getArgument(4);
            if (ids != null) for (String id : ids) permissions.put(id, "open");
            return permissions;
        });
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
    @Test void getAndReplayDispatchOnlyReadOnlyRecoveryWithPersistedOriginalContext() throws Exception {
        human.setAccount("original-account");
        when(admission.admitGroupRun(eq(30L), eq(20L), eq(1L), any())).thenReturn(
                GroupRunAdmissionService.Admission.builder().outcome("ADMITTED").authorizationEpoch("epoch-1").workspaceDir("/trusted/workspace").build());
        var r = request("invoke"); r.setCapabilityId("skill:fixture"); r.setParameters(Map.of("arguments", "from-server"));
        service.submit(r);
        human.setAccount("changed-account");
        when(admission.admitGroupRun(30L,20L,1L,"epoch-1")).thenReturn(
                GroupRunAdmissionService.Admission.builder().outcome("ADMITTED").authorizationEpoch("epoch-1").workspaceDir("/changed/workspace").build());
        pastDeadline();
        assertEquals("unknown", service.get("req-1").getStatus());
        assertEquals("unknown", service.get("req-1").getStatus());
        assertEquals("unknown", service.submit(r).getStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, times(4)).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        var initial = json.valueToTree(sent.getAllValues().get(0));
        assertFalse(initial.path("data").has("reconcileOnly"));
        for (int i = 1; i < sent.getAllValues().size(); i++) {
            var recovered = json.valueToTree(sent.getAllValues().get(i));
            assertTrue(recovered.path("data").path("reconcileOnly").asBoolean());
            ((com.fasterxml.jackson.databind.node.ObjectNode) recovered.path("data")).remove("reconcileOnly");
            assertEquals(initial, recovered, "GET cannot rebuild context from current configuration or mint a new request");
        }
        var envelope = json.valueToTree(sent.getAllValues().get(1));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "agent-command-reconcile-envelope.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
        verify(admission, times(1)).admitGroupRun(30L,20L,1L,null);
    }
    @Test void reconciliationRejectsChangedEpochAndDoesNotDowngradeToNewAdmission() {
        var r = request("invoke"); r.setCapabilityId("session.reset"); service.submit(r);
        when(admission.admitGroupRun(30L,20L,1L,"epoch-1")).thenReturn(GroupRunAdmissionService.Admission.builder().outcome("DENIED").build());
        assertThrows(BizException.class, () -> service.get("req-1"));
        assertThrows(BizException.class, () -> service.submit(r));
        verify(push, times(1)).submitPushMsg(any(), eq(30L), eq(11L));
        verify(admission, times(1)).admitGroupRun(30L,20L,1L,null);
    }
    @Test void legacyExecutionWithoutFrozenContextNeverReconstructsOrDispatchesRecovery() throws Exception {
        var r = request("invoke"); r.setCapabilityId("session.reset"); service.submit(r);
        String key = storage.keySet().iterator().next();
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(storage.get(key));
        legacy.remove("context"); storage.put(key, json.writeValueAsString(legacy));
        assertEquals("accepted", service.get("req-1").getStatus());
        assertEquals("accepted", service.submit(r).getStatus());
        verify(push, times(1)).submitPushMsg(any(), eq(30L), eq(11L));
    }
    @Test void knownOutcomesAndReadOnlyQueriesDoNotDispatchRecovery() throws Exception {
        var r = request("invoke"); r.setCapabilityId("model.set"); r.setParameters(Map.of("providerId", "p", "modelId", "m")); service.submit(r);
        service.complete(nativeCallback("\"status\":\"completed\",\"effect\":{\"kind\":\"model\",\"providerId\":\"p\",\"modelId\":\"m\",\"effective\":\"next_request\"}"));
        ContextUtil.setUid(11L); assertEquals("completed", service.get("req-1").getStatus()); service.submit(r);
        var query = request("invoke"); query.setRequestId("query-1"); service.submit(query); service.get("query-1"); service.submit(query);
        verify(push, times(2)).submitPushMsg(any(), eq(30L), eq(11L));
        verify(push, never()).submitPushMsg(argThat(ws -> json.valueToTree(ws.getData()).path("reconcileOnly").asBoolean()), eq(30L), eq(11L));
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
        verify(push, times(2)).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("reconcileOnly").asBoolean()), eq(30L), eq(11L));
        verify(push, times(1)).submitPushMsg(argThat(ws -> json.valueToTree(ws.getData()).path("reconcileOnly").asBoolean()), eq(30L), eq(11L));
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
        String key = storage.keySet().stream().filter(k -> k.startsWith("aiclaw:command:v1:")).findFirst().orElseThrow();
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
        verify(push, times(1)).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("reconcileOnly").asBoolean()), eq(30L), eq(11L));
        verify(push, times(1)).submitPushMsg(argThat(ws -> json.valueToTree(ws.getData()).path("reconcileOnly").asBoolean()), eq(30L), eq(11L));
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
        // aichatoverview#504: 真实判定形状——command:reset 默认 closed，从目录移除（ plumbing 由 map 驱动，判定矩阵见 AiclawCommandPolicyServiceTest）。
        when(policies.permissionsFor(eq(30L), eq(0L), eq(11L), eq(false), any())).thenAnswer(inv -> {
            Map<String, String> permissions = new LinkedHashMap<>();
            for (String id : (Collection<String>) inv.getArgument(4))
                if (!id.equals("command:reset")) permissions.put(id, "open");
            return permissions;
        });
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
        // aichatoverview#504: closed 条目从目录移除（command:reset 默认 closed），其余标注 permission=open。
        assertEquals(3, catalog.path("capabilities").size());
        assertTrue(catalog.path("capabilities").get(0).path("executable").asBoolean());
        assertTrue(catalog.path("capabilities").get(1).path("executable").asBoolean());
        assertEquals("skill", catalog.path("capabilities").get(1).path("source").asText());
        assertEquals("open", catalog.path("capabilities").get(1).path("permission").asText());
        assertTrue(catalog.path("capabilities").get(2).path("executable").asBoolean());
        assertFalse(catalog.toString().contains("command:reset"));
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
        c.setCatalog(json.readTree("{\"version\":\"v1\",\"framework\":\"opencode\",\"runtimeVersion\":\"1\",\"capabilities\":[{\"id\":\"skill:advisory\",\"kind\":\"skill\",\"name\":\"Advisory\",\"source\":\"command\",\"supported\":true,\"executable\":true,\"parameters\":[{\"name\":\"mode\",\"type\":\"string\",\"required\":false,\"private\":\"discard\"}]}]}"));
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
        c.setCatalog(json.readTree("{\"version\":\"v1\",\"framework\":\"opencode\",\"runtimeVersion\":\"1\",\"privatePath\":\"/secret\",\"capabilities\":[{\"id\":\"skill:advisory\",\"kind\":\"skill\",\"name\":\"Advisory\",\"supported\":true,\"executable\":true,\"defaultVisibility\":\"public\",\"scope\":\"workspace\",\"parameters\":[]}]}"));
        var catalog=service.complete(c).getCatalog();
        assertFalse(catalog.has("privatePath")); assertFalse(catalog.path("capabilities").get(0).path("executable").asBoolean());
        verifyNoInteractions(chat);
    }
    @Test void policyClosedInvokeDeniedBeforeDispatch() {
        when(policies.decide(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.CLOSED, AiclawCommandPolicyService.DENIED_CLOSED + ":model.list"));
        var failure = assertThrows(BizException.class, () -> service.submit(request("invoke")));
        assertTrue(failure.getMessage().contains(AiclawCommandPolicyService.DENIED_CLOSED));
        verifyNoInteractions(push, chat);
        assertTrue(storage.isEmpty(), "denied invoke writes no receipt");
    }
    @Test void policyApprovalRequiredReturnsPendingForOtherOwnerAndSelfApproves() {
        when(policies.decide(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED, AiclawCommandPolicyService.DENIED_APPROVAL + "_required:model.list"));
        when(ownerCache.getOwnerUid(30L)).thenReturn(99L);
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                new AiclawCommandPolicyService.Snapshot(
                        AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED, "required"), "fp-1"));
        var pending = service.submit(request("invoke"));
        assertEquals("pending", pending.getStatus());
        assertEquals("pending", pending.getApprovalStatus());
        assertTrue(pending.getExpiresAt() >= clock + 59_000);
        verify(push, times(1)).submitPushMsg(any(), eq(99L), eq(11L));
        // 待审批只允许代次侦测信封，不得真实下发执行。
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
        // 主人自己命中的是 #504 自批快速路径，不产生待审批。
        when(ownerCache.getOwnerUid(30L)).thenReturn(11L);
        when(policies.decide(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(true))).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.OPEN, "command_policy_owner_self_approved:model.list"));
        var direct = request("invoke"); direct.setRequestId("req-owner");
        assertEquals("accepted", service.submit(direct).getStatus());
    }

    @Test void ownerApprovesFrozenRequestExactlyOnceAndUnlocksViaWsAndMessage() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        assertEquals("approved", service.approve("req-1").getApprovalStatus());
        // approve 只允许一次真实下发：第二次是幂等读。
        assertEquals("approved", service.approve("req-1").getApprovalStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, times(2)).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        var dispatch = json.valueToTree(sent.getAllValues().get(1));
        assertEquals("1", dispatch.path("data").path("context").path("expectedGeneration").asText());
        verify(chat).sendMsg(argThat(msg -> msg.getBody().toString().contains("主人已批准")), eq(11L));
    }

    @Test void ownerRejectExpiresNeverDispatchAndNewRequestNeedsNewId() throws Exception {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        asOwner();
        assertEquals("noop", service.reject("req-1").getStatus());
        assertEquals("rejected", service.reject("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
        ContextUtil.setUid(11L);
        var again = request("invoke"); again.setRequestId("req-2");
        assertEquals("pending", service.submit(again).getStatus());
        // 过期：截止键消失等价 Redis TTL 到点；GET 只做 CAS 收敛，不执行。
        expireApprovalKey("req-2");
        var expired = service.get("req-2");
        assertEquals("noop", expired.getStatus());
        assertEquals("expired", expired.getApprovalStatus());
        assertEquals("expired", service.get("req-2").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void ownerApprovesJustBeforeDeadlineAndLateTimerCannotExpireApprovedExecution() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        clock += 59_999;
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        assertEquals("approved", service.approve("req-1").getApprovalStatus());
        // 批准即解锁：执行超过原审批窗口也不得被取消。
        clock += 120_000;
        ContextUtil.setUid(11L);
        assertEquals("accepted", service.get("req-1").getStatus());
        verify(push, times(1)).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void approvalAtExactDeadlineIsExpiredWithoutDispatch() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        clock += 60_000;
        asOwner();
        var result = service.approve("req-1");
        assertEquals("expired", result.getApprovalStatus());
        assertEquals("approval_expired", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void approvalAfterDeadlineIsExpiredAndRejectCannotReviveIt() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        clock += 60_001;
        asOwner();
        var approved = service.approve("req-1");
        assertEquals("expired", approved.getApprovalStatus());
        var rejected = service.reject("req-1");
        assertEquals("expired", rejected.getApprovalStatus(), "approve/reject 竞争只有一个过期赢家");
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void slowApprovalDependencyCrossingDeadlineCannotGrant() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenAnswer(i -> {
            clock += 60_000; // 权威复核本身很慢，跨过截止点
            return approvalSnapshot("fp-1");
        });
        asOwner();
        assertEquals("expired", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void slowGrantCannotWriteApprovalPastDeadline() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        beforeGrant = () -> clock += 60_000; // 脚本执行前的排队/网络延迟跨过截止点
        asOwner();
        assertEquals("expired", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void relativeDeadlineSamplingDelayCannotExtendApprovalWindow() {
        approvalPending("fp-1");
        // 采样 expiresAt 之后、ACCEPT 真正执行之前的排队/网络延迟：截止键必须仍按原始绝对时刻死亡。
        beforeAccept = () -> clock += 5_000;
        var pending = service.submit(request("invoke"));
        assertEquals("pending", pending.getStatus());
        assertEquals(1_060_000L, pending.getExpiresAt(), "expiresAt 锚定原始 Redis 受理时刻，不被延迟推后");
        probeAck("req-1", "1");
        clock = pending.getExpiresAt();
        asOwner();
        assertEquals("expired", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void acceptExecutingAfterOriginalDeadlineIsBornExpired() {
        approvalPending("fp-1");
        beforeAccept = () -> clock += 61_000;
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        assertFalse(storage.containsKey("aiclaw:approval:deadline:v1:1:req-1"), "绝对 PEXPIREAT 已过：截止键诞生即失效");
        asOwner();
        var result = service.approve("req-1");
        assertEquals("expired", result.getApprovalStatus());
        assertEquals("approval_expired", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void probeFencesGenerationWithoutWarmCacheAndDispatchCarriesNumericValue() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        var probe = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push).submitPushMsg(probe.capture(), eq(30L), eq(11L));
        var payload = json.valueToTree(probe.getValue());
        assertTrue(payload.path("data").path("generationProbe").asBoolean());
        assertEquals("generation", payload.path("data").path("operation").asText(), "旧插件必须把侦测信封当非法请求拒绝，绝不能执行");
        assertFalse(payload.path("data").path("context").has("expectedGeneration"), "侦测信封不得让服务端猜代次");
        probeAck("req-1", "1");
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, times(2)).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        assertEquals("1", json.valueToTree(sent.getAllValues().get(1)).path("data").path("context").path("expectedGeneration").asText());
    }

    @Test void approvalWithoutTrustedProbeFailsClosedAndLateProbeCannotResurrectIt() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        asOwner();
        var result = service.approve("req-1");
        assertEquals("invalidated", result.getApprovalStatus());
        assertEquals("authorization_changed", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
        ContextUtil.setUid(99L);
        assertEquals("invalidated", probe("req-1", "1").getApprovalStatus(), "迟到的侦测回调不能复活已失效申请");
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void generationDriftDuringApprovalWindowInvalidatesBeforeDispatch() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        storage.put("aiclaw:generation:v1:1:30:20", "2"); // 等待期内另一次真实原生 reset 的回调
        asOwner();
        var result = service.approve("req-1");
        assertEquals("invalidated", result.getApprovalStatus());
        assertEquals("authorization_changed", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void authoritativeInitialGenerationZeroApprovesAndClearsStaleCache() {
        approvalPending("fp-1");
        storage.put("aiclaw:generation:v1:1:30:20", "7"); // 旧缓存的最后已知值，不是当前权威值
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "0");
        assertFalse(storage.containsKey("aiclaw:generation:v1:1:30:20"));
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, times(2)).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        assertEquals("0", json.valueToTree(sent.getAllValues().get(1)).path("data").path("context").path("expectedGeneration").asText());
    }

    /**
     * 旧代次的只读对账回放（reconciled completed）可以修复 unknown，但不得把当前代次缓存回退、
     * 也不得重复推进 epoch，从而不影响新代次请求的审批与下发。
     */
    @Test void oldTerminalReconcileCannotRegressNewerGenerationCacheOrEpoch() throws Exception {
        when(policies.decide(eq(30L), eq(0L), eq("skill:fixture"), eq(11L), eq(false))).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED, "required"));
        when(ownerCache.getOwnerUid(30L)).thenReturn(99L);
        when(policies.snapshot(eq(30L), eq(0L), eq("skill:fixture"), eq(11L), eq(false))).thenReturn(approvalSnapshot("fp-1"));

        var first = request("invoke"); first.setCapabilityId("skill:fixture"); first.setRequestId("R");
        assertEquals("pending", service.submit(first).getStatus());
        probeAck("R", "1");
        asOwner();
        assertEquals("accepted", service.approve("R").getStatus());
        pastDeadline();
        ContextUtil.setUid(11L);
        assertEquals("unknown", service.get("R").getStatus());

        // 复位已由另一条真实回调报告：当前权威代次 2、epoch 1。
        storage.put("aiclaw:generation:v1:1:30:20", "2");
        storage.put("aiclaw:generation:epoch:v1:1:30:20", "1");
        ContextUtil.setUid(11L);
        var second = request("invoke"); second.setRequestId("req-2"); second.setCapabilityId("skill:fixture");
        assertEquals("pending", service.submit(second).getStatus());
        probeAck("req-2", "2");

        // 真实 Node 组件产生的 captured 回放载荷原样送入 complete()，不事后补字段。
        var captured = json.readTree(getClass().getResourceAsStream("/aiclaw/node-replay-payload-505.json"));
        ContextUtil.setUid(30L);
        var replay = json.treeToValue(captured.get("replay"), AgentCommand.Callback.class);
        var repaired = service.complete(replay);
        assertEquals("completed", repaired.getStatus());
        assertTrue(repaired.getReconciled());
        assertEquals("2", storage.get("aiclaw:generation:v1:1:30:20"), "旧代终态回放不得回退当前缓存");
        assertEquals("1", storage.get("aiclaw:generation:epoch:v1:1:30:20"), "旧代终态回放不得推进 epoch");

        asOwner();
        assertEquals("accepted", service.approve("req-2").getStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, atLeastOnce()).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        var dispatch = sent.getAllValues().stream()
                .filter(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean())
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("2", json.valueToTree(dispatch).path("data").path("context").path("expectedGeneration").asText());
    }

    @Test void duplicateOrOutOfOrderProbeCannotOverwriteFrozenGeneration() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        // 等待期 reset 后迟到/乱序的合法探测回包：不得覆盖已冻结快照。
        var late = probe("req-1", "2");
        assertEquals("invalidated", late.getApprovalStatus());
        assertEquals("authorization_changed", late.getError());
        asOwner();
        assertEquals("invalidated", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void firstProbeLateAfterReportedResetCannotBindNewGeneration() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        var second = request("invoke"); second.setRequestId("req-2");
        assertEquals("pending", service.submit(second).getStatus());
        // req-2 的首次探测先读到 reset 后的代次 2：房间 epoch 推进。
        assertEquals("invalidated", probe("req-2", "2").getApprovalStatus());
        assertEquals("1", storage.get("aiclaw:generation:epoch:v1:1:30:20"));
        // 老 pending 的首次探测晚到，也必须失效而不是换绑到 reset 后会话。
        var late = probe("req-1", "2");
        assertEquals("invalidated", late.getApprovalStatus());
        assertEquals("authorization_changed", late.getError());
        asOwner();
        assertEquals("invalidated", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void resetObservedBeforeSubmissionStillAllowsLegitimateApproval() {
        approvalPending("fp-1");
        storage.put("aiclaw:generation:epoch:v1:1:30:20", "1");
        storage.put("aiclaw:generation:v1:1:30:20", "2"); // 受理前房间已 reset 到 2，且有可信观测
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "2");
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        var sent = ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
        verify(push, times(2)).submitPushMsg(sent.capture(), eq(30L), eq(11L));
        assertEquals("2", json.valueToTree(sent.getAllValues().get(1)).path("data").path("context").path("expectedGeneration").asText());
    }

    @Test void epochReadFailureFailsClosedAtSubmission() {
        approvalPending("fp-1");
        when(values.get(argThat((String k) -> k != null && k.startsWith("aiclaw:generation:epoch:v1:"))))
                .thenThrow(new RuntimeException("epoch store down"));
        assertThrows(BizException.class, () -> service.submit(request("invoke")));
        assertFalse(storage.containsKey("aiclaw:command:v1:1:req-1"), "不可信代次不得落 pending");
        verify(push, never()).submitPushMsg(any(), eq(30L), eq(11L));
    }

    @Test void probeObservationFailureInvalidatesWithoutDispatch() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        failObserve = true;
        var late = probe("req-1", "1");
        assertEquals("invalidated", late.getApprovalStatus());
        assertEquals("authorization_changed", late.getError());
        asOwner();
        assertEquals("invalidated", service.approve("req-1").getApprovalStatus());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void policyFingerprintChangeInvalidatesEvenWhenVerdictStaysApprovalRequired() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        asOwner();
        var result = service.approve("req-1");
        assertEquals("noop", result.getStatus());
        assertEquals("invalidated", result.getApprovalStatus());
        assertEquals("authorization_changed", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void getInvalidatesPendingImmediatelyWhenPolicyFingerprintChanged() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        ContextUtil.setUid(11L);
        var result = service.get("req-1");
        assertEquals("noop", result.getStatus());
        assertEquals("invalidated", result.getApprovalStatus());
        assertEquals("authorization_changed", result.getError());
        // 失效即解锁：GET 第二次只读终态，不重复反馈。
        assertEquals("invalidated", service.get("req-1").getApprovalStatus());
        verify(chat, times(1)).sendMsg(argThat(msg -> msg.getBody().toString().contains("审批反馈")
                && msg.getBody().toString().contains("req-1")), eq(11L));
        asOwner();
        assertTrue(service.approvals().isEmpty());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void ownerListInvalidatesPendingWhenPolicyFingerprintChanged() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        asOwner();
        assertTrue(service.approvals().isEmpty(), "失效申请不得继续占用主人的待审批列表");
        ContextUtil.setUid(11L);
        assertEquals("invalidated", service.get("req-1").getApprovalStatus());
        verify(chat, times(1)).sendMsg(argThat(msg -> msg.getBody().toString().contains("审批反馈")
                && msg.getBody().toString().contains("req-1")), eq(11L));
    }

    @Test void getAndOwnerListKeepPendingWhenPolicyAndFenceAreUnchanged() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        ContextUtil.setUid(11L);
        var result = service.get("req-1");
        assertEquals("pending", result.getStatus());
        assertNull(result.getError());
        asOwner();
        when(sets.members("aiclaw:approval:pending:1:99")).thenReturn(new LinkedHashSet<>(List.of("req-1")));
        var list = service.approvals();
        assertEquals(1, list.size());
        assertEquals("req-1", list.get(0).getRequestId());
    }

    @Test void unconfirmedGenerationProbeDoesNotInvalidatePendingOnGet() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        // 侦测回包仍在途：unknown 不是“代次变化”，不能借 GET 提前失效。
        ContextUtil.setUid(11L);
        var result = service.get("req-1");
        assertEquals("pending", result.getStatus());
        assertNull(result.getError());
        asOwner();
        when(sets.members("aiclaw:approval:pending:1:99")).thenReturn(new LinkedHashSet<>(List.of("req-1")));
        assertEquals(1, service.approvals().size());
    }

    @Test void expiredStillWinsOverPolicyChangeAfterDeadline() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        expireApprovalKey("req-1");
        ContextUtil.setUid(11L);
        var result = service.get("req-1");
        assertEquals("expired", result.getApprovalStatus());
        assertEquals("approval_expired", result.getError(), "到点由截止键 CAS 先裁决，不能改写成失效");
    }

    @Test void policyDecisionChangeToOpenOrClosedInvalidatesPendingReads() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        for (AiclawCommandPolicyService.Verdict verdict : List.of(
                AiclawCommandPolicyService.Verdict.OPEN, AiclawCommandPolicyService.Verdict.CLOSED)) {
            when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                    new AiclawCommandPolicyService.Snapshot(
                            AiclawCommandPolicyService.Decision.of(verdict, verdict.name()), "fp-1"));
            ContextUtil.setUid(11L);
            var result = service.get("req-1");
            assertEquals("invalidated", result.getApprovalStatus(), verdict + " 不再需要审批时必须失效");
            assertEquals("authorization_changed", result.getError());
        }
        verify(chat, times(1)).sendMsg(argThat(msg -> msg.getBody().toString().contains("审批反馈")), eq(11L));
    }

    @Test void pendingReadStaysPendingWhenPolicySnapshotUnavailable() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(null);
        ContextUtil.setUid(11L);
        assertEquals("pending", service.get("req-1").getStatus(), "读取失败是 unknown，不是规则变化");
    }

    @Test void invalidatedPendingReadsApproveRejectAndLateProbeAreIdempotent() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        ContextUtil.setUid(11L);
        assertEquals("invalidated", service.get("req-1").getApprovalStatus());
        asOwner();
        assertEquals("invalidated", service.approve("req-1").getApprovalStatus());
        assertEquals("invalidated", service.reject("req-1").getApprovalStatus());
        assertEquals("invalidated", probe("req-1", "2").getApprovalStatus(), "迟到的合法 probe 不能复活已失效申请");
        ContextUtil.setUid(11L);
        assertEquals("invalidated", service.get("req-1").getApprovalStatus());
        verify(chat, times(1)).sendMsg(argThat(msg -> msg.getBody().toString().contains("审批反馈")
                && msg.getBody().toString().contains("req-1")), eq(11L));
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void replayOfPendingAfterPolicyChangeReturnsInvalidatedWithoutDispatch() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        ContextUtil.setUid(11L);
        var result = service.submit(request("invoke"));
        assertEquals("invalidated", result.getApprovalStatus());
        assertEquals("authorization_changed", result.getError());
        verify(push, never()).submitPushMsg(argThat(ws -> !json.valueToTree(ws.getData()).path("generationProbe").asBoolean()),
                eq(30L), eq(11L));
    }

    @Test void approvedExecutionIsNotInvalidatedByLaterPolicyChangeOrApprovalTtl() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        probeAck("req-1", "1");
        asOwner();
        assertEquals("accepted", service.approve("req-1").getStatus());
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                approvalSnapshot("fp-2"));
        clock += 120_000;
        ContextUtil.setUid(11L);
        var result = service.get("req-1");
        assertEquals("accepted", result.getStatus(), "批准即解锁，执行态不受审批期规则复核或原审批 TTL 影响");
        assertEquals("approved", result.getApprovalStatus());
    }

    @Test void thirdPartyCannotListReadApproveOrRejectAnotherOwnersRequest() {
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        when(sets.members("aiclaw:approval:pending:1:99")).thenReturn(new LinkedHashSet<>(List.of("req-1")));
        var other = new User(); other.setId(12L); other.setTenantId(1L); other.setState(0); other.setUserType(3);
        when(facts.selectUser(12L)).thenReturn(other);
        ContextUtil.setUid(12L);
        assertThrows(BizException.class, () -> service.get("req-1"));
        assertThrows(BizException.class, () -> service.approve("req-1"));
        assertThrows(BizException.class, () -> service.reject("req-1"));
        assertTrue(service.approvals().isEmpty());
        asOwner();
        assertEquals(1, service.approvals().size());
        assertEquals("req-1", service.approvals().get(0).getRequestId());
    }

    @Test void ownerListReReadsAndDropsMissingOrTerminalEntriesWithoutHidingLaterRequests() throws Exception {
        when(sets.members("aiclaw:approval:pending:1:99")).thenReturn(new LinkedHashSet<>(List.of("gone-1", "req-1")));
        approvalPending("fp-1");
        assertEquals("pending", service.submit(request("invoke")).getStatus());
        asOwner();
        var list = service.approvals();
        assertEquals(1, list.size());
        assertEquals("req-1", list.get(0).getRequestId());
        verify(sets).remove("aiclaw:approval:pending:1:99", "gone-1");
    }

    private void asOwner() {
        ContextUtil.setUid(99L);
        when(facts.selectUser(99L)).thenReturn(owner());
    }

    private User owner() {
        User owner = new User();
        owner.setId(99L);
        owner.setTenantId(1L);
        owner.setState(0);
        owner.setUserType(3);
        return owner;
    }

    private void approvalPending(String fingerprint) {
        when(policies.decide(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED, "required"));
        when(ownerCache.getOwnerUid(30L)).thenReturn(99L);
        when(policies.snapshot(eq(30L), eq(0L), eq("model.list"), eq(11L), eq(false))).thenReturn(approvalSnapshot(fingerprint));
    }

    private AiclawCommandPolicyService.Snapshot approvalSnapshot(String fingerprint) {
        return new AiclawCommandPolicyService.Snapshot(
                AiclawCommandPolicyService.Decision.of(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED, "required"), fingerprint);
    }

    /** 模拟插件对代次侦测信封的真实回调；pending 记录只更新可信快照，不产生执行。 */
    private AgentCommand.Result probe(String requestId, String generation) {
        ContextUtil.setUid(30L);
        var c = new AgentCommand.Callback(); c.setRequestId(requestId); c.setRoomId("20");
        c.setStatus("completed"); c.setPhase("generation"); c.setGeneration(generation);
        return service.complete(c);
    }

    private void probeAck(String requestId, String generation) {
        assertEquals("pending", probe(requestId, generation).getStatus());
    }

    private void expireApprovalKey(String requestId) {
        String key = "aiclaw:approval:deadline:v1:1:" + requestId;
        storage.remove(key); ttl.remove(key);
    }

    /** 用与生产 Lua 相同的语义模拟脚本执行：绝对 PEXPIREAT 截止键、epoch 观测、CAS 截止裁决。 */
    private Object redisScript(org.mockito.invocation.InvocationOnMock i) {
        List<String> keys = i.getArgument(1);
        String script = ((RedisScript<?>) i.getArgument(0)).getScriptAsString();
        if (script.contains("redis.call('TIME')")) return clock;
        if (script.contains("PEXPIREAT")) {                                   // ACCEPT
            if (storage.containsKey(keys.get(0))) return 0L;
            if (beforeAccept != null) beforeAccept.run();
            storage.put(keys.get(0), i.getArgument(2));
            ttl.put(keys.get(0), clock + Long.parseLong(i.getArgument(3)));
            long expiresAt = Long.parseLong(i.getArgument(4));
            if (expiresAt > clock) {
                storage.put(keys.get(1), "1"); ttl.put(keys.get(1), expiresAt);
            } else {                                                          // 键按绝对时间已死：诞生即失效
                storage.remove(keys.get(1)); ttl.remove(keys.get(1));
            }
            return 1L;
        }
        if (script.contains("local known=old")) {                             // OBSERVE_MAX：终态只读对账的单调缓存
            String generation = i.getArgument(2);
            String old = storage.get(keys.get(0));
            long epoch = storage.containsKey(keys.get(1)) ? Long.parseLong(storage.get(keys.get(1))) : 0L;
            long known = old == null ? -1 : Long.parseLong(old);
            long value = Long.parseLong(generation);
            if (value > 0 && value > known) {
                storage.put(keys.get(0), generation);
                ttl.put(keys.get(0), clock + Long.parseLong(i.getArgument(3)));
                if (value >= 2) {
                    epoch += 1;
                    storage.put(keys.get(1), String.valueOf(epoch));
                    ttl.put(keys.get(1), clock + Long.parseLong(i.getArgument(3)));
                }
            }
            return epoch;
        }
        if (script.contains("tonumber(ARGV[1]) >= 2")) {                      // OBSERVE
            if (failObserve) { failObserve = false; throw new RuntimeException("observe down"); }
            String generation = i.getArgument(2);
            String old = storage.get(keys.get(0));
            long epoch = storage.containsKey(keys.get(1)) ? Long.parseLong(storage.get(keys.get(1))) : 0L;
            if ("0".equals(generation)) {
                storage.remove(keys.get(0)); ttl.remove(keys.get(0));
            } else {
                storage.put(keys.get(0), generation);
                ttl.put(keys.get(0), clock + Long.parseLong(i.getArgument(3)));
            }
            if (Long.parseLong(generation) >= 2 && (old == null || !old.equals(generation))) {
                epoch += 1;
                storage.put(keys.get(1), String.valueOf(epoch));
                ttl.put(keys.get(1), clock + Long.parseLong(i.getArgument(3)));
            }
            return epoch;
        }
        String command = keys.get(0);
        if (keys.size() == 1) return storage.replace(command, i.getArgument(2), i.getArgument(3)) ? 1L : 0L;
        if (!Objects.equals(storage.get(command), i.getArgument(2))) return 0L;
        Long recordTtl = ttl.get(command);
        if (recordTtl == null || recordTtl <= clock) return 0L;
        if (beforeGrant != null) beforeGrant.run();
        Long deadlineTtl = ttl.get(keys.get(1));
        boolean alive = deadlineTtl != null && deadlineTtl > clock;
        if (script.contains("== 0 then return -1")) {           // GRANT：截止键存活才允许转态
            if (!alive) return -1L;
            storage.put(command, i.getArgument(3));
            return 1L;
        }
        if (alive) return -1L;                                // EXPIRE：截止键消失才允许过期
        storage.put(command, i.getArgument(3));
        return 1L;
    }
}
