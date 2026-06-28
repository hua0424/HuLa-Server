package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-010 S9: CC 启动命令的对客户端响应。
 *
 * <p>{@code GET /im/room/aiclaw/cc-launch} 成功时的 {@code R.data}（im 解析+鉴权后转 ws ccBind）。
 * 仅该 CC aiclaw 的 owner 可拿到。</p>
 *
 * @author developer
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CcLaunchResp implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "owner 启动命令")
	private String launchCommand;

	@Schema(description = "工作区目录")
	private String workspaceDir;
}
