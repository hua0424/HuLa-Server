package com.luohuo.flex.ws.websocket.processor;

import cn.hutool.json.JSONUtil;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.WSBaseReq;
import com.luohuo.flex.common.config.AiclawProperties;
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
 * 处理 aiclaw plugins 发送的 THINKING_START / THINKING_END（S4 起 DELTA 已废弃）
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
	@Resource
	private AiclawProperties aiclawProperties;

	private final WebClient webClient = WebClient.create();

	/**
	 * 活跃 thinking 上下文：thinkingId → ThinkingContext
	 */
	private final ConcurrentHashMap<String, ThinkingContext> activeThinkings = new ConcurrentHashMap<>();

	/**
	 * 二级索引：aiclawUid:roomId → thinkingId（用于 delta/end 缺失 thinkingId 时反查）
	 */
	private final ConcurrentHashMap<String, String> aiclawRoomIndex = new ConcurrentHashMap<>();

	@Override
	public boolean supports(WSBaseReq req) {
		return WSReqTypeEnum.THINKING_START.eq(req.getType())
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
		ctx.setTriggerMsgId(req.getTriggerMsgId());
		ctx.setMemberUids(memberUids);
		ctx.setLastActivityTime(System.currentTimeMillis());
		activeThinkings.put(thinkingIdStr, ctx);
		aiclawRoomIndex.put(buildAiclawRoomKey(aiclawUid, roomId), thinkingIdStr);

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

	private void handleEnd(Long aiclawUid, WSBaseReq payload) {
		WSThinkingEnd req = JSONUtil.toBean(payload.getData(), WSThinkingEnd.class);
		String thinkingIdStr = req.getThinkingId();
		Long roomIdFromReq = req.getRoomId() != null ? Long.valueOf(req.getRoomId()) : null;

		// fallback：thinkingId 缺失时反查。END 不携带 triggerMsgId，故 (aiclaw, room) 即有效键
		// （并发守卫下唯一）。先走内存索引快路径，再走跨重启 DB 兜底。
		if (thinkingIdStr == null || thinkingIdStr.isBlank()) {
			if (roomIdFromReq != null) {
				thinkingIdStr = aiclawRoomIndex.get(buildAiclawRoomKey(aiclawUid, roomIdFromReq));
			}
			// 跨重启场景：内存 activeThinkings + aiclawRoomIndex 均已丢失，回退到 DB 反查 status=0 最新记录
			if (thinkingIdStr == null && roomIdFromReq != null) {
				Long resolved = resolveActiveThinkingViaHttp(aiclawUid, roomIdFromReq);
				if (resolved != null) {
					thinkingIdStr = String.valueOf(resolved);
					log.debug("thinking_end thinkingId DB fallback resolved: aiclaw={}, roomId={}, thinkingId={}",
							aiclawUid, roomIdFromReq, thinkingIdStr);
				}
			}
			if (thinkingIdStr == null) {
				log.warn("thinking_end missing thinkingId and no fallback: aiclaw={}, roomId={}", aiclawUid, roomIdFromReq);
				return;
			}
			log.debug("thinking_end thinkingId fallback resolved: aiclaw={}, roomId={}, thinkingId={}", aiclawUid, roomIdFromReq, thinkingIdStr);
		}

		ThinkingContext ctx = activeThinkings.remove(thinkingIdStr);
		if (ctx != null) {
			aiclawRoomIndex.remove(buildAiclawRoomKey(ctx.getFromUid(), ctx.getRoomId()));
		}

		// ctx 为空（如跨重启 DB 兜底）时仍需 finalize 落库 + 广播；roomId 取自 req
		req.setThinkingId(thinkingIdStr);

		// 1. 调用 IM 服务 finalize（content 随 req 整体透传至 /thinking/end）
		finalizeViaHttp(req);

		// 2. 广播 thinkingEnd（仅状态，绝不携带 content）
		Long broadcastRoomId = ctx != null ? ctx.getRoomId() : roomIdFromReq;
		WSThinkingEnd endResp = WSThinkingEnd.builder()
				.thinkingId(thinkingIdStr)
				.durationMs(req.getDurationMs())
				.status(req.getStatus())
				.error(req.getError())
				.roomId(broadcastRoomId != null ? String.valueOf(broadcastRoomId) : null)
				.build();
		// ctx 存在走内存成员列表；ctx 为空（跨重启：内存丢失）时重建成员列表，
		// 否则只发给 aiclawUid 会导致看过 thinkingStart 的真人成员的 thinking 指示器永久挂起。
		// 守卫：roomId 为空或 HTTP 查询结果为空时回退 List.of(aiclawUid)，避免 NPE / 空推送。
		List<Long> members;
		if (ctx != null) {
			members = ctx.getMemberUids();
		} else {
			List<Long> rebuilt = roomIdFromReq != null ? queryRoomMembersViaHttp(roomIdFromReq) : null;
			members = (rebuilt != null && !rebuilt.isEmpty()) ? rebuilt : List.of(aiclawUid);
		}
		pushToMembers("thinkingEnd", endResp, members, aiclawUid);

		log.debug("thinking_end: aiclaw={}, thinkingId={}, status={}, ctxPresent={}",
				aiclawUid, thinkingIdStr, req.getStatus(), ctx != null);
	}

	/**
	 * 超时扫描：每 60 秒检查活跃 thinking 是否超时
	 */
	@Scheduled(fixedRate = 60000)
	public void checkThinkingTimeout() {
		long now = System.currentTimeMillis();
		List<String> timedOut = new ArrayList<>();

		activeThinkings.forEach((thinkingId, ctx) -> {
			if (now - ctx.getLastActivityTime() > aiclawProperties.getThinking().getTimeoutMs()) {
				timedOut.add(thinkingId);
			}
		});

		for (String thinkingId : timedOut) {
			ThinkingContext ctx = activeThinkings.remove(thinkingId);
			if (ctx != null) {
				aiclawRoomIndex.remove(buildAiclawRoomKey(ctx.getFromUid(), ctx.getRoomId()));
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

	private String buildAiclawRoomKey(Long aiclawUid, Long roomId) {
		return aiclawUid + ":" + roomId;
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

	/**
	 * 跨重启兜底：通过 IM HTTP 反查 (aiclawUid, roomId) 最近一条进行中（status=0）的 thinkingId。
	 * 采用与 createThinkingViaHttp 相同的 Hutool 同步 HTTP 风格。
	 */
	private Long resolveActiveThinkingViaHttp(Long aiclawUid, Long roomId) {
		String url = resolveImServiceUrl();
		if (url == null) return null;

		try {
			String resp = cn.hutool.http.HttpRequest.post(url + "/thinking/resolve-active")
					.form("aiclawUid", aiclawUid)
					.form("roomId", roomId)
					.execute()
					.body();
			cn.hutool.json.JSONObject json = JSONUtil.parseObj(resp);
			return json.getLong("data");
		} catch (Exception e) {
			log.error("resolveActiveThinkingViaHttp failed: aiclaw={}, roomId={}", aiclawUid, roomId, e);
			return null;
		}
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
		private String triggerMsgId;
		private List<Long> memberUids;
		private long lastActivityTime;
	}
}
