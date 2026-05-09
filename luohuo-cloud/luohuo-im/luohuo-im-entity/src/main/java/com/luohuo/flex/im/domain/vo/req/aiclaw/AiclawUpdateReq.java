package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 修改AI助理资料请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawUpdateReq implements Serializable {

	@Schema(description = "AI助理UID（由路径参数设置）")
	private Long uid;

	@Schema(description = "名称")
	private String name;

	@Schema(description = "头像URL")
	private String avatar;

	@Schema(description = "简介")
	private String description;
}
