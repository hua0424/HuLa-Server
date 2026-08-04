package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw 上报主机信息请求（aichatoverview#193）
 *
 * <p>三个字段全部可空：按字段合并入 im_aiclaw.adapter_config JSON，
 * blank 字段保留旧值、非 blank 覆写。aiclaw 身份来自鉴权上下文
 * （connectionToken → uid），不取自请求体，只能上报自己。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawReportHostInfoReq implements Serializable {

	@Schema(description = "主机名（可空，空则保留旧值）")
	private String hostname;

	@Schema(description = "主机 IP（可空，空则保留旧值）")
	private String ip;

	@Schema(description = "agent workspace 根目录（可空，空则保留旧值；server 据此推导 owner/dm 目录）")
	private String workspaceBase;
}
