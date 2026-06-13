package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-004 [S7]: aiclaw thinking 全文回看响应。
 *
 * <p>思考条本身只持有状态（state-only），用户点开时按需拉取全文。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawThinkingDetailResp implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "完整思考文本")
	private String content;

	@Schema(description = "状态：0=进行中 1=成功 2=错误 3=超时 4=超长截断")
	private Integer status;

	@Schema(description = "处理耗时（毫秒）")
	private Integer durationMs;
}
