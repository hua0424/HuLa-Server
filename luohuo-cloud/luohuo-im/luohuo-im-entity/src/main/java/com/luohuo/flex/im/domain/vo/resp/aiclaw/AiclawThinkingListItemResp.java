package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * REQ-XXX：房间 thinking 归档列表项（元数据 only）。
 *
 * <p>供客户端 thinking 抽屉懒加载历史使用：列表只带元数据，<b>不含全文 content</b>
 * （content 单行可达 200KB）。用户点开某条时再用 {@code id}（即 thinkingId）走
 * 现有 {@code GET /aiclaw/thinking/{thinkingId}} 按需拉取全文。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "房间 thinking 归档列表项（元数据 only）")
public class AiclawThinkingListItemResp implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "thinkingId（列表主键，展开时用于拉取全文）")
	private Long id;

	@Schema(description = "产生 thinking 的 aiclaw uid")
	private Long aiclawUid;

	@Schema(description = "触发本次 thinking 的消息 ID")
	private Long triggerMsgId;

	@Schema(description = "状态：0=进行中 1=成功 2=错误 3=超时 4=超长截断")
	private Integer status;

	@Schema(description = "处理耗时（毫秒）")
	private Integer durationMs;

	@Schema(description = "是否产生了回复消息：0=否，1=是")
	private Integer hasResponse;

	@Schema(description = "创建时间")
	private LocalDateTime createTime;
}
