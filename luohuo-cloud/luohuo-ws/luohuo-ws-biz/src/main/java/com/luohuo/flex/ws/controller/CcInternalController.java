package com.luohuo.flex.ws.controller;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.base.R;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.model.entity.ws.CcLaunchResp;
import com.luohuo.flex.ws.service.CcBindService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REQ-010 S9: CC 绑定<b>内部</b>端点（ws-server，WebFlux）—— 仅供 im-server 经服务发现调用。
 *
 * <p>FINAL 架构下鉴权/解析全部在 im 侧（owner-auth + CC aiclaw resolve）完成；本端点<b>不做</b>
 * 任何鉴权，只暴露 {@link CcBindService#ccBind}（requestId 关联的 WS 往返）。因此<b>绝不能</b>被
 * 外部客户端经网关直达。</p>
 *
 * <p><b>暴露面与防护</b>：网关把 {@code /ws/**} StripPrefix=1 整体路由到本服务，故本端点
 * （ws 路径 {@code /internal/cc-bind}）理论上可经 {@code /ws/internal/cc-bind} 被外部触达。
 * im→ws 调用走 DiscoveryClient <b>直连实例、绕过网关</b>（与旧 ws→im /cc-resolve 同源），并携带
 * 共享秘钥头 {@code X-Internal-Token}；网关 {@code TokenContextFilter} 不会注入该头，故外部经网关
 * 的请求拿不到正确秘钥 → 401-style 拒绝。这是当前路由约定下「最小暴露」的可行手段；若需更强隔离
 * （独立内网端口 / 网关显式排除 {@code /ws/internal/**}），属配置层决策，已在交付报告中标注待 manager 定夺。</p>
 *
 * @author developer
 */
@Slf4j
@RestController
@RequestMapping("/internal")
public class CcInternalController {

	@Resource
	private CcBindService ccBindService;

	/** 与 im 侧约定的共享秘钥（默认值仅 dev 兜底，prod 应在 Nacos 覆盖）。 */
	@Value("${aichat.cc.internal-token:aichat-cc-internal}")
	private String internalToken;

	/**
	 * 内部 ccBind：im 已完成 resolve + owner 鉴权，这里只做 requestId 关联的 WS 往返。
	 *
	 * @param token          X-Internal-Token 头（必须与配置一致，否则视为外部非法访问）
	 * @param aiclawUid      目标 CC aiclaw uid（im 已解析 + 鉴权）
	 * @param roomId         房间 id
	 * @param roomType       房间类型 1=群聊 2=单聊
	 * @param counterpartUid 单聊对端 uid；群聊为 null
	 */
	@GetMapping("/cc-bind")
	public Mono<R<CcLaunchResp>> ccBind(@RequestHeader(value = "X-Internal-Token", required = false) String token,
										@RequestParam Long aiclawUid,
										@RequestParam Long roomId,
										@RequestParam Integer roomType,
										@RequestParam(required = false) Long counterpartUid) {
		// 内部秘钥校验：外部经网关无法携带正确秘钥 → 拒绝（防越权直达）
		if (StrUtil.isBlank(token) || !token.equals(internalToken)) {
			log.warn("CcInternalController rejected: missing/invalid X-Internal-Token (roomId={}, aiclawUid={})", roomId, aiclawUid);
			return Mono.just(R.fail("无权限"));
		}
		// 阻塞调用（future.get 等待 node 回执）放到 boundedElastic，绝不占用 Netty event loop。
		return Mono.fromCallable(() -> R.success(ccBindService.ccBind(aiclawUid, roomId, roomType, counterpartUid)))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(BizException.class, e -> Mono.just(R.fail(e.getMessage())))
				.onErrorResume(e -> {
					log.error("internal ccBind unexpected error: aiclawUid={}, roomId={}", aiclawUid, roomId, e);
					return Mono.just(R.fail("CC 助理节点离线或响应超时"));
				});
	}
}
