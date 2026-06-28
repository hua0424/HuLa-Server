package com.luohuo.flex.ws.websocket.processor;

import cn.hutool.json.JSONUtil;
import com.luohuo.flex.model.entity.ws.CcBindResultDTO;
import com.luohuo.flex.model.enums.WSReqTypeEnum;
import com.luohuo.flex.model.ws.WSBaseReq;
import com.luohuo.flex.ws.service.CcBindService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * REQ-010 S9: CC 绑定结果处理器（node→server）。
 *
 * <p>处理目标 CC aiclaw 的 node 发来的 {@code CC_BIND_RESULT}（type=23），
 * 解析 {@link CcBindResultDTO} 后按 requestId 完成 {@link CcBindService} 中的待回执 future。
 * 与 {@code ThinkingProcessor} 同为「node→server」处理器，经 {@code MessageHandlerChain}
 * 自动注册（Spring {@code List<MessageProcessor>} + {@link Order}）。</p>
 *
 * @author developer
 */
@Slf4j
@Order(24)
@Component
public class CcBindResultProcessor implements MessageProcessor {

	@Resource
	private CcBindService ccBindService;

	@Override
	public boolean supports(WSBaseReq req) {
		return WSReqTypeEnum.CC_BIND_RESULT.eq(req.getType());
	}

	@Override
	public void process(WebSocketSession session, Long uid, WSBaseReq payload) {
		CcBindResultDTO result = JSONUtil.toBean(payload.getData(), CcBindResultDTO.class);
		if (result == null || result.getRequestId() == null) {
			log.warn("CC_BIND_RESULT missing requestId: uid={}", uid);
			return;
		}
		log.debug("CC_BIND_RESULT received: aiclawUid={}, requestId={}, hasError={}",
				uid, result.getRequestId(), result.getError() != null);
		ccBindService.complete(result);
	}
}
