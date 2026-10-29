package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * aiclaw 群配置响应
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw 群配置响应")
public class AiclawGroupConfigResp {

	@Schema(description = "aiclaw 的 uid")
	private Long aiclawUid;

	@Schema(description = "群聊 room_id")
	private Long roomId;

	@Schema(description = "频率限制（条/分钟），0=无限制")
	private Integer rateLimitPerMinute;

	@Schema(description = "是否需要 @ 触发：0=否，1=是")
	private Integer mentionRequired;

	@Schema(description = "每日发言上限")
	private Integer dailyLimit;

	@Schema(description = "是否响应其他 aiclaw：0=否，1=是")
	private Integer respondToAi;

	@Schema(description = "REQ-009#82: 是否已批准在该群响应：0=未批准/沉默，1=已批准")
	private Integer approved;

	@Schema(description = "REQ-009#82: 工作目录（可空；NULL=plugins 自行派生默认目录）")
	private String workspaceDir;

	@Schema(description = "REQ-009#82: 群的可读群号（plugins 据此派生工作目录 groupkey）")
	private String account;

	/**
	 * aichatoverview#485 (T4)：读取这一行时看到的授权代次。
	 *
	 * <p>它不是权限本身，只是「这次输入属于哪个授权上下文」的标记：plugins 把它随输入冻结、
	 * 随 START 回传，server 每次用当前事实重新推导并比对，不等即拒。由此撤权前入队的输入
	 * 不会因为「之后又被批准」而重新变得可执行。
	 */
	@Schema(description = "授权代次：读取时看到的授权上下文的指纹（非权限，server 每次重算比对）")
	private String authorizationEpoch;
}
