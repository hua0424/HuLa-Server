package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建AI助理请求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawCreateReq implements Serializable {

	@NotBlank(message = "名称不能为空")
	@Schema(description = "AI助理名称")
	private String name;

	@Schema(description = "AI助理头像URL")
	private String avatar;

	@Schema(description = "AI助理简介")
	private String description;
}
