package com.luohuo.flex.im.domain.vo.resp.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-010 S4: aiclaw 群成员列表的精简响应。
 *
 * <p>给 AI agent（经 aiclaw token 鉴权）的干净 shape：显式 {@code online} 布尔，
 * 而非裸 activeStatus int，避免 agent 侧再去理解枚举语义。</p>
 *
 * @author developer
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawMemberResp implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "用户UID")
	private String uid;

	@Schema(description = "用户昵称（群昵称优先，回退用户名）")
	private String name;

	@Schema(description = "账号")
	private String account;

	@Schema(description = "是否在线")
	private Boolean online;

	@Schema(description = "群角色ID")
	private Integer roleId;
}
