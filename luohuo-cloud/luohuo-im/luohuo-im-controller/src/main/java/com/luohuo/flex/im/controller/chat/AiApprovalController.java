package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.base.R;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.ai.approval.AiApprovalRequestRecord;
import com.luohuo.flex.im.core.chat.service.ai.approval.AiApprovalService;
import com.luohuo.flex.im.core.chat.service.ai.audit.AiAuditLogService;
import com.luohuo.flex.im.domain.entity.ai.AiAuditLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI owner 授权审批接口
 */
@RestController
@RequestMapping("/chat/ai/approval")
@Tag(name = "AI授权审批")
@RequiredArgsConstructor
public class AiApprovalController {

	private final AiApprovalService aiApprovalService;
	private final AiAuditLogService aiAuditLogService;

	@GetMapping("/pending")
	@Operation(summary = "查询 owner 待审批列表")
	public R<List<AiApprovalRequestRecord>> pending(@RequestParam(required = false) Long aiUserId,
											 @RequestParam(defaultValue = "20") Integer limit) {
		Long ownerUid = ContextUtil.getUid();
		return R.success(aiApprovalService.listPending(ownerUid, aiUserId, limit));
	}

	@PostMapping("/approve")
	@Operation(summary = "审批通过")
	public R<AiApprovalRequestRecord> approve(@Valid @RequestBody ApprovalDecisionReq req) {
		Long ownerUid = ContextUtil.getUid();
		return R.success(aiApprovalService.approve(ownerUid, req.getRequestId(), req.getRole(), req.getRewrittenText()));
	}

	@PostMapping("/reject")
	@Operation(summary = "审批拒绝")
	public R<AiApprovalRequestRecord> reject(@Valid @RequestBody ApprovalDecisionReq req) {
		Long ownerUid = ContextUtil.getUid();
		return R.success(aiApprovalService.reject(ownerUid, req.getRequestId(), req.getReason()));
	}

	@GetMapping("/audit")
	@Operation(summary = "查询审计日志列表")
	public R<Page<AiAuditLog>> auditList(@RequestParam(required = false) Long aiUserId,
										  @RequestParam(defaultValue = "1") Integer page,
										  @RequestParam(defaultValue = "20") Integer size) {
		Long ownerUid = ContextUtil.getUid();
		return R.success(aiAuditLogService.listAuditLogs(ownerUid, aiUserId, page, size));
	}

	@Data
	public static class ApprovalDecisionReq {
		@NotBlank(message = "requestId不能为空")
		private String requestId;
		private String role;
		private String reason;
		private String rewrittenText;
	}
}
