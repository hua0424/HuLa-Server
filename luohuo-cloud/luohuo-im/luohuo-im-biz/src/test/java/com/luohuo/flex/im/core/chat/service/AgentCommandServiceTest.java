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
                json.valueToTree(ws.getData()).path("context").path("counterpartUid").asText().equals("11")), eq(30L), eq(11L));
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
        r.setParameters(Map.of()); r.setCapabilityId("skill:arbitrary");
        assertEquals("unsupported", service.submit(r).getStatus()); verifyNoInteractions(push);
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
