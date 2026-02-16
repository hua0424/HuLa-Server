package com.luohuo.flex.im.core.chat.service.ai.approval;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum AiApprovalStatusEnum {
	PENDING("pending"),
	APPROVED("approved"),
	REJECTED("rejected");

	private final String code;
}
