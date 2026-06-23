package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw 连接后上报 agent 类型请求（REQ-009 #83）
 *
 * <p>仅上报类型本身；aiclaw 身份来自鉴权上下文（connectionToken → uid），
 * 不取自请求体，防止伪造他人 aiclaw 的类型。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawReportTypeReq implements Serializable {

	@Schema(description = "agent 后端类型，如 openclaw / opencode")
	private String agentType;
}
