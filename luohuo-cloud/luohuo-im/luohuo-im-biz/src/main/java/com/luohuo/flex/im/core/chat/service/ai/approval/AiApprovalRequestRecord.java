package com.luohuo.flex.im.core.chat.service.ai.approval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiApprovalRequestRecord implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	private String requestId;
	private Long aiUserId;
	private Long ownerUid;
	private Long requesterUid;
	private String status;
	private String role;
	private String reason;
	private Long createdAt;
	private Long expireAt;
	private Long decidedAt;
	private Long decidedBy;
	/**
	 * 用户原始请求文本
	 */
	private String originalText;
	/**
	 * owner 审批时可能改写的文本
	 */
	private String finalText;
}
