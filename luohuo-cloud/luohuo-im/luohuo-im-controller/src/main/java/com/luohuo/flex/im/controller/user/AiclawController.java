package com.luohuo.flex.im.controller.user;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.ThinkingService;
import com.luohuo.flex.im.core.user.service.AiclawService;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawActivateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawAuthConfirmReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCreateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawPersonaReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawRelationReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawReportTypeReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawThinkingByTriggerReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawActivateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawConversationResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCreateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawFriendResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawListResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI助理管理
 */
@RestController
@RequestMapping("/aiclaw")
@Tag(name = "AI助理管理")
public class AiclawController {

	@Resource
	private AiclawService aiclawService;

	@Resource
	private ThinkingService thinkingService;

	@GetMapping("/thinking/{thinkingId}")
	@Operation(summary = "回看 aiclaw thinking 全文（仅房间成员可见）")
	public R<AiclawThinkingDetailResp> reviewThinking(@PathVariable Long thinkingId) {
		// IDOR 防护：以当前登录用户（caller）为授权主体，校验其为该 thinking 所属房间成员
		return R.success(thinkingService.reviewThinking(thinkingId, ContextUtil.getUid()));
	}

	@PostMapping("/thinking/by-trigger")
	@Operation(summary = "按触发消息批量反查 thinking 元数据（仅房间成员，metadata only）")
	public R<List<AiclawThinkingListItemResp>> listThinkingByTrigger(
			@Valid @RequestBody AiclawThinkingByTriggerReq req) {
		return R.success(thinkingService.listThinkingByTriggerMsgIds(
				req.getRoomId(), ContextUtil.getUid(), req.getTriggerMsgIds()));
	}

	@PostMapping("/create")
	@Operation(summary = "创建AI助理，返回加密激活 token")
	public R<AiclawCreateResp> create(@Valid @RequestBody AiclawCreateReq req) {
		return R.success(aiclawService.create(req, ContextUtil.getUid()));
	}

	@PostMapping("/anyTenant/activate")
	@Operation(summary = "激活AI助理（plugins 调用，无需登录态）")
	public R<AiclawActivateResp> activate(@Valid @RequestBody AiclawActivateReq req) {
		return R.success(aiclawService.activate(req));
	}

	@GetMapping("/list")
	@Operation(summary = "获取AI助理列表")
	public R<List<AiclawListResp>> list() {
		return R.success(aiclawService.list(ContextUtil.getUid()));
	}

	@PostMapping("/report-agent-type")
	@Operation(summary = "aiclaw 连接后上报 agent 类型（REQ-009 #83，仅可上报自身类型）")
	public R<Void> reportAgentType(@RequestBody AiclawReportTypeReq req) {
		// 防伪造：caller 的 uid（来自 aiclaw connectionToken）即被上报方，只能上报自己的类型
		aiclawService.reportAgentType(ContextUtil.getUid(), req.getAgentType());
		return R.success();
	}

	@GetMapping("/{uid}/activation-token")
	@Operation(summary = "获取激活 token（未激活时可查看）")
	public R<AiclawTokenResp> getActivationToken(@PathVariable Long uid) {
		return R.success(aiclawService.getActivationToken(uid, ContextUtil.getUid()));
	}

	@PostMapping("/{uid}/refresh-activation")
	@Operation(summary = "重新生成激活 token（连接 token 也会更新）")
	public R<AiclawTokenResp> refreshActivation(@PathVariable Long uid) {
		return R.success(aiclawService.refreshActivation(uid, ContextUtil.getUid()));
	}

	@PutMapping("/{uid}/profile")
	@Operation(summary = "修改AI助理资料")
	public R<Void> updateProfile(@PathVariable Long uid, @Valid @RequestBody AiclawUpdateReq req) {
		req.setUid(uid);
		aiclawService.updateProfile(req, ContextUtil.getUid());
		return R.success();
	}

	@GetMapping("/{uid}/conversations")
	@Operation(summary = "获取AI助理的对话列表")
	public R<List<AiclawConversationResp>> getConversations(@PathVariable Long uid) {
		return R.success(aiclawService.getConversations(uid, ContextUtil.getUid()));
	}

	@GetMapping("/{uid}/conversations/{friendUid}/messages")
	@Operation(summary = "获取AI助理与某用户的聊天记录")
	public R<CursorPageBaseResp<ChatMessageResp>> getConversationMessages(
			@PathVariable Long uid, @PathVariable Long friendUid, @Valid CursorPageBaseReq pageReq) {
		return R.success(aiclawService.getConversationMessages(uid, friendUid, pageReq, ContextUtil.getUid()));
	}

	@PutMapping("/{uid}/persona")
	@Operation(summary = "设置AI助理对外人设")
	public R<Void> setPersona(@PathVariable Long uid, @Valid @RequestBody AiclawPersonaReq req) {
		aiclawService.setPersona(uid, req.getPublicPersona(), ContextUtil.getUid());
		return R.success();
	}

	@GetMapping("/{uid}/friends")
	@Operation(summary = "获取AI助理好友列表")
	public R<List<AiclawFriendResp>> getFriends(@PathVariable Long uid) {
		return R.success(aiclawService.getFriends(uid, ContextUtil.getUid()));
	}

	@DeleteMapping("/{uid}/friends/{friendUid}")
	@Operation(summary = "移除AI助理好友")
	public R<Void> removeFriend(@PathVariable Long uid, @PathVariable Long friendUid) {
		aiclawService.removeFriend(uid, friendUid, ContextUtil.getUid());
		return R.success();
	}

	@PutMapping("/{uid}/friends/{friendUid}/relation")
	@Operation(summary = "设置AI助理好友关系说明")
	public R<Void> setRelation(@PathVariable Long uid, @PathVariable Long friendUid,
							   @Valid @RequestBody AiclawRelationReq req) {
		aiclawService.setRelation(uid, friendUid, req.getRelationDesc(), ContextUtil.getUid());
		return R.success();
	}

	@PostMapping("/{uid}/deactivate")
	@Operation(summary = "停用AI助理（触发24h注销）")
	public R<Void> deactivate(@PathVariable Long uid) {
		aiclawService.deactivate(uid, ContextUtil.getUid());
		return R.success();
	}

	@PostMapping("/{uid}/restore")
	@Operation(summary = "恢复AI助理（24h内）")
	public R<Void> restore(@PathVariable Long uid) {
		aiclawService.restore(uid, ContextUtil.getUid());
		return R.success();
	}

	@PostMapping("/{uid}/auth-confirm")
	@Operation(summary = "机器码变更授权确认")
	public R<Void> authConfirm(@PathVariable Long uid, @Valid @RequestBody AiclawAuthConfirmReq req) {
		aiclawService.authConfirm(uid, req, ContextUtil.getUid());
		return R.success();
	}
}
