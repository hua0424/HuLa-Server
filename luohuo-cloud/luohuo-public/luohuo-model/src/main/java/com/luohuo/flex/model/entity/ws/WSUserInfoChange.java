package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资料变更推送（#192：低延迟失效通知，丢失由前端常规拉取兜底）
 * profile：本人 name/avatar/resume 变更，扇出到反向好友 + 本人
 * remark：好友备注变更，仅推给设置人本人
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSUserInfoChange {

	public static final String PROFILE = "profile";
	public static final String REMARK = "remark";

	@Schema(description = "资料发生变更的用户 UID（String 避免 JS 精度丢失）")
	private String uid;

	@Schema(description = "变更类型：profile=资料变更, remark=好友备注变更")
	private String changeType;
}
