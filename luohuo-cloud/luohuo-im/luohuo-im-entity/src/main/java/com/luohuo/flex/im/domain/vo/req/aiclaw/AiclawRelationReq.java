package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 设置 aiclaw 好友关系说明请求
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiclawRelationReq implements Serializable {

	@NotNull(message = "relationDesc 不能为 null")
	@Size(max = 200, message = "关系说明不能超过 200 字符")
	@Schema(description = "关系说明文本，空串表示清除")
	private String relationDesc;
}
