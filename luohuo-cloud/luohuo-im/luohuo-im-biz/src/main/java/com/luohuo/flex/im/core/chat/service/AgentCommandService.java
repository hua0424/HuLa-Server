package com.luohuo.flex.im.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Native control receipts, independent of chat/LLM turns and their busy/termination semantics. */
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
    // aichatoverview#504: 主人命令权限门控（server 公共层唯一判定点）。
    private final AiclawCommandPolicyService policies;
    private final AiclawOwnerCache ownerCache;
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
        private Map<String, Object> context;
        private long deadline;
        private AgentCommand.Result result;
        private Long feedbackMessageId;
        private String feedbackReceiptId;
        private AgentCommand.Result previousResult;
        private Long previousFeedbackMessageId;
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
        // aichatoverview#504: invoke 走主人权限门控（默认放行入口能力；名单外/关闭/他人审批在此拒绝）。
        // discover 不拦截：目录在 complete() 过滤+标注，未放行能力不下发。
        if ("invoke".equals(request.getOperation())) {
            boolean owner = Objects.equals(ownerCache.getOwnerUid(target), actor);
            long scope = room.getType() == 2 ? 0L : roomId;
            var decision = policies.decide(target, scope, request.getCapabilityId(), actor, owner);
            if (decision == null || decision.getVerdict() == AiclawCommandPolicyService.Verdict.CLOSED)
                throw new BizException("command_policy_denied:" + request.getCapabilityId());
            if (decision.getVerdict() == AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED)
                throw new BizException("command_policy_denied:approval_required:" + request.getCapabilityId());
        }
        Pending pending = new Pending(); pending.setRequest(request); pending.setActor(actor); pending.setTenant(tenant);
        pending.setEpoch(allowed.getAuthorizationEpoch()); pending.setDeadline(System.currentTimeMillis() + DEADLINE_MS);
        // A private stable receipt cannot be preoccupied through a client-chosen ordinary message ID.
        pending.setFeedbackReceiptId("command:" + UUID.randomUUID());
        AgentCommand.Result result = new AgentCommand.Result();
        result.setRequestId(request.getRequestId()); result.setRoomId(request.getRoomId());
        result.setAiclawUid(request.getAiclawUid()); result.setOperation(request.getOperation()); result.setStatus("accepted");
        result.setPhase("dispatch");
        pending.setResult(result);
        pending.setContext(frozenContext(room, human, target, actor, allowed));
        String raw = encode(pending);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, raw, RETENTION))) {
            String winner = redis.opsForValue().get(key);
            if (winner == null) throw new BizException("command_store_unavailable");
            return replay(winner, request, actor, tenant);
        }
        if ("invoke".equals(request.getOperation()) && !callable(request.getCapabilityId())) {
            return finish(raw, "unsupported", null, null, "execution_not_connected").getResult();
        }
        if (pending.getContext() == null) return finish(raw, "unknown", null, null, "authorization_changed").getResult();
        WsBaseResp<JsonNode> ws = commandMessage(pending, false);
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
        return reconcile(settle(raw));
    }

    private Map<String, Object> frozenContext(Room room, User human, Long target, Long actor,
                                               GroupRunAdmissionService.Admission allowed) {
        Map<String, Object> context = new LinkedHashMap<>(); context.put("roomType", room.getType());
        if (room.getType() == 2) {
            Aiclaw agent = facts.selectAiclaw(target);
            context.put("counterpartUid", actor.toString());
            context.put("isOwner", agent != null && Objects.equals(agent.getOwnerUid(), actor));
            if (human.getAccount() != null) context.put("account", human.getAccount());
        } else {
            RoomGroup group = groups.getByRoomIdFromDb(room.getId());
            if (group == null || group.getAccount() == null) return null;
            context.put("account", group.getAccount().toString());
        }
        if (allowed.getWorkspaceDir() != null) context.put("workspaceDir", allowed.getWorkspaceDir());
        if (allowed.getAuthorizationEpoch() != null) context.put("authorizationEpoch", allowed.getAuthorizationEpoch());
        return context;
    }

    private static WsBaseResp<JsonNode> commandMessage(Pending p, boolean reconcileOnly) {
        ObjectNode payload = JSON.valueToTree(p.getRequest()); payload.set("context", JSON.valueToTree(p.getContext()));
        if (reconcileOnly) payload.put("reconcileOnly", true); // Internal envelope only, never a public Request field.
        WsBaseResp<JsonNode> ws = new WsBaseResp<>(); ws.setType("agentCommand"); ws.setData(payload); return ws;
    }

    private AgentCommand.Result reconcile(Pending pending) {
        // A callback may have settled while feedback was delivered; never send recovery for a known outcome.
        Pending p = decode(requiredRaw(pending.getTenant(), pending.getRequest().getRequestId()));
        if (!execution(p.getRequest()) || !Set.of("accepted", "unknown").contains(p.getResult().getStatus())
                || "authorization_changed".equals(p.getResult().getError())) return p.getResult();
        authorize(p); // Recheck the original epoch, never adopt a fresh admission's epoch/context.
        if (p.getContext() == null || p.getEpoch() == null || !p.getEpoch().equals(p.getContext().get("authorizationEpoch"))) {
            log.warn("Command reconciliation unavailable: frozen context missing requestId={}", p.getRequest().getRequestId());
            return p.getResult();
        }
        try {
            push.submitPushMsg(commandMessage(p, true), id(p.getRequest().getAiclawUid()), p.getActor());
        } catch (RuntimeException failure) {
            // Recovery delivery is not execution evidence; keep the last receipt and never retry a mutation here.
            log.warn("Command reconciliation dispatch unresolved requestId={}", p.getRequest().getRequestId());
        }
        return decode(requiredRaw(p.getTenant(), p.getRequest().getRequestId())).getResult();
    }

    /** Controller first verifies gateway's authenticated agent proof. */
    public AgentCommand.Result complete(AgentCommand.Callback callback) {
        validRequestId(callback.getRequestId());
        Long tenant = ContextUtil.getTenantId(), actor = ContextUtil.getUid();
        String phase = callback.getPhase() == null ? "outcome" : callback.getPhase();
        boolean ack = "accepted".equals(phase);
        if (!(ack && "accepted".equals(callback.getStatus()) || "outcome".equals(phase)
                && Set.of("completed", "failed", "unsupported", "offline", "noop", "unknown").contains(String.valueOf(callback.getStatus()))))
            throw new BizException("invalid_command_result");
        // Bounded CAS retries preserve competing ACK/outcome updates without executing native work again.
        for (int attempt = 0; attempt < 16; attempt++) {
            String raw = requiredRaw(tenant, callback.getRequestId()); Pending p = decode(raw);
            if (!Objects.equals(actor, id(p.getRequest().getAiclawUid())) ||
                    !Objects.equals(callback.getRoomId(), p.getRequest().getRoomId())) throw new BizException("command_not_found");
            if (ack && !execution(p.getRequest())) throw new BizException("invalid_command_result");
            if (overdue(p)) {
                Pending expired = finish(raw, "unknown", null, null, "command_timeout");
                if (!execution(p.getRequest())) return expired.getResult();
                continue;
            }
            String oldStatus = p.getResult().getStatus();
            boolean uncertain = "unknown".equals(oldStatus) && execution(p.getRequest());
            boolean reconcile = uncertain && Boolean.TRUE.equals(callback.getReconciled())
                    && Set.of("completed", "failed", "noop").contains(callback.getStatus());
            if (!"accepted".equals(oldStatus) && !uncertain || "authorization_changed".equals(p.getResult().getError())) {
                feedback(p); return p.getResult();
            }
            try { authorize(p); }
            catch (BizException revoked) { return finish(raw, "unknown", null, null, "authorization_changed").getResult(); }
            // Preserve the first uncertain receipt before journal evidence fills previously missing IDs.
            AgentCommand.Result previous = uncertain ? JSON.convertValue(p.getResult(), AgentCommand.Result.class) : null;
            mergeBinding(p.getResult(), callback);
            mergeEffect(p.getRequest(), p.getResult(), callback.getEffect());
            validateEvidence(p.getRequest(), p.getResult(), callback.getStatus());
            if (uncertain && !ack && !reconcile) { feedback(decode(raw)); return decode(raw).getResult(); }
            if (ack) {
                if (!uncertain) p.getResult().setPhase("accepted"); // A lost ACK can pin IDs, never erase unknown.
            } else {
                if (reconcile) {
                    p.setPreviousResult(previous); p.setPreviousFeedbackMessageId(p.getFeedbackMessageId());
                    p.setFeedbackMessageId(null); p.getResult().setReconciled(true);
                }
                JsonNode catalog = "discover".equals(p.getRequest().getOperation()) && "completed".equals(callback.getStatus())
                        ? policyCatalog(p, callback.getCatalog()) : null;
                outcome(p, callback.getStatus(), catalog,
                        Set.of("completed", "noop").contains(callback.getStatus()) ? safeText(callback.getText(), 2000)
                                : "unknown".equals(callback.getStatus()) && "ambiguous_compaction".equals(callback.getError())
                                ? "已观察到原生压缩，但无法确认属于本次请求；不会自动重发。" : null,
                        safeError(callback.getError()));
            }
            if (save(raw, p)) { feedback(p); return p.getResult(); }
        }
        throw new BizException("command_store_busy");
    }

    private void authorize(Pending p) {
        requireHuman(p.getActor(), p.getTenant());
        long target = id(p.getRequest().getAiclawUid()), room = id(p.getRequest().getRoomId());
        requireConversation(p.getActor(), p.getTenant(), room, target);
        var allowed = admission.admitGroupRun(target, room, p.getTenant(), p.getEpoch());
        if (allowed == null || !allowed.admitted()) throw new BizException("command_forbidden");
    }
    private static boolean execution(AgentCommand.Request request) {
        return "invoke".equals(request.getOperation()) && callable(request.getCapabilityId()) && !"model.list".equals(request.getCapabilityId());
    }
    private static boolean overdue(Pending p) {
        return "accepted".equals(p.getResult().getStatus()) && System.currentTimeMillis() >= p.getDeadline()
                && !(execution(p.getRequest()) && "accepted".equals(p.getResult().getPhase()));
    }
    private boolean save(String raw, Pending p) {
        return Long.valueOf(1).equals(redis.execute(CAS, List.of(key(p.getTenant(), p.getRequest().getRequestId())), raw, encode(p)));
    }
    private static void outcome(Pending p, String status, JsonNode catalog, String text, String error) {
        AgentCommand.Result result = p.getResult();
        result.setStatus(status); result.setPhase("outcome"); result.setCatalog(catalog); result.setText(text); result.setError(error);
        result.setReceiptRevision(result.getReceiptRevision() == null ? 1 : result.getReceiptRevision() + 1);
    }
    private static String mergeId(String previous, String incoming) {
        if (incoming == null) return previous;
        opaque(incoming, 256, "invalid_command_result");
        if (previous != null && !previous.equals(incoming)) throw new BizException("command_binding_conflict");
        return incoming;
    }
    private static void mergeBinding(AgentCommand.Result result, AgentCommand.Callback callback) {
        result.setGeneration(mergeId(result.getGeneration(), callback.getGeneration()));
        AgentCommand.NativeRef supplied = callback.getNativeRef();
        if (supplied != null) {
            opaque(supplied.getSessionId(), 256, "invalid_command_result");
            AgentCommand.NativeRef ref = result.getNativeRef() == null ? new AgentCommand.NativeRef() : result.getNativeRef();
            ref.setSessionId(mergeId(ref.getSessionId(), supplied.getSessionId()));
            ref.setInputId(mergeId(ref.getInputId(), supplied.getInputId()));
            ref.setMessageId(mergeId(ref.getMessageId(), supplied.getMessageId()));
            ref.setParentId(mergeId(ref.getParentId(), supplied.getParentId()));
            ref.setTurnId(mergeId(ref.getTurnId(), supplied.getTurnId()));
            result.setNativeRef(ref);
        }
        if (result.getNativeRef() != null && result.getGeneration() == null) throw new BizException("invalid_command_result");
    }

    private static void mergeEffect(AgentCommand.Request request, AgentCommand.Result result, AgentCommand.Effect supplied) {
        if (supplied == null) return;
        String capability = request.getCapabilityId(), kind = supplied.getKind();
        if (!("model.set".equals(capability) && "model".equals(kind)
                || "session.reset".equals(capability) && "reset".equals(kind)
                || "session.compact".equals(capability) && "compact".equals(kind))) throw new BizException("invalid_command_result");
        AgentCommand.Effect previous = result.getEffect() == null ? new AgentCommand.Effect() : result.getEffect();
        AgentCommand.Effect effect = new AgentCommand.Effect(); effect.setKind(mergeId(previous.getKind(), kind));
        switch (kind) {
            case "reset" -> {
                effect.setSessionId(mergeId(previous.getSessionId(), supplied.getSessionId()));
                effect.setGeneration(mergeId(previous.getGeneration(), supplied.getGeneration()));
                opaque(effect.getSessionId(), 256, "invalid_command_result"); opaque(effect.getGeneration(), 256, "invalid_command_result");
            }
            case "compact" -> {
                effect.setControlId(mergeId(previous.getControlId(), supplied.getControlId()));
                effect.setSummaryId(mergeId(previous.getSummaryId(), supplied.getSummaryId()));
                opaque(effect.getControlId(), 256, "invalid_command_result");
                if (supplied.getSummary() == null) throw new BizException("invalid_command_result");
                effect.setSummary(supplied.getSummary());
            }
            case "model" -> {
                effect.setProviderId(mergeId(previous.getProviderId(), supplied.getProviderId()));
                effect.setModelId(mergeId(previous.getModelId(), supplied.getModelId()));
                if (!Objects.equals(request.getParameters().get("providerId"), effect.getProviderId())
                        || !Objects.equals(request.getParameters().get("modelId"), effect.getModelId())
                        || !"next_request".equals(supplied.getEffective())) throw new BizException("invalid_command_result");
                effect.setEffective("next_request");
            }
        }
        result.setEffect(effect);
    }
    private static void validateEvidence(AgentCommand.Request request, AgentCommand.Result result, String status) {
        if (!execution(request)) return;
        String capability = request.getCapabilityId();
        AgentCommand.NativeRef ref = result.getNativeRef(); AgentCommand.Effect effect = result.getEffect();
        if (skill(capability) && ref != null && ref.getInputId() != null && ref.getParentId() != null
                && !ref.getInputId().equals(ref.getParentId())) throw new BizException("command_binding_conflict");
        if (Set.of("accepted", "completed").contains(status) && (skill(capability) || "session.compact".equals(capability))) {
            if (ref == null || result.getGeneration() == null) throw new BizException("invalid_command_result");
            if (skill(capability) && ref.getInputId() == null) throw new BizException("invalid_command_result");
        }
        if (!"completed".equals(status)) return;
        if (skill(capability)) {
            if (ref.getMessageId() == null || !Objects.equals(ref.getInputId(), ref.getParentId())) throw new BizException("invalid_command_result");
        } else if ("session.compact".equals(capability)) {
            if (effect == null || !Boolean.TRUE.equals(effect.getSummary()) || effect.getSummaryId() == null)
                throw new BizException("invalid_command_result");
        } else if ("session.reset".equals(capability)) {
            if (effect == null || ref != null && Objects.equals(ref.getSessionId(), effect.getSessionId())
                    || result.getGeneration() != null && Objects.equals(result.getGeneration(), effect.getGeneration()))
                throw new BizException("invalid_command_result");
        } else if ("model.set".equals(capability) && effect == null) throw new BizException("invalid_command_result");
    }

    private AgentCommand.Result replay(String raw, AgentCommand.Request request, Long actor, Long tenant) {
        Pending old = decode(raw);
        if (!Objects.equals(old.getActor(), actor) || !Objects.equals(old.getTenant(), tenant)
                || !encode(old.getRequest()).equals(encode(request))) throw new BizException("command_request_id_conflict");
        return reconcile(settle(raw));
    }

    private Pending settle(String raw) {
        Pending p = decode(raw);
        if (overdue(p))
            return finish(raw, "unknown", null, null, "command_timeout");
        feedback(p); return p;
    }

    private Pending finish(String raw, String status, JsonNode catalog, String text, String error) {
        for (int attempt = 0; attempt < 16; attempt++) {
            Pending p = decode(raw);
            if (!"accepted".equals(p.getResult().getStatus())) { feedback(p); return p; }
            // A competing native ACK defeats a dispatch timeout, but cannot defeat revoked authorization.
            if ("command_timeout".equals(error) && !overdue(p)
                    || "accepted".equals(p.getResult().getPhase()) && Set.of("dispatch_unknown", "agent_offline").contains(String.valueOf(error))) return p;
            outcome(p, status, catalog, text, error);
            if (save(raw, p)) { feedback(p); return p; }
            raw = requiredRaw(p.getTenant(), p.getRequest().getRequestId());
        }
        throw new BizException("command_store_busy");
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
        if (!"invoke".equals(p.getRequest().getOperation())) return;
        Map<String, String> priorContext = ContextUtil.copyContext();
        Map<Integer, Long> sent = new HashMap<>();
        try {
            // At most two receipts: first outcome and its one evidence-backed reconciliation.
            for (int attempt = 0; attempt < 16; attempt++) {
                String raw = requiredRaw(p.getTenant(), p.getRequest().getRequestId()); p = decode(raw);
                if ("accepted".equals(p.getResult().getStatus())) return;
                boolean previous = p.getPreviousResult() != null && p.getPreviousFeedbackMessageId() == null;
                if (!previous && p.getFeedbackMessageId() != null) return;
                authorize(p);
                AgentCommand.Result receipt = previous ? p.getPreviousResult() : p.getResult();
                int revision = receipt.getReceiptRevision() == null ? 1 : receipt.getReceiptRevision();
                Long messageId = sent.get(revision);
                if (messageId == null) {
                    ContextUtil.remove(); ContextUtil.setUid(p.getActor()); ContextUtil.setTenantId(p.getTenant());
                    ChatMessageReq msg = new ChatMessageReq(); msg.setRoomId(id(p.getRequest().getRoomId())); msg.setMsgType(1);
                    // Preserve legacy first receipts; a reconciled outcome must never collide with unknown's SQL receipt.
                    msg.setRequestId(p.getFeedbackReceiptId() + (revision == 1 ? "" : ":" + revision));
                    msg.setCommandFeedbackRequestId(p.getRequest().getRequestId());
                    String text = "命令反馈 · " + p.getRequest().getRequestId() + " · Agent " + p.getRequest().getAiclawUid()
                            + " · " + receipt.getStatus() + "\n";
                    if (Boolean.TRUE.equals(receipt.getReconciled())) text += "先前结果未知，现已核对。\n";
                    text += receipt.getText() != null ? receipt.getText() : Objects.toString(receipt.getError(), "");
                    msg.setBody(Map.of("content", text));
                    messageId = chat.sendMsg(msg, p.getActor()); sent.put(revision, messageId);
                }
                if (previous) p.setPreviousFeedbackMessageId(messageId); else p.setFeedbackMessageId(messageId);
                save(raw, p); // CAS loss rereads both revisions; cannot overwrite a competing completion.
            }
        } catch (RuntimeException e) {
            // GET retries only receipt-backed feedback, never the native operation or revoked authorization.
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
        if (!Set.of("discover", "invoke").contains(String.valueOf(r.getOperation())))
            throw new BizException("invalid_command_request");
        String capability = r.getCapabilityId();
        if ("invoke".equals(r.getOperation())) {
            opaque(capability, 128, "invalid_command_request");
            opaque(r.getCatalogVersion(), 256, "invalid_command_request");
        } else if (r.getCatalogVersion() != null || capability != null && !"model.set".equals(capability)) {
            throw new BizException("invalid_command_request");
        }
        Map<String, Object> supplied = r.getParameters() == null ? Map.of() : r.getParameters();
        Map<String, Object> canonical = new LinkedHashMap<>();
        Set<String> keys = Set.of();
        if ("discover".equals(r.getOperation()) && "model.set".equals(capability)) {
            keys = Set.of("query", "offset");
            canonical.put("query", parameterText(supplied.getOrDefault("query", ""), 256));
            canonical.put("offset", safeInteger(supplied.getOrDefault("offset", 0), "invalid_parameters"));
        } else if ("invoke".equals(r.getOperation()) && "model.set".equals(capability)) {
            keys = Set.of("providerId", "modelId");
            canonical.put("providerId", opaque(supplied.get("providerId"), 256, "invalid_parameters"));
            canonical.put("modelId", opaque(supplied.get("modelId"), 256, "invalid_parameters"));
        } else if ("invoke".equals(r.getOperation()) && skill(capability)) {
            keys = Set.of("arguments");
            canonical.put("arguments", parameterText(supplied.getOrDefault("arguments", ""), 4096));
        }
        if (!keys.containsAll(supplied.keySet())) throw new BizException("invalid_parameters");
        r.setParameters(canonical);
    }
    private static boolean skill(String capability) {
        return capability != null && capability.startsWith("skill:") && !capability.substring(6).isBlank();
    }
    private static boolean callable(String capability) {
        return Set.of("model.list", "model.set", "session.reset", "session.compact").contains(String.valueOf(capability)) || skill(capability);
    }
    private static String opaque(Object value, int max, String error) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max || text.matches("(?s).*[\\p{Cntrl}].*"))
            throw new BizException(error);
        return text;
    }
    private static String parameterText(Object value, int max) {
        if (!(value instanceof String text) || text.length() > max || text.matches("(?s).*[\\p{Cntrl}&&[^\\r\\n\\t]].*"))
            throw new BizException("invalid_parameters");
        return text;
    }
    private static long safeInteger(Object value, String error) {
        if (!(value instanceof Number number)) throw new BizException(error);
        try {
            long result = new java.math.BigDecimal(number.toString()).longValueExact();
            if (result >= 0 && result <= 9_007_199_254_740_991L) return result;
        } catch (ArithmeticException | NumberFormatException ignored) { }
        throw new BizException(error);
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
        return Set.of("catalog_stale", "invalid_parameters", "native_timeout", "native_request_failed", "command_busy", "ambiguous_compaction",
                "discovery_not_supported", "execution_not_supported", "execution_not_connected").contains(value) ? value : "native_request_failed";
    }
    private static JsonNode catalog(JsonNode input) {
        if (input == null || !input.isObject() || !input.path("capabilities").isArray()
                || input.path("capabilities").size() > 256) throw new BizException("invalid_command_catalog");
        ObjectNode out = JSON.createObjectNode();
        for (String field : List.of("version", "framework", "runtimeVersion")) {
            if (!input.path(field).isTextual() || input.path(field).asText().isBlank() || input.path(field).asText().length() > 256)
                throw new BizException("invalid_command_catalog");
            out.put(field, opaque(input.path(field).asText(), 256, "invalid_command_catalog"));
        }
        var entries = out.putArray("capabilities"); Set<String> ids = new HashSet<>();
        for (JsonNode c : input.path("capabilities")) {
            if (!c.path("id").isTextual() || c.path("id").asText().isBlank() || c.path("id").asText().length() > 128
                    || !ids.add(c.path("id").asText()) || !Set.of("model", "skill", "command").contains(c.path("kind").asText())
                    || !c.path("name").isTextual()) throw new BizException("invalid_command_catalog");
            ObjectNode entry = entries.addObject();
            String capability = opaque(c.path("id").asText(), 128, "invalid_command_catalog");
            entry.put("id", capability);
            for (String field : List.of("kind", "name", "description", "reason"))
                if (c.path(field).isTextual()) entry.put(field, safeText(c.path(field).asText(), field.equals("description") ? 512 : 128));
            if (c.has("source")) {
                if (!c.path("source").isTextual() || !c.path("source").asText().matches("[A-Za-z0-9._:-]{1,64}"))
                    throw new BizException("invalid_command_catalog");
                entry.put("source", c.path("source").asText());
            }
            String kind = c.path("kind").asText();
            boolean executable = "model".equals(kind) && Set.of("model.list", "model.set").contains(capability)
                    || "command".equals(kind) && Set.of("session.reset", "session.compact").contains(capability)
                    || "skill".equals(kind) && skill(capability) && "skill".equals(c.path("source").asText());
            entry.put("supported", c.path("supported").asBoolean(false));
            entry.put("executable", c.path("supported").asBoolean(false) && c.path("executable").asBoolean(false) && executable);
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
            } // Parameters are metadata; execution is validated against the operation's fixed contract.
        }
        if (input.has("models")) {
            JsonNode supplied = input.path("models");
            if (!supplied.isObject() || !supplied.path("items").isArray() || supplied.path("items").size() > 100)
                throw new BizException("invalid_command_catalog");
            ObjectNode page = out.putObject("models");
            long total = catalogInteger(supplied.path("total")), offset = catalogInteger(supplied.path("offset"));
            page.put("total", total); page.put("offset", offset);
            if (supplied.path("items").size() > Math.max(0, total - offset)) throw new BizException("invalid_command_catalog");
            if (offset + supplied.path("items").size() < total && !supplied.has("nextOffset"))
                throw new BizException("invalid_command_catalog");
            if (supplied.has("nextOffset")) {
                long next = catalogInteger(supplied.path("nextOffset"));
                if (next <= offset || next >= total || next != offset + supplied.path("items").size())
                    throw new BizException("invalid_command_catalog");
                page.put("nextOffset", next);
            }
            var items = page.putArray("items"); Set<List<String>> models = new HashSet<>();
            for (JsonNode model : supplied.path("items")) {
                if (!model.isObject() || !model.path("providerId").isTextual() || !model.path("modelId").isTextual()
                        || !model.path("label").isTextual() || model.path("label").asText().length() > 256
                        || !model.path("connected").isBoolean()) throw new BizException("invalid_command_catalog");
                String provider = opaque(model.path("providerId").asText(), 256, "invalid_command_catalog");
                String id = opaque(model.path("modelId").asText(), 256, "invalid_command_catalog");
                if (!models.add(List.of(provider, id))) throw new BizException("invalid_command_catalog");
                items.addObject().put("providerId", provider).put("modelId", id)
                        .put("label", safeText(model.path("label").asText(), 256)).put("connected", model.path("connected").asBoolean());
            }
        }
        return out;
    }
    /**
     * aichatoverview#504: 目录过滤+标注。先走 #503 消毒，再按主人权限：
     * closed 条目移除（菜单不可见）；open/approval_required 标注 permission（菜单渲染与审批标记用）。
     */
    private JsonNode policyCatalog(Pending p, JsonNode nodeCatalog) {
        JsonNode sanitized = catalog(nodeCatalog);
        if (!(sanitized instanceof ObjectNode out) || !sanitized.path("capabilities").isArray()) return sanitized;
        Long target = id(p.getRequest().getAiclawUid());
        long roomId = id(p.getRequest().getRoomId());
        Object roomType = p.getContext() == null ? null : p.getContext().get("roomType");
        long scope = roomType instanceof Number type && type.intValue() == 2 ? 0L : roomId;
        boolean owner = Objects.equals(ownerCache.getOwnerUid(target), p.getActor());
        List<String> ids = new ArrayList<>();
        for (JsonNode c : sanitized.path("capabilities")) ids.add(c.path("id").asText());
        Map<String, String> permissions = policies.permissionsFor(target, scope, p.getActor(), owner, ids);
        var kept = JSON.createArrayNode();
        for (JsonNode c : sanitized.path("capabilities")) {
            String permission = permissions.get(c.path("id").asText());
            if (permission == null) continue; // closed → 目录过滤移除。
            ((ObjectNode) c).put("permission", permission);
            kept.add(c);
        }
        out.set("capabilities", kept);
        return out;
    }
    private static long catalogInteger(JsonNode node) {
        if (!node.isNumber()) throw new BizException("invalid_command_catalog");
        return safeInteger(node.numberValue(), "invalid_command_catalog");
    }
    @PreDestroy public void close() { deadlines.shutdownNow(); }
}
