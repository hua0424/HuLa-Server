package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 机器码变更授权确认请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawAuthConfirmReq implements Serializable {

	@NotNull(message = "approved不能为空")
	@Schema(description = "是否同意授权")
	private Boolean approved;
}
