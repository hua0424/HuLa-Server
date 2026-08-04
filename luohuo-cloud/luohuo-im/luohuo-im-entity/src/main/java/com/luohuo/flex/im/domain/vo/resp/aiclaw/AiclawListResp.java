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

	@Schema(description = "在线状态(运行时,来自 presence ZSET) 1=在线 2=离线")
	private Integer activeStatus;

	@Schema(description = "claw 类型")
	private String adapterType;

	@Schema(description = "对外人设（系统 prompt），可为空")
	private String publicPersona;

	@Schema(description = "主机名（#193，来自 aiclaw 上报；null=未上报）")
	private String hostname;

	@Schema(description = "主机 IP（#193，来自 aiclaw 上报；null=未上报）")
	private String ip;

	@Schema(description = "owner 私聊 workspace 目录（#193，server 由 workspaceBase 推导：workspaceBase/uid/owner；null=未上报 workspaceBase）")
	private String ownerWorkspaceDir;

	@Schema(description = "创建时间")
	private LocalDateTime createTime;
}
