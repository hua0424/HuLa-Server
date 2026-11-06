package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * aichatoverview#504 命令权限更新请求（仅主人）。
 *
 * <p>语义冻结（#504 issuecomment-6677394062）：
 * 群覆盖 mode=list 时名单外用户在该群内=不可调用，不回落继承默认。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aichatoverview#504 命令权限更新请求")
public class AiclawCommandPolicyUpdateReq {

	@Schema(description = "Agent 的 uid")
	private Long aiclawUid;

	@Schema(description = "群 room_id；空=默认规则；>0=群覆盖")
	private Long roomId;

	@Schema(description = "权限项")
	private List<Item> items;

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	@Schema(description = "aichatoverview#504 单能力权限项")
	public static class Item {

		@Schema(description = "能力标识（精确 id，如 skill:run503-native-proof / session.reset）")
		private String capabilityId;

		@Schema(description = "open=允许调用，closed=不开放，approval_required=需主人审批")
		private String state;

		@Schema(description = "群覆盖名单模式：all=全体成员，list=指定名单；默认规则行忽略")
		private String mode;

		@Schema(description = "mode=list 时的 uid 数组；其余情况忽略")
		private List<Long> allowedUids;

		@Schema(description = "读到版本（更新必填，新建可空）；失配拒绝")
		private Integer version;
	}
}
