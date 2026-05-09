package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luohuo.basic.base.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI助理扩展表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "im_aiclaw", autoResultMap = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "AI助理扩展表")
public class Aiclaw extends Entity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * 关联 im_user.id
	 */
	@TableField("uid")
	private Long uid;

	/**
	 * 拥有者用户ID
	 */
	@TableField("owner_uid")
	private Long ownerUid;

	/**
	 * bcrypt hash 后的 token
	 */
	@TableField("token_hash")
	private String tokenHash;

	/**
	 * token 前8位，用于快速匹配
	 */
	@TableField("token_prefix")
	private String tokenPrefix;

	/**
	 * plugins 设备标识（UUID）
	 */
	@TableField("machine_code")
	private String machineCode;

	/**
	 * 授权状态 0=未激活 1=已激活 2=已停用
	 */
	@TableField("auth_status")
	private Integer authStatus;

	/**
	 * claw 类型
	 */
	@TableField("adapter_type")
	private String adapterType;

	/**
	 * 连接参数 JSON
	 */
	@TableField("adapter_config")
	private String adapterConfig;

	/**
	 * 对外人设（系统 prompt），可为空
	 */
	@TableField("public_persona")
	private String publicPersona;

	/**
	 * 停用时间（用于延迟注销）
	 */
	@TableField("deactivated_at")
	private LocalDateTime deactivatedAt;

	/**
	 * 租户id
	 */
	@Schema(description = "租户id")
	private Long tenantId;
}
