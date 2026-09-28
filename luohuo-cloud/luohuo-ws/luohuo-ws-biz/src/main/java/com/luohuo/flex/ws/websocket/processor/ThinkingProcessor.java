package com.luohuo.flex.ws.websocket.processor;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextConstants;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.WSBaseReq;
import com.luohuo.flex.ws.service.AiclawRateLimitChecker;
import com.luohuo.flex.ws.service.PushService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.scheduler.Schedulers;
import com.luohuo.flex.ws.ReactiveContextUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated WS thinking messages, persisted by IM before notification. */
@Slf4j
@Order(20)
@Component
public class ThinkingProcessor implements MessageProcessor {
	private static final String SERVICE_AUTH = "X-Thinking-Service-Auth";
	private static final String ACTOR = "X-Thinking-Actor-Uid";
	private static final String ACTOR_TYPE = "X-Thinking-Actor-Type";
	private static final String SERVICE_TIMEOUT = "X-Thinking-Service-Timeout";

	@Resource private PushService pushService;
	@Resource private DiscoveryClient discoveryClient;
	@Resource private AiclawRateLimitChecker rateLimitChecker;
	@Resource private AiclawProperties aiclawProperties;
	@Value("${THINKING_INTERNAL_SECRET}") private String internalSecret;

	private final ConcurrentHashMap<String, ThinkingContext> activeThinkings = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingFrames = new ConcurrentHashMap<>();
	// ponytail: global 128-frame bound; split admission per actor if measured throughput needs it.
	private final AtomicInteger pendingFrameCount = new AtomicInteger();
	private final Executor thinkingExecutor = task -> Schedulers.boundedElastic().schedule(task);

	@Override
	public boolean supports(WSBaseReq req) {
		return WSReqTypeEnum.THINKING_START.eq(req.getType()) || WSReqTypeEnum.THINKING_END.eq(req.getType());
	}

	/** Returns tenant only for a gateway-authenticated AICLAW session bound to this uid. */
	public Long trustedTenant(WebSocketSession session, Long uid) {
		if (session == null || session.getHandshakeInfo() == null) return null;
		Long actor = positive(session.getHandshakeInfo().getHeaders().getFirst(ACTOR));
		Long tenant = positive(session.getHandshakeInfo().getHeaders().getFirst(ContextConstants.HEADER_TENANT_ID));
		String proof = session.getHandshakeInfo().getHeaders().getFirst(SERVICE_AUTH);
		if (internalSecret == null || internalSecret.isBlank() || proof == null ||
				!MessageDigest.isEqual(internalSecret.getBytes(StandardCharsets.UTF_8), proof.getBytes(StandardCharsets.UTF_8)) ||
				!"AICLAW".equals(session.getHandshakeInfo().getHeaders().getFirst(ACTOR_TYPE)) ||
				actor == null || tenant == null || !actor.equals(uid)) return null;
		return tenant;
	}

	@Override
	public void process(WebSocketSession session, Long uid, WSBaseReq payload) {
		// Capture from the authenticated handshake, not a frame or a thread-local on an async callback.
		Long actor = uid;
		Long tenant = trustedTenant(session, uid);
		if (tenant == null) {
			log.warn("thinking rejected: untrusted WS session");
			return;
		}
		WSReqTypeEnum type = WSReqTypeEnum.of(payload.getType());
		Runnable work;
		if (type == WSReqTypeEnum.THINKING_START) {
			work = () -> handleStart(actor, tenant, payload);
		} else if (type == WSReqTypeEnum.THINKING_END) {
			WSThinkingEnd end;
			try {
				end = JSONUtil.toBean(payload.getData(), WSThinkingEnd.class);
			} catch (RuntimeException e) {
				log.warn("thinking END rejected: malformed payload, actor={}", actor);
				return;
			}
			if (end == null || positive(end.getThinkingId()) == null || positive(end.getRoomId()) == null) {
				log.warn("thinking END rejected: explicit thinkingId and roomId required, actor={}", actor);
				if (end != null && end.getClientRunId() != null) pushRejected(actor, end.getRoomId(),
						end.getThinkingId(), end.getClientRunId(), "thinking_end_rejected");
				return;
			}
			work = () -> handleEnd(actor, tenant, end);
		} else {
			return;
		}
		if (pendingFrameCount.incrementAndGet() > 128) {
			pendingFrameCount.decrementAndGet();
			log.warn("thinking queue full, closing WS actor={}", actor);
			session.close(new CloseStatus(1013, "thinking overloaded; reconnect")).subscribe();
			return;
		}
		CompletableFuture<Void> queued;
		try {
			queued = pendingFrames.compute(actor, (key, previous) -> {
				CompletableFuture<Void> prior = previous == null ? CompletableFuture.completedFuture(null) : previous;
				return prior.handle((ignored, error) -> null).thenRunAsync(() -> {
					ReactiveContextUtil.setTenantId(tenant);
					ReactiveContextUtil.setUid(actor);
					try {
						work.run();
					} catch (RuntimeException e) {
						log.warn("thinking rejected: actor={}, kind={}", actor, e.getClass().getSimpleName());
						if (type == WSReqTypeEnum.THINKING_START) {
							try {
								WSThinkingStart start = JSONUtil.toBean(payload.getData(), WSThinkingStart.class);
								pushError(actor, start == null ? null : start.getRoomId(), null,
										start == null ? null : start.getClientRunId(), "thinking_start_failed");
							} catch (RuntimeException ignored) { pushError(actor, null, null, null, "thinking_start_invalid"); }
						} else {
							try {
								WSThinkingEnd end = JSONUtil.toBean(payload.getData(), WSThinkingEnd.class);
								pushRejected(actor, end == null ? null : end.getRoomId(),
										end == null ? null : end.getThinkingId(), end == null ? null : end.getClientRunId(),
										"thinking_end_rejected");
							} catch (RuntimeException ignored) { pushRejected(actor, null, null, null, "thinking_end_rejected"); }
						}
					} finally {
						ReactiveContextUtil.remove();
					}
				}, thinkingExecutor);
			});
		} catch (RuntimeException e) {
			pendingFrameCount.decrementAndGet();
			session.close(new CloseStatus(1013, "thinking unavailable; reconnect")).subscribe();
			return;
		}
		queued.whenComplete((ignored, error) -> {
			pendingFrames.remove(actor, queued);
			pendingFrameCount.decrementAndGet();
			if (error != null) log.warn("thinking queue failed: actor={}, kind={}", actor, error.getClass().getSimpleName());
		});
	}

	private Long positive(String value) {
		try {
			Long id = Long.valueOf(value);
			return id > 0 ? id : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void handleStart(Long actor, Long tenant, WSBaseReq payload) {
		WSThinkingStart req = JSONUtil.toBean(payload.getData(), WSThinkingStart.class);
		Long room = req == null ? null : positive(req.getRoomId());
		if (room == null) {
			pushError(actor, req == null ? null : req.getRoomId(), null,
					req == null ? null : req.getClientRunId(), "thinking_room_invalid");
			return;
		}
		req.setFromUid(String.valueOf(actor)); // Never use a client-reported fromUid.
		JSONObject result = callIm("POST", "/thinking/start", req, actor, tenant, false);
		JSONObject receipt = result != null && result.get("data") instanceof JSONObject data ? data : null;
		Long id = result == null || !Boolean.TRUE.equals(result.getBool("success")) ? null
				: receipt != null ? receipt.getLong("thinkingId")
				: req.getClientRunId() == null ? result.getLong("data") : null; // Old IM cannot promise run idempotency.
		if (id == null) {
			pushError(actor, req.getRoomId(), null, req.getClientRunId(), result == null
					? "thinking_start_unknown" : "thinking_run_conflict".equals(result.getStr("msg"))
					? "thinking_run_conflict" : receipt == null && req.getClientRunId() != null && Boolean.TRUE.equals(result.getBool("success"))
					? "thinking_run_unsupported" : "thinking_start_failed");
			return;
		}
		String thinkingId = String.valueOf(id);
		WSThinkingStart start = WSThinkingStart.builder().thinkingId(thinkingId)
				.fromUid(String.valueOf(actor)).roomId(req.getRoomId()).triggerMsgId(req.getTriggerMsgId())
				.clientRunId(req.getClientRunId()).build();
		if (receipt != null && Boolean.TRUE.equals(receipt.getBool("replayed"))) {
			Integer status = receipt.getInt("status");
			if (status == null || status < 0 || status > 4) {
				pushError(actor, req.getRoomId(), null, req.getClientRunId(), "thinking_start_unknown");
				return;
			}
			pushToMembers("thinkingStart", start, List.of(actor), actor);
			if (status != 0) {
				WSThinkingEnd terminal = WSThinkingEnd.builder().thinkingId(thinkingId)
						.clientRunId(req.getClientRunId()).roomId(req.getRoomId()).fromUid(String.valueOf(actor))
						.status(status == 1 || status == 4 ? "complete" : "error")
						.error(receipt.getStr("errorCode")).build();
				pushToMembers("thinkingEnd", terminal, List.of(actor), actor);
			}
			// ponytail: caller-only replay avoids frontend same-ID supersede; recovering a crash between
			// DB insert and room broadcast needs an idempotent UI plus durable delivery/outbox.
			return;
		}
		ThinkingContext ctx = new ThinkingContext();
		ctx.setFromUid(actor);
		ctx.setTenantId(tenant);
		ctx.setRoomId(room);
		ctx.setLastActivityTime(System.currentTimeMillis());
		activeThinkings.put(thinkingId, ctx);

		AiclawRateLimitChecker.LimitResult limit = rateLimitChecker.check(actor, room);
		if (limit != AiclawRateLimitChecker.LimitResult.ALLOWED) {
			String error = limit == AiclawRateLimitChecker.LimitResult.RATE_LIMITED
					? "rate_limit_exceeded" : "daily_limit_exceeded";
			if (markError(thinkingId, actor, tenant, room, error, false)) {
				activeThinkings.remove(thinkingId, ctx);
				pushError(actor, req.getRoomId(), thinkingId, req.getClientRunId(), error);
			}
			return;
		}
		rateLimitChecker.record(actor, room);
		List<Long> members = queryRoomMembers(room, actor, tenant);
		if (members.isEmpty()) {
			log.warn("thinking start persisted but members unavailable: id={}", thinkingId);
			pushError(actor, req.getRoomId(), thinkingId, req.getClientRunId(), "thinking_members_unavailable");
			return;
		}
		// The authenticated caller must receive the receipt even if not listed by a stale member lookup.
		if (!members.contains(actor)) members = new ArrayList<>(members);
		if (!members.contains(actor)) members.add(actor);
		pushToMembers("thinkingStart", start, members, actor);
	}

	private void handleEnd(Long actor, Long tenant, WSThinkingEnd req) {
		Long room = positive(req.getRoomId());
		Long id = positive(req.getThinkingId());
		// A room-local latest/unique active thinking is not evidence that an old END belongs to it.
		if (room == null || id == null) {
			log.warn("thinking END rejected: explicit thinkingId and roomId required, actor={}", actor);
			return;
		}
		String thinkingId = String.valueOf(id);
		JSONObject result = callIm("POST", "/thinking/end", req, actor, tenant, false);
		if (result == null || !Boolean.TRUE.equals(result.getBool("success"))) {
			pushRejected(actor, req.getRoomId(), thinkingId, req.getClientRunId(),
					result == null ? "thinking_end_unknown" : "thinking_end_rejected");
			return;
		}
		if (!Boolean.TRUE.equals(result.getBool("data"))) {
			if (req.getClientRunId() != null) pushToMembers("thinkingEnd", WSThinkingEnd.builder()
					.thinkingId(thinkingId).roomId(req.getRoomId()).clientRunId(req.getClientRunId())
					.fromUid(String.valueOf(actor)).durationMs(req.getDurationMs()).status(req.getStatus())
					.error(req.getError()).build(), List.of(actor), actor);
			return; // Verified identical terminal retry, ACK caller only.
		}
		ThinkingContext ctx = activeThinkings.get(thinkingId);
		if (ctx != null && Objects.equals(ctx.getFromUid(), actor) && Objects.equals(ctx.getTenantId(), tenant)
				&& Objects.equals(ctx.getRoomId(), room)) activeThinkings.remove(thinkingId, ctx);
		WSThinkingEnd end = WSThinkingEnd.builder().thinkingId(thinkingId).durationMs(req.getDurationMs())
				.status(req.getStatus()).error(req.getError()).roomId(String.valueOf(room))
				.clientRunId(req.getClientRunId()).fromUid(String.valueOf(actor)).build();
		// CAS already committed; a concurrent kick cannot suppress notification to remaining live members.
		pushToMembers("thinkingEnd", end, queryRoomMembers(room, actor, tenant, true), actor);
	}

	@Scheduled(fixedRate = 60000)
	public void checkThinkingTimeout() {
		long now = System.currentTimeMillis();
		List<String> timedOut = new ArrayList<>();
		activeThinkings.forEach((id, ctx) -> {
			if (now - ctx.getLastActivityTime() > aiclawProperties.getThinking().getTimeoutMs()) timedOut.add(id);
		});
		for (String id : timedOut) {
			ThinkingContext ctx = activeThinkings.get(id);
			if (ctx == null || now - ctx.getLastActivityTime() <= aiclawProperties.getThinking().getTimeoutMs()) continue;
			if (!markError(id, ctx.getFromUid(), ctx.getTenantId(), ctx.getRoomId(), "timeout", true)) {
				// A different WS node or IM's DB sweep may already have won; do not retain a stale index.
				if (isPersistedTerminal(id, ctx)) activeThinkings.remove(id, ctx);
				continue; // Transient IM failure remains unknown and must not clear the index.
			}
			activeThinkings.remove(id, ctx);
			WSThinkingEnd end = WSThinkingEnd.builder().thinkingId(id).status("error")
					.error("thinking timeout").roomId(String.valueOf(ctx.getRoomId())).build();
			pushToMembers("thinkingEnd", end,
					queryRoomMembers(ctx.getRoomId(), ctx.getFromUid(), ctx.getTenantId(), true), ctx.getFromUid());
		}
	}

	private boolean isPersistedTerminal(String id, ThinkingContext ctx) {
		JSONObject result = callIm("GET", "/thinking/" + id + "/terminal?roomId=" + ctx.getRoomId(),
				null, ctx.getFromUid(), ctx.getTenantId(), true);
		return result != null && Boolean.TRUE.equals(result.getBool("data"));
	}

	private boolean markError(String id, Long actor, Long tenant, Long room, String error, boolean timeout) {
		WSThinkingEnd req = WSThinkingEnd.builder().thinkingId(id).roomId(String.valueOf(room)).error(error).build();
		JSONObject result = callIm("POST", "/thinking/error", req, actor, tenant, timeout);
		return result != null && Boolean.TRUE.equals(result.getBool("data"));
	}

	/** IM checks live AICLAW membership before returning recipient IDs. */
	public List<Long> authorizedMembers(Long room, Long actor, Long tenant) {
		return queryRoomMembers(room, actor, tenant, false);
	}

	private List<Long> queryRoomMembers(Long room, Long actor, Long tenant) {
		return queryRoomMembers(room, actor, tenant, false);
	}

	private List<Long> queryRoomMembers(Long room, Long actor, Long tenant, boolean timeout) {
		JSONObject result = callIm("GET", "/thinking/room/" + room + "/members", null, actor, tenant, timeout);
		if (result == null || !Boolean.TRUE.equals(result.getBool("success"))) return List.of();
		try {
			List<Long> members = result.getBeanList("data", Long.class);
			return members == null ? List.of() : members;
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	private JSONObject callIm(String method, String path, Object body, Long actor, Long tenant, boolean timeout) {
		String url = resolveImServiceUrl();
		if (url == null || actor == null || tenant == null || internalSecret == null || internalSecret.isBlank()) return null;
		try {
			HttpRequest request = "GET".equals(method) ? HttpRequest.get(url + path) : HttpRequest.post(url + path);
			request.header(SERVICE_AUTH, internalSecret).header(ACTOR, String.valueOf(actor))
					.header(ContextConstants.HEADER_TENANT_ID, String.valueOf(tenant)).timeout(5000);
			if (timeout) request.header(SERVICE_TIMEOUT, "true");
			if (body != null) request.header("Content-Type", "application/json").body(JSONUtil.toJsonStr(body));
			try (HttpResponse response = request.execute()) {
				if (response.getStatus() != 200) {
					log.warn("thinking IM request failed: path={}, HTTP={}", path, response.getStatus());
					return null;
				}
				JSONObject result = JSONUtil.parseObj(response.body());
				if (!Integer.valueOf(R.SUCCESS_CODE).equals(result.getInt("code")) || !Boolean.TRUE.equals(result.getBool("success"))) {
					log.warn("thinking IM rejected: path={}, code={}", path, result.getInt("code"));
					return result;
				}
				return result;
			}
		} catch (Exception e) {
			log.error("thinking IM request failed: path={}, kind={}", path, e.getClass().getSimpleName());
			return null;
		}
	}

	private String resolveImServiceUrl() {
		try {
			List<ServiceInstance> instances = discoveryClient.getInstances("luohuo-im-server");
			if (instances != null && !instances.isEmpty()) return instances.get(0).getUri().toString();
		} catch (Exception e) {
			log.error("Failed to resolve IM service", e);
		}
		return null;
	}

	private void pushRejected(Long actor, String room, String id, String clientRunId, String error) {
		WSThinkingEnd rejection = WSThinkingEnd.builder().thinkingId(id).roomId(room)
				.fromUid(String.valueOf(actor)).clientRunId(clientRunId)
				.status("error").error(error).build();
		pushToMembers("thinkingRejected", rejection, List.of(actor), actor);
	}

	private void pushError(Long actor, String room, String id, String clientRunId, String error) {
		WSThinkingEnd end = WSThinkingEnd.builder().thinkingId(id).roomId(room)
				.fromUid(String.valueOf(actor)).clientRunId(clientRunId).status("error").error(error).build();
		pushToMembers("thinkingEnd", end, List.of(actor), actor);
	}

	private <T> void pushToMembers(String type, T data, List<Long> members, Long actor) {
		if (members == null || members.isEmpty()) return;
		WsBaseResp<T> resp = new WsBaseResp<>();
		resp.setType(type);
		resp.setData(data);
		pushService.sendPushMsg(resp, members, actor);
	}

	@Data
	private static class ThinkingContext {
		private Long fromUid;
		private Long tenantId;
		private Long roomId;
		private long lastActivityTime;
	}
}
