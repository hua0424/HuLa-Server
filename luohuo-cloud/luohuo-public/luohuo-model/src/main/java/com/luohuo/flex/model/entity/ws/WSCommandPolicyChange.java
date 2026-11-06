package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * aichatoverview#504 命令权限变更推送（失效通知；收到后应重拉权限视图/目录）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSCommandPolicyChange {

	@Schema(description = "aiclaw UID（String 避免 JS 精度丢失）")
	private String aiclawUid;

	@Schema(description = "作用域：0=默认规则；>0=群 roomId")
	private String roomId;

	@Schema(description = "本次变更的能力 id 列表")
	private List<String> capabilityIds;
}
