package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 激活AI助理响应（返回 uid + 连接 token，plugins 保存到本地 credentials）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawActivateResp implements Serializable {

	@Schema(description = "AI助理UID")
	private Long uid;

	@Schema(description = "WS 连接凭证")
	private String connectionToken;
}
