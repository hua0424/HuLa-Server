package com.luohuo.flex.im.domain.vo.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @Date 2025/02/19 10:37
 * @Description 群基础信息
 */
@Data
public class RoomInfoReq {

	@NotNull(message = "请选择群聊")
	private Long id;

	// #202: 改名路径上限与建群拉齐为 32（原 max=10）；@NotNull+@Size(min=1) 语义覆盖原 @NotEmpty（null/空串均拒）
	@NotNull(message = "群名称不可为null")
	@Size(min = 1, max = 32, message = "群名称长度必须在1到32个字符之间")
	@Schema(description ="群名称")
	private String name;

	@NotEmpty(message = "群头像不能为空")
	@Schema(description ="群头像")
	private String avatar;

	@Schema(description ="是否允许扫码直接进群")
	private Boolean allowScanEnter;
}
