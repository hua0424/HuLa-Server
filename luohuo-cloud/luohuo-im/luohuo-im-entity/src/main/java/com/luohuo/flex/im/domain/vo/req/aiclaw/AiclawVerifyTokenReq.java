package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 校验 aiclaw connectionToken 请求（gateway 缓存缺失时回源调用，无需登录态）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawVerifyTokenReq implements Serializable {

	@NotBlank(message = "connectionToken 不能为空")
	@Schema(description = "aiclaw WS 连接 token（明文）")
	private String token;
}
