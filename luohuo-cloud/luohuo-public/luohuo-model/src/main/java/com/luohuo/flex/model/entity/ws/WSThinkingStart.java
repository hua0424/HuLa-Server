package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * thinking 开始推送
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WSThinkingStart {

	@Schema(description = "发送者 UID（String 避免 JS 精度丢失）")
	private String fromUid;

	@Schema(description = "房间 ID（String 避免 JS 精度丢失）")
	private String roomId;

	@Schema(description = "触发本次 thinking 的消息 ID（String 避免 JS 精度丢失）")
	private String triggerMsgId;

	@Schema(description = "thinking 记录 ID（server 生成，出参时携带）")
	private String thinkingId;

	@Schema(description = "本轮执行 ID（仅关联，不授权；START 回执原样回显）")
	private String clientRunId;

	/**
	 * aichatoverview#485 T4：输入入队时可信读取到的授权代次。
	 *
	 * <p>客户端的自报值不能作为授权证明，它只用来声明「这条输入属于哪个授权上下文」：server 会把它与
	 * 当前事实推导出的代次比对，不等即拒（见 {@code GroupRunAdmissionService}）。缺省表示调用方没有
	 * 可信来源，此时只做当前事实判定。</p>
	 */
	@Schema(description = "输入入队时可信读取到的授权代次（aichatoverview#485）；缺省表示无可信来源")
	private String authorizationEpoch;
}
