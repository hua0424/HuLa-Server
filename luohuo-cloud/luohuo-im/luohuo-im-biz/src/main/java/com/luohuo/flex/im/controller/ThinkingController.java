package com.luohuo.flex.im.controller;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.flex.model.entity.ws.WSThinkingStart;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Internal WS → IM thinking API; external requests must never reach this endpoint. */
@RestController
@RequestMapping("/thinking")
public class ThinkingController {

	@Resource
	private ThinkingInternalAuth internalAuth;
	@Resource
	private ThinkingService thinkingService;

	@PostMapping("/start")
	public R<Long> start(@RequestBody WSThinkingStart req, HttpServletRequest request) {
		Long actor = internalAuth.require(request);
		Long room = Long.valueOf(req.getRoomId());
		Long trigger = req.getTriggerMsgId() == null ? null : Long.valueOf(req.getTriggerMsgId());
		return R.success(thinkingService.create(actor, room, trigger));
	}

	@PostMapping("/end")
	public R<Boolean> end(@RequestBody WSThinkingEnd req, HttpServletRequest request) {
		Long actor = internalAuth.require(request);
		Long room = Long.valueOf(req.getRoomId());
		return R.success(thinkingService.finalize(Long.valueOf(req.getThinkingId()), actor, room,
				req.getContent(), req.getDurationMs(), req.getStatus(), req.getError()));
	}

	@PostMapping("/error")
	public R<Boolean> error(@RequestBody WSThinkingEnd req, HttpServletRequest request) {
		boolean timeout = "true".equals(request.getHeader(ThinkingInternalAuth.SERVICE_TIMEOUT));
		Long actor = timeout ? internalAuth.requireTimeout(request) : internalAuth.require(request);
		Long room = Long.valueOf(req.getRoomId());
		return R.success(thinkingService.markError(Long.valueOf(req.getThinkingId()), actor, room,
				req.getError(), timeout));
	}

	@GetMapping("/{thinkingId}/terminal")
	public R<Boolean> isTerminal(@PathVariable Long thinkingId, @RequestParam Long roomId,
			HttpServletRequest request) {
		Long actor = internalAuth.requireTimeout(request);
		return R.success(thinkingService.isOwnedTerminal(thinkingId, actor, roomId));
	}

	@GetMapping("/room/{roomId}/members")
	public R<List<Long>> getRoomMembers(@PathVariable Long roomId, HttpServletRequest request) {
		boolean timeout = "true".equals(request.getHeader(ThinkingInternalAuth.SERVICE_TIMEOUT));
		Long actor = timeout ? internalAuth.requireTimeout(request) : internalAuth.require(request);
		if (!timeout) thinkingService.requireActiveAgent(actor, roomId, ContextUtil.getTenantId());
		return R.success(thinkingService.currentMemberUids(roomId, ContextUtil.getTenantId()));
	}
}
