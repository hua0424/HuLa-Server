package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aichatoverview#484 (T3)：aiclaw 群配置「当前事实」的窄读响应。
 *
 * <p>合同要点（设计 v1 §3.2）：</p>
 * <ul>
 *   <li>{@code outcome=PRESENT} 时为可用快照；{@code configured=false} 表示「没有配置行」，
 *       按既有默认 approved=false 处理，<b>不是</b>「配置被删除」。</li>
 *   <li>{@code outcome=NOT_ACCESSIBLE} 与「群不存在 / aiclaw 不在群 / 调用者无权」同形，
 *       不额外披露存在性；只有该结论才允许消费方精准退场。</li>
 *   <li>其余错误（超时/5xx/DB/格式错）由传输层表达，<b>不得</b>折成本结论。</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawGroupConfigStateResp implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String OUTCOME_PRESENT = "PRESENT";
	public static final String OUTCOME_NOT_ACCESSIBLE = "NOT_ACCESSIBLE";

	@Schema(description = "读取结论：PRESENT=可用当前事实；NOT_ACCESSIBLE=群不存在/aiclaw不在群/无权（同形）")
	private String outcome;

	@Schema(description = "是否为已落库的配置行；false 时下列字段为服务端默认值")
	private Boolean configured;

	@Schema(description = "配置内容（configured=false 时也为默认值，便于消费方统一处理）")
	private AiclawGroupConfigResp config;

	/**
	 * aichatoverview#485 T4：本次读取时的授权代次（不透明、不可由客户端构造）。
	 *
	 * <p>它不是长期有效的执行许可：消费方只能把它当作「这份输入属于哪个授权上下文」的标记，随该输入一起
	 * 传递；server 每次准入都用当前事实重新推导代次并比对，不等即拒绝，因此撤权后重新批准不会让旧输入复活。</p>
	 */
	@Schema(description = "授权代次（opaque）：输入入队时携带，server 用当前事实比对；不等即拒")
	private String authorizationEpoch;
}
