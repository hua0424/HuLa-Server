package com.luohuo.flex.im.controller.chat;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.service.CcAiclawResolveService;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.CcAiclawResolveResp;
import com.luohuo.flex.model.entity.ws.CcLaunchResp;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REQ-010 S9: CC（claude-code）绑定对客户端入口（im-server，client-facing）。
 *
 * <p>对外契约：{@code GET /im/room/aiclaw/cc-launch?roomId={Long}[&uid={Long}]}
 * （网关 {@code /im/**} StripPrefix=1 后到达本服务 {@code /room/aiclaw/cc-launch}）。</p>
 *
 * <p>链路（FINAL 架构 —— 各司其职、就近其数据）：
 * <ol>
 *   <li>im 取登录态 requesterUid（{@link ContextUtil#getUid()}，网关 {@code TokenContextFilter}
 *       从已验证 token 注入 uid 头 —— 客户端无法伪造）；</li>
 *   <li>im <b>进程内</b>解析房间的 CC aiclaw + owner 鉴权（{@link CcAiclawResolveService}，
 *       im 拥有 room/aiclaw/owner 数据，无需出网）；</li>
 *   <li>im 经服务发现（DiscoveryClient 直连 {@code luohuo-ws-server} 实例，<b>绕过网关</b>）
 *       调用 ws 的<b>内部</b> ccBind 端点 —— ws 拥有 WS 会话 + requestId 关联；</li>
 *   <li>把 ws 返回的 {@link CcLaunchResp} 透传给客户端。</li>
 * </ol></p>
 *
 * <p>鉴权全部在本层（im）完成；ws 的内部端点不做鉴权，故必须不可被外部客户端经网关直达
 * （见 ws 侧 {@code CcInternalController} 的 X-Internal-Token 校验）。</p>
 *
 * @author developer
 */
@Slf4j
@RestController
@RequestMapping("/room/aiclaw")
public class CcLaunchController {

	@Resource
	private CcAiclawResolveService ccAiclawResolveService;
	@Resource
	private DiscoveryClient discoveryClient;

	/** 与 ws 内部 ccBind 端点约定的共享秘钥（网关不注入此头 → 外部请求无法携带）。 */
	@Value("${aichat.cc.internal-token:aichat-cc-internal}")
	private String internalToken;

	@GetMapping("/cc-launch")
	@Operation(summary = "CC（claude-code）绑定：解析+owner鉴权后向节点取 owner 启动命令")
	public R<CcLaunchResp> ccLaunch(@RequestParam Long roomId,
									@RequestParam(required = false) Long uid) {
		Long requesterUid = ContextUtil.getUid();
		if (requesterUid == null) {
			return R.fail("未登录");
		}

		// 1. 进程内解析 CC aiclaw + owner 鉴权
		CcAiclawResolveResp resolved = ccAiclawResolveService.resolveCcAiclawForRoom(roomId, requesterUid, uid);
		if (!Boolean.TRUE.equals(resolved.getOk())) {
			return R.fail(mapResolveError(resolved.getErrorCode()));
		}

		// 2. 经服务发现直连 ws-server 内部 ccBind 端点（绕过网关）
		try {
			CcLaunchResp launch = callWsCcBind(
					resolved.getAiclawUid(), roomId, resolved.getRoomType(), resolved.getCounterpartUid());
			if (launch == null) {
				return R.fail("CC 助理节点离线或响应超时");
			}
			return R.success(launch);
		} catch (BizException e) {
			return R.fail(e.getMessage());
		} catch (Exception e) {
			log.error("ccLaunch ws ccBind failed: roomId={}, requesterUid={}, uid={}", roomId, requesterUid, uid, e);
			return R.fail("CC 助理节点离线或响应超时");
		}
	}

	/** 把 resolve 的 errorCode 映射为对客户端的中文 msg（契约固定）。 */
	private String mapResolveError(String errorCode) {
		if (errorCode == null) {
			return "无权限";
		}
		return switch (errorCode) {
			case "NO_CC" -> "该房间没有 CC 助理";
			case "AMBIGUOUS" -> "房间有多个 CC 助理，请指定 uid";
			case "UID_NOT_CC" -> "指定的 uid 不是该房间的 CC 助理";
			case "NOT_OWNER" -> "无权限";
			default -> "无权限";
		};
	}

	/**
	 * 服务发现直连 ws-server 内部 ccBind 端点（绕过网关），与旧 ws→im 走 DiscoveryClient + Hutool HTTP 同源。
	 * 节点报错 → 抛 {@link BizException}（msg 即对客户端文案）；服务不可达/解析异常 → 返回 null（由调用方转「离线/超时」）。
	 */
	protected CcLaunchResp callWsCcBind(Long aiclawUid, Long roomId, Integer roomType, Long counterpartUid) {
		String base = resolveWsServiceUrl();
		if (base == null) {
			return null;
		}
		HttpRequest request = HttpRequest.get(base + "/internal/cc-bind")
				.header("X-Internal-Token", internalToken)
				.form("aiclawUid", aiclawUid)
				.form("roomId", roomId)
				.form("roomType", roomType);
		if (counterpartUid != null) {
			request.form("counterpartUid", counterpartUid);
		}
		String body = request.execute().body();
		JSONObject json = JSONUtil.parseObj(body);
		// ws 端点返回标准 R<CcLaunchResp>
		Integer code = json.getInt("code");
		if (code == null || code != R.SUCCESS_CODE) {
			String msg = json.getStr("msg");
			throw new BizException(msg != null && !msg.isBlank() ? msg : "CC 助理节点离线或响应超时");
		}
		return json.getBean("data", CcLaunchResp.class);
	}

	private String resolveWsServiceUrl() {
		try {
			List<ServiceInstance> instances = discoveryClient.getInstances("luohuo-ws-server");
			if (instances != null && !instances.isEmpty()) {
				return instances.get(0).getUri().toString();
			}
		} catch (Exception e) {
			log.error("Failed to resolve WS service: {}", e.getMessage());
		}
		return null;
	}
}
