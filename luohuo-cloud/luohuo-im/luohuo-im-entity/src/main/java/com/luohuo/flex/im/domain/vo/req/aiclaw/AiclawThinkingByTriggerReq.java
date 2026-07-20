package com.luohuo.flex.im.domain.vo.req.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 按触发消息批量反查 thinking 元数据请求（issue #180）。
 *
 * <p>供客户端一次性反查一批已渲染消息各自对应的 thinking 元数据（metadata only），
 * 替代原按房间游标翻页的归档列表。授权主体为当前登录用户（caller），房间成员闸门在
 * service 层校验，不在此 DTO 体现。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawThinkingByTriggerReq implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "房间 ID（授权主体在该房间的成员校验在 service 层进行）")
	@NotNull
	private Long roomId;

	@Schema(description = "触发消息 ID 列表（批量反查，单次上限 100 条）")
	@NotEmpty
	@Size(max = 100, message = "triggerMsgIds 上限 100")
	private List<Long> triggerMsgIds;
}
