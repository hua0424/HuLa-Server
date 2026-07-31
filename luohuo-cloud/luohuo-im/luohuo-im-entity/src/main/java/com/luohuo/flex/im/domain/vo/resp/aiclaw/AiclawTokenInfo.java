package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw token 校验后返回的身份信息（gateway 据此重建 Redis 缓存）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawTokenInfo implements Serializable {

	@Schema(description = "AI助理UID")
	private Long uid;

	@Schema(description = "拥有者用户ID")
	private Long ownerUid;

	@Schema(description = "授权状态 0=未激活 1=已激活 2=已停用")
	private Integer authStatus;

	@Schema(description = "租户ID")
	private Long tenantId;

	@Schema(description = "plugins 设备标识")
	private String machineCode;
}
