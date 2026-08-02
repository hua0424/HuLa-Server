package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
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

	@Size(max = 20, message = "名称不能超过 20 字符")
	@Schema(description = "名称")
	private String name;

	@Schema(description = "头像URL")
	private String avatar;

	@Size(max = 200, message = "简介不能超过 200 字符")
	@Schema(description = "简介")
	private String description;
}
