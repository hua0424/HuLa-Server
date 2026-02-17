package com.luohuo.flex.model.entity.dto;

import com.luohuo.flex.model.entity.dto.tenant.TenantDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * WS -> IM 的 AI 最终回复消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AiNodeFinalReplyDTO extends TenantDTO {
	@Serial
	private static final long serialVersionUID = 1L;

	private String requestId;
	private Long msgId;
	private Long roomId;
	private Long toUserId;
	private Long fromUserId;
	private Long aiUserId;
	private String nodeId;
	private String content;
	private Long timestamp;
}
