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
public class AiApprovalGrantRecord implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	private Long aiUserId;
	private Long ownerUid;
	private Long requesterUid;
	private String role;
	private Long approvedAt;
	private Long approvedBy;
}
