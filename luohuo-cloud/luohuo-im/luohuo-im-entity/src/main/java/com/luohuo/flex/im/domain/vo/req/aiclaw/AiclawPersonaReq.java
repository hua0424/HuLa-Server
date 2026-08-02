package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 设置 aiclaw 人设请求
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiclawPersonaReq implements Serializable {

	@NotNull(message = "publicPersona 不能为 null")
	@Size(max = 500, message = "人设不能超过 500 字符")
	@Schema(description = "人设文本，空串表示清除")
	private String publicPersona;
}
