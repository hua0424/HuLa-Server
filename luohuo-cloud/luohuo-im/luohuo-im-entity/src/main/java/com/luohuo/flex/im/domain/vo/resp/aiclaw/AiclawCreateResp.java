package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建AI助理响应（含加密激活 token）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawCreateResp implements Serializable {

	@Schema(description = "AI助理UID")
	private Long uid;

	@Schema(description = "AI助理名称")
	private String name;

	@Schema(description = "加密激活 token")
	private String activationToken;
}
