package com.luohuo.flex.im.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.luohuo.flex.model.entity.WsBaseResp;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** #502: bounded read-only native commands, independent of chat/LLM turns. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCommandService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final long DEADLINE_MS = 20_000;
    private static final DefaultRedisScript<Long> CAS = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end " +
            "local ttl=redis.call('PTTL',KEYS[1]); if ttl<=0 then return 0 end " +
            "redis.call('SET',KEYS[1],ARGV[2],'PX',ttl); return 1", Long.class);
    private final GroupRunAdmissionMapper facts;
    private final GroupRunAdmissionService admission;
    private final GroupMemberDao members;
    private final RoomGroupCache groups;
    private final StringRedisTemplate redis;
    private final PushService push;
    private final ChatService chat;
    // ponytail: one short-lived timer per accepted request; Redis remains authoritative after restart.
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-command-deadline"); t.setDaemon(true); return t;
    });

    @Data
    public static class Pending {
        private AgentCommand.Request request;
        private Long actor;
        private Long tenant;
        private String epoch;
        private long deadline;
        private AgentCommand.Result result;
        private Long feedbackMessageId;
        private String feedbackReceiptId;
    }

    public AgentCommand.Result submit(AgentCommand.Request request) {
        Long actor = ContextUtil.getUid(), tenant = ContextUtil.getTenantId();
        User human = requireHuman(actor, tenant);
        validate(request);
        long roomId = id(request.getRoomId()), target = id(request.getAiclawUid());
        Room room = requireConversation(actor, tenant, roomId, target);
        String key = key(tenant, request.getRequestId());
        String previous = redis.opsForValue().get(key);
        if (previous != null) return replay(previous, request, actor, tenant);
        var allowed = admission.admitGroupRun(target, roomId, tenant, null);
        if (allowed == null || !allowed.admitted()) throw new BizException("command_forbidden");
        Pending pending = new Pending(); pending.setRequest(request); pending.setActor(actor); pending.setTenant(tenant);
        pending.setEpoch(allowed.getAuthorizationEpoch()); pending.setDeadline(System.currentTimeMillis() + DEADLINE_MS);
        // A private stable receipt cannot be preoccupied through a client-chosen ordinary message ID.
        pending.setFeedbackReceiptId("command:" + UUID.randomUUID());
        AgentCommand.Result result = new AgentCommand.Result();
        result.setRequestId(request.getRequestId()); result.setRoomId(request.getRoomId());
        result.setAiclawUid(request.getAiclawUid()); result.setOperation(request.getOperation()); result.setStatus("accepted");
        pending.setResult(result);
        String raw = encode(pending);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, raw, RETENTION))) {
            String winner = redis.opsForValue().get(key);
            if (winner == null) throw new BizException("command_store_unavailable");
            return replay(winner, request, actor, tenant);
        }
        if ("invoke".equals(request.getOperation()) && !"model.list".equals(request.getCapabilityId())) {
            return finish(raw, "unsupported", null, null, "execution_not_connected").getResult();
        }
        Map<String, Object> context = new LinkedHashMap<>(); context.put("roomType", room.getType());
        if (room.getType() == 2) {
            Aiclaw agent = facts.selectAiclaw(target);
            context.put("counterpartUid", actor.toString());
            context.put("isOwner", agent != null && Objects.equals(agent.getOwnerUid(), actor));
            if (human.getAccount() != null) context.put("account", human.getAccount());
        } else {
            RoomGroup group = groups.getByRoomIdFromDb(roomId);
            if (group == null || group.getAccount() == null)
                return finish(raw, "unknown", null, null, "authorization_changed").getResult();
            context.put("account", group.getAccount().toString());
        }
        if (allowed.getWorkspaceDir() != null) context.put("workspaceDir", allowed.getWorkspaceDir());
        ObjectNode payload = JSON.valueToTree(request); payload.set("context", JSON.valueToTree(context));
        WsBaseResp<JsonNode> ws = new WsBaseResp<>(); ws.setType("agentCommand"); ws.setData(payload);
        try {
            PushSubmission submitted = push.submitPushMsg(ws, target, actor);
            if (submitted == null || submitted.getRouteStatus() == PushSubmission.RouteStatus.FAILED)
                return finish(raw, "unknown", null, null, "dispatch_unknown").getResult();
            if (submitted.getRouteStatus() == PushSubmission.RouteStatus.NO_TARGET)
                return finish(raw, "offline", null, null, "agent_offline").getResult();
            if (submitted.getAdmission() == PushSubmission.Admission.NONE)
                return finish(raw, "unknown", null, null, "dispatch_unknown").getResult();
        } catch (RuntimeException failure) {
            // A throwing transport can have submitted already. Never resend a native command.
            return finish(raw, "unknown", null, null, "dispatch_unknown").getResult();
        }
        deadlines.schedule(() -> expire(tenant, request.getRequestId()), DEADLINE_MS, TimeUnit.MILLISECONDS);
        return result; // Submission is not delivery, much less native completion.
    }

    public AgentCommand.Result get(String requestId) {
        Long actor = ContextUtil.getUid(), tenant = ContextUtil.getTenantId();
        requireHuman(actor, tenant); validRequestId(requestId);
        String raw = requiredRaw(tenant, requestId); Pending p = decode(raw);
        if (!Objects.equals(p.getActor(), actor)) throw new BizException("command_not_found");
        requireConversation(actor, tenant, id(p.getRequest().getRoomId()), id(p.getRequest().getAiclawUid()));
        return settle(raw).getResult();
    }

    /** Controller first verifies gateway's authenticated agent proof. */
    public AgentCommand.Result complete(AgentCommand.Callback callback) {
        validRequestId(callback.getRequestId());
        Long tenant = ContextUtil.getTenantId(), actor = ContextUtil.getUid();
        String raw = requiredRaw(tenant, callback.getRequestId()); Pending p = decode(raw);
        if (!Objects.equals(actor, id(p.getRequest().getAiclawUid())) ||
                !Objects.equals(callback.getRoomId(), p.getRequest().getRoomId())) throw new BizException("command_not_found");
        if (!Set.of("completed", "failed", "unsupported", "unknown").contains(String.valueOf(callback.getStatus())))
            throw new BizException("invalid_command_result");
        if (!"accepted".equals(p.getResult().getStatus())) { feedback(p); return p.getResult(); }
        if (System.currentTimeMillis() >= p.getDeadline()) return finish(raw, "unknown", null, null, "command_timeout").getResult();
        try {
            requireHuman(p.getActor(), tenant);
            requireConversation(p.getActor(), tenant, id(p.getRequest().getRoomId()), actor);
            var allowed = admission.admitGroupRun(actor, id(p.getRequest().getRoomId()), tenant, p.getEpoch());
            if (allowed == null || !allowed.admitted()) return finish(raw, "unknown", null, null, "authorization_changed").getResult();
        } catch (BizException revoked) {
            return finish(raw, "unknown", null, null, "authorization_changed").getResult();
        }
        JsonNode catalog = null;
        if ("discover".equals(p.getRequest().getOperation()) && "completed".equals(callback.getStatus()))
            catalog = catalog(callback.getCatalog());
        String text = "completed".equals(callback.getStatus()) ? safeText(callback.getText(), 2000) : null;
        return finish(raw, callback.getStatus(), catalog, text, safeError(callback.getError())).getResult();
    }

    private AgentCommand.Result replay(String raw, AgentCommand.Request request, Long actor, Long tenant) {
        Pending old = decode(raw);
        if (!Objects.equals(old.getActor(), actor) || !Objects.equals(old.getTenant(), tenant)
                || !encode(old.getRequest()).equals(encode(request))) throw new BizException("command_request_id_conflict");
        return settle(raw).getResult();
    }

    private Pending settle(String raw) {
        Pending p = decode(raw);
        if ("accepted".equals(p.getResult().getStatus()) && System.currentTimeMillis() >= p.getDeadline())
            return finish(raw, "unknown", null, null, "command_timeout");
        feedback(p); return p;
    }

    private Pending finish(String raw, String status, JsonNode catalog, String text, String error) {
        Pending p = decode(raw);
        if (!"accepted".equals(p.getResult().getStatus())) return p;
        p.getResult().setStatus(status); p.getResult().setCatalog(catalog); p.getResult().setText(text); p.getResult().setError(error);
        if (!Long.valueOf(1).equals(redis.execute(CAS, List.of(key(p.getTenant(), p.getRequest().getRequestId())), raw, encode(p))))
            p = decode(requiredRaw(p.getTenant(), p.getRequest().getRequestId()));
        feedback(p); return p;
    }

    private void expire(Long tenant, String requestId) {
        try {
            ContextUtil.setTenantId(tenant);
            String raw = redis.opsForValue().get(key(tenant, requestId));
            if (raw != null) settle(raw);
        } catch (RuntimeException e) {
            log.warn("Command deadline unresolved; authenticated GET will reconcile requestId={}", requestId);
        } finally { ContextUtil.remove(); ContextUtil.clearTenantContext(); }
    }

    private void feedback(Pending p) {
        if (!"invoke".equals(p.getRequest().getOperation()) || "accepted".equals(p.getResult().getStatus())
                || p.getFeedbackMessageId() != null) return;
        Map<String, String> priorContext = ContextUtil.copyContext();
        try {
            requireHuman(p.getActor(), p.getTenant());
            requireConversation(p.getActor(), p.getTenant(), id(p.getRequest().getRoomId()), id(p.getRequest().getAiclawUid()));
            ContextUtil.remove(); ContextUtil.setUid(p.getActor()); ContextUtil.setTenantId(p.getTenant());
            ChatMessageReq msg = new ChatMessageReq(); msg.setRoomId(id(p.getRequest().getRoomId())); msg.setMsgType(1);
            // Existing SQL message receipt makes feedback replay converge across Redis/SQL crashes.
            msg.setRequestId(p.getFeedbackReceiptId());
            msg.setCommandFeedbackRequestId(p.getRequest().getRequestId());
            String text = "命令反馈 · " + p.getRequest().getRequestId() + " · Agent " + p.getRequest().getAiclawUid()
                    + " · " + p.getResult().getStatus() + "\n";
            text += p.getResult().getText() != null ? p.getResult().getText() : Objects.toString(p.getResult().getError(), "");
            msg.setBody(Map.of("content", text));
            String before = encode(p); p.setFeedbackMessageId(chat.sendMsg(msg, p.getActor()));
            redis.execute(CAS, List.of(key(p.getTenant(), p.getRequest().getRequestId())), before, encode(p));
        } catch (RuntimeException e) {
            // Never impersonate a revoked human or retry the native operation. GET can retry only the receipt-backed feedback.
            log.warn("Command feedback pending or no longer authorized requestId={}", p.getRequest().getRequestId());
        } finally {
            ContextUtil.restoreContext(priorContext);
        }
    }

    private Room requireConversation(Long actor, Long tenant, Long roomId, Long target) {
        Room room = facts.selectRoom(roomId, tenant);
        if (room == null || Boolean.TRUE.equals(room.getIsDel()) || !Set.of(1, 2).contains(room.getType()))
            throw new BizException("command_forbidden");
        if (room.getType() == 2) {
            RoomFriend friend = facts.selectFriendMembership(actor, roomId, tenant);
            if (friend == null || Boolean.TRUE.equals(friend.getDeFriend1()) || Boolean.TRUE.equals(friend.getDeFriend2())
                    || !(Objects.equals(friend.getUid1(), actor) && Objects.equals(friend.getUid2(), target)
                    || Objects.equals(friend.getUid2(), actor) && Objects.equals(friend.getUid1(), target)))
                throw new BizException("command_forbidden");
        } else {
            if (facts.selectMembership(actor, roomId, tenant) == null) throw new BizException("command_forbidden");
            GroupMember member = members.getMember(roomId, actor);
            if (member == null || Boolean.TRUE.equals(member.getDeFriend())) throw new BizException("command_forbidden");
        }
        return room;
    }

    private User requireHuman(Long actor, Long tenant) {
        if (actor == null || actor <= 0 || tenant == null || tenant <= 0) throw new BizException("command_forbidden");
        User user = facts.selectUser(actor);
        if (user == null || !Objects.equals(user.getTenantId(), tenant) || !Objects.equals(user.getUserType(), 3)
                || !Objects.equals(user.getState(), 0) || Boolean.TRUE.equals(user.getIsDel())) throw new BizException("command_forbidden");
        return user;
    }

    private static void validate(AgentCommand.Request r) {
        if (r == null) throw new BizException("invalid_command_request");
        validRequestId(r.getRequestId()); id(r.getRoomId()); id(r.getAiclawUid());
        if (!Set.of("discover", "invoke").contains(String.valueOf(r.getOperation()))
                || r.getParameters() != null && !r.getParameters().isEmpty()) throw new BizException("invalid_command_request");
        r.setParameters(Map.of());
        if ("invoke".equals(r.getOperation()) && (r.getCapabilityId() == null || r.getCapabilityId().length() > 128
                || r.getCapabilityId().isBlank() || r.getCatalogVersion() == null || r.getCatalogVersion().isBlank()
                || r.getCatalogVersion().length() > 256)) throw new BizException("invalid_command_request");
        if ("discover".equals(r.getOperation()) && (r.getCapabilityId() != null || r.getCatalogVersion() != null))
            throw new BizException("invalid_command_request");
    }
    private static void validRequestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,100}")) throw new BizException("invalid_command_request_id");
    }
    private static long id(String value) {
        try { if (value != null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value); }
        catch (NumberFormatException ignored) { }
        throw new BizException("invalid_command_id");
    }
    private static String key(Long tenant, String requestId) { return "aiclaw:command:v1:" + tenant + ":" + requestId; }
    private String requiredRaw(Long tenant, String requestId) {
        if (tenant == null || tenant <= 0) throw new BizException("command_forbidden");
        String raw = redis.opsForValue().get(key(tenant, requestId));
        if (raw == null) throw new BizException("command_not_found");
        return raw;
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new BizException("command_store_invalid"); }
    }
    private static Pending decode(String raw) {
        try { return JSON.readValue(raw, Pending.class); } catch (Exception e) { throw new BizException("command_store_invalid"); }
    }
    private static String safeText(String text, int max) {
        if (text == null) return null;
        String clean = text.substring(0, Math.min(text.length(), max * 2))
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("(?i)https?://\\S+", "[link omitted]")
                .replaceAll("<[^>]*>", "");
        return clean.substring(0, Math.min(max, clean.length()));
    }
    private static String safeError(String value) {
        if (value == null) return null;
        return Set.of("catalog_stale", "invalid_parameters", "native_timeout", "native_request_failed", "command_busy",
                "discovery_not_supported", "execution_not_supported", "execution_not_connected").contains(value) ? value : "native_request_failed";
    }
    private static JsonNode catalog(JsonNode input) {
        if (input == null || !input.isObject() || !input.path("capabilities").isArray()
                || input.path("capabilities").size() > 256) throw new BizException("invalid_command_catalog");
        ObjectNode out = JSON.createObjectNode();
        for (String field : List.of("version", "framework", "runtimeVersion")) {
            if (!input.path(field).isTextual() || input.path(field).asText().isBlank() || input.path(field).asText().length() > 256)
                throw new BizException("invalid_command_catalog");
            out.put(field, safeText(input.path(field).asText(), 256));
        }
        var entries = out.putArray("capabilities"); Set<String> ids = new HashSet<>();
        for (JsonNode c : input.path("capabilities")) {
            if (!c.path("id").isTextual() || c.path("id").asText().isBlank() || c.path("id").asText().length() > 128
                    || !ids.add(c.path("id").asText()) || !Set.of("model", "skill", "command").contains(c.path("kind").asText())
                    || !c.path("name").isTextual()) throw new BizException("invalid_command_catalog");
            ObjectNode entry = entries.addObject();
            for (String field : List.of("id", "kind", "name", "description", "reason"))
                if (c.path(field).isTextual()) entry.put(field, safeText(c.path(field).asText(), field.equals("description") ? 512 : 128));
            entry.put("supported", c.path("supported").asBoolean(false));
            entry.put("executable", c.path("supported").asBoolean(false) && c.path("executable").asBoolean(false)
                    && "model.list".equals(c.path("id").asText()) && "model".equals(c.path("kind").asText()));
            entry.put("defaultVisibility", "skill".equals(c.path("kind").asText()) || "public".equals(c.path("defaultVisibility").asText()) ? "public" : "hidden");
            entry.put("scope", Set.of("workspace", "conversation", "instance").contains(c.path("scope").asText()) ? c.path("scope").asText() : "workspace");
            var parameters = entry.putArray("parameters");
            JsonNode supplied = c.path("parameters");
            if (!supplied.isMissingNode() && (!supplied.isArray() || supplied.size() > 32))
                throw new BizException("invalid_command_catalog");
            Set<String> names = new HashSet<>();
            for (JsonNode parameter : supplied) {
                if (!parameter.isObject() || !parameter.path("name").isTextual()
                        || parameter.path("name").asText().isBlank() || parameter.path("name").asText().length() > 128
                        || !names.add(parameter.path("name").asText()) || !parameter.path("type").isTextual()
                        || parameter.path("type").asText().isBlank() || parameter.path("type").asText().length() > 32
                        || !parameter.path("required").isBoolean()) throw new BizException("invalid_command_catalog");
                ObjectNode metadata = parameters.addObject();
                metadata.put("name", safeText(parameter.path("name").asText(), 128));
                metadata.put("type", safeText(parameter.path("type").asText(), 32));
                metadata.put("required", parameter.path("required").asBoolean());
            } // Advisory catalog metadata only; invoke still rejects all nonempty parameter maps.
        }
        return out;
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}
