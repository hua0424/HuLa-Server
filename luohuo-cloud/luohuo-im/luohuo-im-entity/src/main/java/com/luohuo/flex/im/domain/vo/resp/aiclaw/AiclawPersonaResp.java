package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw 自作用域人设响应（GET /aiclaw/self/persona）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawPersonaResp implements Serializable {

	@Schema(description = "人设（系统 prompt），null 表示未设置")
	private String publicPersona;
}
