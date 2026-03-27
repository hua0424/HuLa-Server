package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI助理列表项响应
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawListResp implements Serializable {

	@Schema(description = "AI助理UID")
	private Long uid;

	@Schema(description = "名称")
	private String name;

	@Schema(description = "头像URL")
	private String avatar;

	@Schema(description = "简介")
	private String description;

	@Schema(description = "授权状态 0=未激活 1=已激活 2=已停用")
	private Integer authStatus;

	@Schema(description = "claw 类型")
	private String adapterType;

	@Schema(description = "对外人设（系统 prompt），可为空")
	private String publicPersona;

	@Schema(description = "创建时间")
	private LocalDateTime createTime;
}
