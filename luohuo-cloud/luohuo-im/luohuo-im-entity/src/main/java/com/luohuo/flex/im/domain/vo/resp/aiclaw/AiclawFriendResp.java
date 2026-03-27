package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw 好友列表项响应（含 relationDesc）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawFriendResp implements Serializable {

	@Schema(description = "好友用户ID")
	private Long uid;

	@Schema(description = "好友昵称")
	private String name;

	@Schema(description = "好友头像")
	private String avatar;

	@Schema(description = "好友账号")
	private String account;

	@Schema(description = "在线状态 1在线 2离线")
	private Integer activeStatus;

	@Schema(description = "用户类型 1系统 2机器人 3普通 4AI助理")
	private Integer userType;

	@Schema(description = "关系说明，null 表示未设置")
	private String relationDesc;
}
