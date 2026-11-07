package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * aichatoverview#505 命令审批事件。
 *
 * <p>request 事件只推给主人；result 事件只推给发起人，用于立即解除当前房间输入框等待。
 * 审批状态与真实执行状态分离，result 不代表命令已执行完成。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSCommandApproval {

	@Schema(description = "命令 requestId")
	private String requestId;

	@Schema(description = "群 roomId 或私聊房间 id")
	private String roomId;

	@Schema(description = "Agent uid（String 避免 JS 精度丢失）")
	private String aiclawUid;

	@Schema(description = "能力 id")
	private String capabilityId;

	@Schema(description = "发起人 uid")
	private String callerUid;

	@Schema(description = "主人 uid")
	private String ownerUid;

	@Schema(description = "pending / approved / rejected / expired / invalidated")
	private String approvalStatus;

	@Schema(description = "审批截止时间（epoch millis）")
	private Long expiresAt;

	@Schema(description = "失效/拒绝原因码（安全白名单）")
	private String error;
}
