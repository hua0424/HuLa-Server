package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 激活 token 响应（refresh-activation / reset-token / activation-token 共用）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawTokenResp implements Serializable {

	@Schema(description = "加密激活 token")
	private String activationToken;
}
