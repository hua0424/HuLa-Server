package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-010 S9: node→server 的 CC 绑定结果回执载荷（{@code CC_BIND_RESULT} 的 data）。
 *
 * <p>成功：{@code launchCommand} + {@code workspaceDir} 非空，{@code error} 为空。
 * 失败：{@code error} 非空（node 生成失败原因）。</p>
 *
 * @author developer
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CcBindResultDTO implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "关联ID（UUID），与请求一致")
	private String requestId;

	@Schema(description = "owner 启动命令（成功时非空）")
	private String launchCommand;

	@Schema(description = "工作区目录（成功时非空）")
	private String workspaceDir;

	@Schema(description = "错误信息（失败时非空）")
	private String error;
}
