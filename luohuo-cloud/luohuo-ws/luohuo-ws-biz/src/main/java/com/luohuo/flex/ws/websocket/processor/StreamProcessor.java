package com.luohuo.flex.ws.websocket.processor;

import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.base.R;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.ws.ReactiveContextUtil;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSStreamDelta;
import com.luohuo.flex.model.entity.ws.WSStreamEnd;
import com.luohuo.flex.model.entity.ws.WSStreamStart;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.StreamDeltaReq;
import com.luohuo.flex.model.ws.StreamEndReq;
import com.luohuo.flex.model.ws.StreamStartReq;
import com.luohuo.flex.model.ws.WSBaseReq;
import org.springframework.beans.factory.annotation.Value;
import com.luohuo.flex.ws.service.PushService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式消息处理器
 * 处理 aiclaw plugins 发送的 STREAM_START / STREAM_DELTA / STREAM_END
 * 中继转发给目标用户，stream_end 时通过 HTTP 调用 IM 服务落库（不引入额外 MQ topic）
 */
@Slf4j
@Order(17)
@Component
@RequiredArgsConstructor
public class StreamProcessor implements MessageProcessor {

	@Resource
	private PushService pushService;
	@Resource
	private DiscoveryClient discoveryClient;
	@Resource
	private AiclawProperties aiclawProperties;
	@Resource
	private ThinkingProcessor thinkingProcessor;
	@Value("${THINKING_INTERNAL_SECRET}")
	private String internalSecret;

	/**
	 * 活跃流状态：aiclawUid → StreamContext
	 */
	private final ConcurrentHashMap<Long, StreamContext> activeStreams = new ConcurrentHashMap<>();
	// Ordered per actor: authorization may block on IM, but never on Netty's receive event loop.
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingFrames = new ConcurrentHashMap<>();
	// ponytail: global 128-frame bound, replace with per-actor admission if throughput demands it.
	private final AtomicInteger pendingFrameCount = new AtomicInteger();
	private final Executor streamExecutor = task -> Schedulers.boundedElastic().schedule(task);

	@Override
	public boolean supports(WSBaseReq req) {
		return WSReqTypeEnum.STREAM_START.eq(req.getType())
				|| WSReqTypeEnum.STREAM_DELTA.eq(req.getType())
				|| WSReqTypeEnum.STREAM_END.eq(req.getType());
	}

	@Override
	public void process(WebSocketSession session, Long uid, WSBaseReq payload) {
		// Authenticate every frame; a stale/forged stream END must not consume another session's state.
		Long tenantId = thinkingProcessor.trustedTenant(session, uid);
		if (tenantId == null) {
			log.warn("stream rejected: untrusted WS session");
			return;
		}
		if (pendingFrameCount.incrementAndGet() > 128) {
			pendingFrameCount.decrementAndGet();
			log.warn("stream rejected: authorization queue full, closing WS actor={}", uid);
			session.close(new CloseStatus(1013, "stream overloaded; reconnect")).subscribe();
			return;
		}
		CompletableFuture<Void> queued;
		try {
			queued = pendingFrames.compute(uid, (key, previous) -> {
				CompletableFuture<Void> prior = previous == null ? CompletableFuture.completedFuture(null) : previous;
				return prior.handle((ignored, error) -> null)
						.thenRunAsync(() -> processAuthorizedFrame(uid, tenantId, payload), streamExecutor);
			});
		} catch (RuntimeException e) {
			pendingFrameCount.decrementAndGet();
			throw e;
		}
		queued.whenComplete((ignored, error) -> {
			pendingFrames.remove(uid, queued);
			pendingFrameCount.decrementAndGet();
			if (error != null) log.warn("stream frame rejected: actor={}, kind={}", uid, error.getClass().getSimpleName());
		});
	}

	private void processAuthorizedFrame(Long uid, Long tenantId, WSBaseReq payload) {
		ReactiveContextUtil.setTenantId(tenantId);
		ReactiveContextUtil.setUid(uid);
		try {
			WSReqTypeEnum type = WSReqTypeEnum.of(payload.getType());
			if (type == null) return;
			switch (type) {
				case STREAM_START -> handleStart(uid, tenantId, payload);
				case STREAM_DELTA -> handleDelta(uid, tenantId, payload);
				case STREAM_END -> handleEnd(uid, tenantId, payload);
				default -> log.warn("StreamProcessor: unexpected type {}", payload.getType());
			}
		} finally {
			ReactiveContextUtil.remove();
		}
	}

	private boolean authorizedRecipient(Long actor, Long tenant, Long room, Long recipient) {
		if (actor == null || tenant == null || room == null || room <= 0 || recipient == null || recipient <= 0) return false;
		// ponytail: O(frames) live IM checks, bounded off the Netty loop; use a revocation-
		// invalidated authorization snapshot only if stream throughput demands it.
		return thinkingProcessor.authorizedMembers(room, actor, tenant).contains(recipient);
	}

	private void handleStart(Long aiclawUid, Long tenantId, WSBaseReq payload) {
		StreamStartReq req = JSONUtil.toBean(payload.getData(), StreamStartReq.class);
		if (!authorizedRecipient(aiclawUid, tenantId, req.getRoomId(), req.getToUid())) {
			log.warn("stream_start rejected: actor={}, room={}", aiclawUid, req.getRoomId());
			return;
		}

		// 如果已有活跃流，先推送旧流的 error end
		StreamContext oldCtx = activeStreams.remove(aiclawUid);
		if (oldCtx != null) {
			pushErrorEnd(oldCtx, "AI助理开始了新的回复");
		}

		// 生成 msgId
		long msgId = IdUtil.getSnowflakeNextId();

		// 注册新流
		StreamContext ctx = new StreamContext();
		ctx.setMsgId(msgId);
		ctx.setFromUid(aiclawUid);
		ctx.setTenantId(tenantId);
		ctx.setToUid(req.getToUid());
		ctx.setRoomId(req.getRoomId());
		// ISS-015: 记录 stream_start 时间戳,stream_end 落库时回灌到 im_message.create_time,
		// 避免长流式回复因 create_time = stream_end 被新用户消息夹塞到后面。
		ctx.setStartTimeMs(System.currentTimeMillis());
		ctx.setLastActivityTime(ctx.getStartTimeMs());
		activeStreams.put(aiclawUid, ctx);

		// 中继给目标用户
		WSStreamStart startResp = WSStreamStart.builder()
				.msgId(msgId)
				.fromUid(aiclawUid)
				.toUid(req.getToUid())
				.roomId(req.getRoomId())
				.build();
		pushToUser("streamStart", startResp, req.getToUid(), aiclawUid);

		log.debug("stream_start: aiclaw={}, msgId={}, toUid={}", aiclawUid, msgId, req.getToUid());
	}

	private void handleDelta(Long aiclawUid, Long tenantId, WSBaseReq payload) {
		StreamContext ctx = activeStreams.get(aiclawUid);
		if (ctx == null || !tenantId.equals(ctx.getTenantId())) {
			log.warn("stream_delta without active stream: aiclaw={}", aiclawUid);
			return;
		}
		if (!authorizedRecipient(aiclawUid, tenantId, ctx.getRoomId(), ctx.getToUid())) {
			activeStreams.remove(aiclawUid, ctx);
			log.warn("stream_delta rejected: actor={}, room={}", aiclawUid, ctx.getRoomId());
			return;
		}

		StreamDeltaReq req = JSONUtil.toBean(payload.getData(), StreamDeltaReq.class);
		ctx.setLastActivityTime(System.currentTimeMillis());

		WSStreamDelta deltaResp = WSStreamDelta.builder()
				.msgId(ctx.getMsgId())
				.chunk(req.getChunk())
				.seq(req.getSeq())
				.build();
		pushToUser("streamDelta", deltaResp, ctx.getToUid(), aiclawUid);
	}

	private void handleEnd(Long aiclawUid, Long tenantId, WSBaseReq payload) {
		StreamContext ctx = activeStreams.get(aiclawUid);
		if (ctx == null || !tenantId.equals(ctx.getTenantId())) {
			log.warn("stream_end without active stream: aiclaw={}", aiclawUid);
			return;
		}
		if (!authorizedRecipient(aiclawUid, tenantId, ctx.getRoomId(), ctx.getToUid())) {
			activeStreams.remove(aiclawUid, ctx);
			log.warn("stream_end rejected: actor={}, room={}", aiclawUid, ctx.getRoomId());
			return;
		}
		StreamEndReq req = JSONUtil.toBean(payload.getData(), StreamEndReq.class);
		ctx.setLastActivityTime(System.currentTimeMillis());
		// A complete answer is visible only after IM has accepted its durable message write.
		if ("complete".equals(req.getStatus()) && !persistStreamMessage(ctx, req.getFullContent())) {
			activeStreams.remove(aiclawUid, ctx);
			pushErrorEnd(ctx, "stream_persistence_unknown");
			return; // Unknown delivery is never retried automatically.
		}
		if (!activeStreams.remove(aiclawUid, ctx)) return;
		WSStreamEnd endResp = WSStreamEnd.builder()
				.msgId(ctx.getMsgId())
				.fullContent(req.getFullContent())
				.status(req.getStatus())
				.build();
		pushToUser("streamEnd", endResp, ctx.getToUid(), aiclawUid);

		log.debug("stream_end: aiclaw={}, msgId={}, status={}", aiclawUid, ctx.getMsgId(), req.getStatus());
	}

	/**
	 * 超时扫描：每 10 秒检查活跃流是否超时（30s 无活动）
	 */
	@Scheduled(fixedRate = 10000)
	public void checkStreamTimeout() {
		long now = System.currentTimeMillis();
		List<Long> timedOut = new ArrayList<>();

		activeStreams.forEach((aiclawUid, ctx) -> {
			if (now - ctx.getLastActivityTime() > aiclawProperties.getStream().getTimeoutMs()) {
				timedOut.add(aiclawUid);
			}
		});

		for (Long aiclawUid : timedOut) {
			StreamContext ctx = activeStreams.remove(aiclawUid);
			if (ctx != null) {
				pushErrorEnd(ctx, "AI助理响应超时");
				log.warn("stream timeout: aiclaw={}, msgId={}", aiclawUid, ctx.getMsgId());
			}
		}
	}

	// -------- 内部方法 --------

	private void pushErrorEnd(StreamContext ctx, String errorMsg) {
		if (!authorizedRecipient(ctx.getFromUid(), ctx.getTenantId(), ctx.getRoomId(), ctx.getToUid())) return;
		WSStreamEnd endResp = WSStreamEnd.builder()
				.msgId(ctx.getMsgId())
				.fullContent(errorMsg)
				.status("error")
				.build();
		pushToUser("streamEnd", endResp, ctx.getToUid(), ctx.getFromUid());
	}

	private <T> void pushToUser(String type, T data, Long toUid, Long fromUid) {
		WsBaseResp<T> resp = new WsBaseResp<>();
		resp.setType(type);
		resp.setData(data);
		pushService.sendPushMsg(resp, toUid, fromUid);
	}

	/**
	 * 通过 HTTP 调用 IM 服务的消息发送接口落库（不引入额外 MQ topic）
	 * 复用现有 POST /chat/msg 接口，以 aiclaw 身份发送消息
	 */
	private boolean persistStreamMessage(StreamContext ctx, String content) {
		String imBaseUrl = resolveImServiceUrl();
		if (imBaseUrl == null || internalSecret == null || internalSecret.isBlank()) {
			log.error("stream_end persist unavailable: IM service or internal authentication missing");
			return false;
		}

		// 构造与 ChatMessageReq 兼容的请求体
		// ISS-015: 带 sendTime=stream_start 时间戳,IM 侧 buildMsgSave 在 skipPush=true 时回灌到 create_time,
		// 让前端 sendTime(=create_time) 反映「AI 开始回复」时刻,而非「AI 完成回复」时刻。
		String body = JSONUtil.toJsonStr(java.util.Map.of(
				"roomId", ctx.getRoomId(),
				"msgType", 1,
				"body", java.util.Map.of("content", content == null ? "" : content),
				"skip", true,
				"skipPush", true,
				"sendTime", ctx.getStartTimeMs()
		));

		try (HttpResponse response = HttpRequest.post(imBaseUrl + "/chat/msg")
				.header("X-Thinking-Service-Auth", internalSecret)
				.header("X-Thinking-Actor-Uid", String.valueOf(ctx.getFromUid()))
				.header("uid", String.valueOf(ctx.getFromUid()))
				.header("userId", String.valueOf(ctx.getFromUid()))
				.header("tenantId", String.valueOf(ctx.getTenantId()))
				.header("Content-Type", "application/json")
				.body(body).timeout(5000).execute()) {
			if (response.getStatus() != 200) return false;
			JSONObject result = JSONUtil.parseObj(response.body());
			return Integer.valueOf(R.SUCCESS_CODE).equals(result.getInt("code"))
					&& Boolean.TRUE.equals(result.getBool("success"));
		} catch (RuntimeException e) {
			log.warn("stream persistence result unknown: msgId={}, kind={}", ctx.getMsgId(), e.getClass().getSimpleName());
			return false;
		}
	}

	/**
	 * 通过 Nacos 服务发现获取 IM 服务地址
	 */
	private String resolveImServiceUrl() {
		try {
			java.util.List<ServiceInstance> instances = discoveryClient.getInstances("luohuo-im-server");
			if (instances != null && !instances.isEmpty()) {
				ServiceInstance instance = instances.get(0);
				return instance.getUri().toString();
			}
		} catch (Exception e) {
			log.error("Failed to resolve IM service: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * 活跃流上下文
	 */
	@Data
	private static class StreamContext {
		private Long msgId;
		private Long fromUid;
		private Long tenantId;
		private Long toUid;
		private Long roomId;
		private long lastActivityTime;
		/**
		 * ISS-015: stream_start 时间戳(epoch ms),stream_end 落库时透传给 IM 服务,
		 * 用于覆盖 im_message.create_time。
		 */
		private long startTimeMs;
	}
}
