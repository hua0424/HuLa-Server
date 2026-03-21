package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 激活AI助理请求（plugins 调用，无需登录态）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawActivateReq implements Serializable {

	@NotBlank(message = "激活码不能为空")
	@Schema(description = "加密的激活 token")
	private String activationToken;

	@NotBlank(message = "机器码不能为空")
	@Schema(description = "plugins 机器码")
	private String machineCode;
}
