package com.luohuo.flex.im.core.chat.service.ai.approval;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum AiApprovalRoleEnum {
	VIEWER("viewer"),
	ADMIN("admin");

	private final String code;

	public static AiApprovalRoleEnum parseOrDefault(String role) {
		if (StrUtil.isBlank(role)) {
			return VIEWER;
		}
		for (AiApprovalRoleEnum value : values()) {
			if (value.code.equalsIgnoreCase(role)) {
				return value;
			}
		}
		return VIEWER;
	}
}
