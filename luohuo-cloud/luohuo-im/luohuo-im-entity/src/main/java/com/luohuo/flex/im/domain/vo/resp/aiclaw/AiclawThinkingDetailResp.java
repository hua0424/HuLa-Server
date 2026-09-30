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

	@Schema(description = "thinking ID（string 避免客户端大整数丢精度）")
	private String thinkingId;

	@Schema(description = "记录所属房间 ID")
	private String roomId;

	@Schema(description = "产生记录的助理 UID")
	private String aiclawUid;

	@Schema(description = "触发消息 ID；无触发时为 null")
	private String triggerMsgId;

	@Schema(description = "本轮执行关联 ID；旧记录为 null，不授予写入权限")
	private String clientRunId;

	@Schema(description = "完整思考文本")
	private String content;

	@Schema(description = "状态：0=进行中 1=成功 2=错误 3=超时 4=超长截断")
	private Integer status;

	@Schema(description = "处理耗时（毫秒）")
	private Integer durationMs;
}
