package com.luohuo.flex.ws.websocket.processor;

import cn.hutool.json.JSONUtil;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSThinkingDelta;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.WSBaseReq;
import com.luohuo.flex.ws.ReactiveContextUtil;
import com.luohuo.flex.ws.service.AiclawRateLimitChecker;
import com.luohuo.flex.ws.service.PushService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thinking 消息处理器
 * 处理 aiclaw plugins 发送的 THINKING_START / THINKING_DELTA / THINKING_END
 * 落库通过 HTTP 调用 IM 服务，推送通过 PushService 广播给群成员
 */
@Slf4j
@Order(20)
@Component
public class ThinkingProcessor implements MessageProcessor {

	@Resource
	private PushService pushService;
	@Resource
	private DiscoveryClient discoveryClient;
	@Resource
	private AiclawRateLimitChecker rateLimitChecker;

	private final WebClient webClient = WebClient.create();

	/**
	 * 活跃 thinking 上下文：thinkingId → ThinkingContext
	 */
	private final ConcurrentHashMap<String, ThinkingContext> activeThinkings = new ConcurrentHashMap<>();

	/**
	 * thinking 超时阈值（毫秒），默认 5 分钟
	 */
	private static final long THINKING_TIMEOUT_MS = 5 * 60 * 1000;

	@Override
	public boolean supports(WSBaseReq req) {
		return WSReqTypeEnum.THINKING_START.eq(req.getType())
				|| WSReqTypeEnum.THINKING_DELTA.eq(req.getType())
				|| WSReqTypeEnum.THINKING_END.eq(req.getType());
	}

	@Override
	public void process(WebSocketSession session, Long uid, WSBaseReq payload) {
		WSReqTypeEnum type = WSReqTypeEnum.of(payload.getType());
		if (type == null) {
			return;
		}
		switch (type) {
			case THINKING_START -> handleStart(uid, payload);
			case THINKING_DELTA -> handleDelta(uid, payload);
			case THINKING_END -> handleEnd(uid, payload);
			default -> log.warn("ThinkingProcessor: unexpected type {}", payload.getType());
		}
	}

	private void handleStart(Long aiclawUid, WSBaseReq payload) {
		WSThinkingStart req = JSONUtil.toBean(payload.getData(), WSThinkingStart.class);
		Long roomId = Long.valueOf(req.getRoomId());

		// 1. 先创建 thinking 记录（确保限流拒绝时有 thinkingId 用于 plugin 路由）
		Long thinkingId = createThinkingViaHttp(req);
		if (thinkingId == null) {
			log.error("thinking start failed: aiclaw={}, roomId={}", aiclawUid, roomId);
			return;
		}
		String thinkingIdStr = String.valueOf(thinkingId);

		// REQ-004 M4: THINKING_START 前置限流校验（thinkingId 已生成）
		AiclawRateLimitChecker.LimitResult limitResult = rateLimitChecker.check(aiclawUid, roomId);
		if (limitResult != AiclawRateLimitChecker.LimitResult.ALLOWED) {
			String errorMsg = limitResult == AiclawRateLimitChecker.LimitResult.RATE_LIMITED
					? "rate_limit_exceeded" : "daily_limit_exceeded";
			// 标记 thinking 为错误状态
			markErrorViaHttp(thinkingIdStr, errorMsg);
			WSThinkingEnd endResp = WSThinkingEnd.builder()
					.thinkingId(thinkingIdStr)
					.status("error")
					.error(errorMsg)
					.roomId(req.getRoomId())
					.build();
			pushToMembers("thinkingEnd", endResp, List.of(aiclawUid), aiclawUid);
			log.warn("thinking_start rate limited: aiclaw={}, roomId={}, thinkingId={}, reason={}",
					aiclawUid, roomId, thinkingIdStr, errorMsg);
			return;
		}

		// 记录限流计数（thinking 创建成功即视为一次"发言意图"）
		rateLimitChecker.record(aiclawUid, roomId);

		// 2. 查询群成员
		List<Long> memberUids = queryRoomMembersViaHttp(roomId);

		// 3. 缓存上下文
		ThinkingContext ctx = new ThinkingContext();
		ctx.setThinkingId(thinkingIdStr);
		ctx.setFromUid(aiclawUid);
		ctx.setRoomId(roomId);
		ctx.setMemberUids(memberUids);
		ctx.setLastActivityTime(System.currentTimeMillis());
		activeThinkings.put(thinkingIdStr, ctx);

		// 4. 广播 thinkingStart（含 thinkingId 回传）
		WSThinkingStart startResp = WSThinkingStart.builder()
				.thinkingId(thinkingIdStr)
				.fromUid(String.valueOf(aiclawUid))
				.roomId(req.getRoomId())
				.triggerMsgId(req.getTriggerMsgId())
				.build();
		pushToMembers("thinkingStart", startResp, memberUids, aiclawUid);

		log.debug("thinking_start: aiclaw={}, thinkingId={}, roomId={}, members={}",
				aiclawUid, thinkingIdStr, roomId, memberUids.size());
	}

	private void handleDelta(Long aiclawUid, WSBaseReq payload) {
		WSThinkingDelta req = JSONUtil.toBean(payload.getData(), WSThinkingDelta.class);
		String thinkingIdStr = req.getThinkingId();
		if (thinkingIdStr == null) {
			log.warn("thinking_delta missing thinkingId: aiclaw={}", aiclawUid);
			return;
		}

		ThinkingContext ctx = activeThinkings.get(thinkingIdStr);
		if (ctx == null) {
			log.warn("thinking_delta without active thinking: aiclaw={}, thinkingId={}", aiclawUid, thinkingIdStr);
			return;
		}
		ctx.setLastActivityTime(System.currentTimeMillis());

		// 1. 调用 IM 服务追加 delta
		appendDeltaViaHttp(req);

		// 2. 广播 thinkingDelta
		WSThinkingDelta deltaResp = WSThinkingDelta.builder()
				.thinkingId(thinkingIdStr)
				.chunk(req.getChunk())
				.seq(req.getSeq())
				.roomId(String.valueOf(ctx.getRoomId()))
				.build();
		pushToMembers("thinkingDelta", deltaResp, ctx.getMemberUids(), aiclawUid);
	}

	private void handleEnd(Long aiclawUid, WSBaseReq payload) {
		WSThinkingEnd req = JSONUtil.toBean(payload.getData(), WSThinkingEnd.class);
		String thinkingIdStr = req.getThinkingId();
		if (thinkingIdStr == null) {
			log.warn("thinking_end missing thinkingId: aiclaw={}", aiclawUid);
			return;
		}

		ThinkingContext ctx = activeThinkings.remove(thinkingIdStr);
		if (ctx == null) {
			log.warn("thinking_end without active thinking: aiclaw={}, thinkingId={}", aiclawUid, thinkingIdStr);
			return;
		}

		// 1. 调用 IM 服务 finalize
		finalizeViaHttp(req);

		// 2. 广播 thinkingEnd
		WSThinkingEnd endResp = WSThinkingEnd.builder()
				.thinkingId(thinkingIdStr)
				.durationMs(req.getDurationMs())
				.status(req.getStatus())
				.error(req.getError())
				.roomId(String.valueOf(ctx.getRoomId()))
				.build();
		pushToMembers("thinkingEnd", endResp, ctx.getMemberUids(), aiclawUid);

		log.debug("thinking_end: aiclaw={}, thinkingId={}, status={}", aiclawUid, thinkingIdStr, req.getStatus());
	}

	/**
	 * 超时扫描：每 60 秒检查活跃 thinking 是否超时
	 */
	@Scheduled(fixedRate = 60000)
	public void checkThinkingTimeout() {
		long now = System.currentTimeMillis();
		List<String> timedOut = new ArrayList<>();

		activeThinkings.forEach((thinkingId, ctx) -> {
			if (now - ctx.getLastActivityTime() > THINKING_TIMEOUT_MS) {
				timedOut.add(thinkingId);
			}
		});

		for (String thinkingId : timedOut) {
			ThinkingContext ctx = activeThinkings.remove(thinkingId);
			if (ctx != null) {
				markErrorViaHttp(thinkingId, "timeout");
				WSThinkingEnd endResp = WSThinkingEnd.builder()
						.thinkingId(thinkingId)
						.status("error")
						.error("thinking timeout")
						.roomId(String.valueOf(ctx.getRoomId()))
						.build();
				pushToMembers("thinkingEnd", endResp, ctx.getMemberUids(), ctx.getFromUid());
				log.warn("thinking timeout: aiclaw={}, thinkingId={}", ctx.getFromUid(), thinkingId);
			}
		}
	}

	// -------- HTTP 调用 IM 服务 --------

	private Long createThinkingViaHttp(WSThinkingStart req) {
		String url = resolveImServiceUrl();
		if (url == null) return null;

		try {
			// M4-fix: 改用 Hutool 同步 HTTP，避免 reactor event loop 上调用 Mono.block()
			String resp = cn.hutool.http.HttpRequest.post(url + "/thinking/start")
					.header("Content-Type", "application/json")
					.body(JSONUtil.toJsonStr(req))
					.execute()
					.body();
			cn.hutool.json.JSONObject json = JSONUtil.parseObj(resp);
			return json.getLong("data");
		} catch (Exception e) {
			log.error("createThinkingViaHttp failed", e);
			return null;
		}
	}

	private void appendDeltaViaHttp(WSThinkingDelta req) {
		String url = resolveImServiceUrl();
		if (url == null) return;

		webClient.post()
				.uri(url + "/thinking/delta")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(req)
				.retrieve()
				.bodyToMono(String.class)
				.doOnError(err -> log.error("appendDeltaViaHttp failed: {}", err.getMessage()))
				.subscribe();
	}

	private void finalizeViaHttp(WSThinkingEnd req) {
		String url = resolveImServiceUrl();
		if (url == null) return;

		webClient.post()
				.uri(url + "/thinking/end")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(req)
				.retrieve()
				.bodyToMono(String.class)
				.doOnError(err -> log.error("finalizeViaHttp failed: {}", err.getMessage()))
				.subscribe();
	}

	private void markErrorViaHttp(String thinkingId, String errorCode) {
		String url = resolveImServiceUrl();
		if (url == null) return;

		WSThinkingEnd req = WSThinkingEnd.builder()
				.thinkingId(thinkingId)
				.error(errorCode)
				.build();

		webClient.post()
				.uri(url + "/thinking/error")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(req)
				.retrieve()
				.bodyToMono(String.class)
				.doOnError(err -> log.error("markErrorViaHttp failed: {}", err.getMessage()))
				.subscribe();
	}

	private List<Long> queryRoomMembersViaHttp(Long roomId) {
		String url = resolveImServiceUrl();
		if (url == null) return List.of();

		try {
			// M4-fix: 改用 Hutool 同步 HTTP，避免 reactor event loop 上调用 Mono.block()
			String resp = cn.hutool.http.HttpRequest.get(url + "/thinking/room/" + roomId + "/members")
					.execute()
					.body();
			cn.hutool.json.JSONObject json = JSONUtil.parseObj(resp);
			return json.getBeanList("data", Long.class);
		} catch (Exception e) {
			log.error("queryRoomMembersViaHttp failed: roomId={}", roomId, e);
			return List.of();
		}
	}

	private String resolveImServiceUrl() {
		try {
			List<ServiceInstance> instances = discoveryClient.getInstances("luohuo-im-server");
			if (instances != null && !instances.isEmpty()) {
				return instances.get(0).getUri().toString();
			}
		} catch (Exception e) {
			log.error("Failed to resolve IM service: {}", e.getMessage());
		}
		return null;
	}

	// -------- 推送 --------

	private <T> void pushToMembers(String type, T data, List<Long> memberUids, Long fromUid) {
		WsBaseResp<T> resp = new WsBaseResp<>();
		resp.setType(type);
		resp.setData(data);
		pushService.sendPushMsg(resp, memberUids, fromUid);
	}

	/**
	 * 活跃 thinking 上下文
	 */
	@Data
	private static class ThinkingContext {
		private String thinkingId;
		private Long fromUid;
		private Long roomId;
		private List<Long> memberUids;
		private long lastActivityTime;
	}
}
