package com.luohuo.flex.im.domain.vo.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 聊天信息点播
 * @author nyh
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageReq {
    @NotNull
    @Schema(description ="房间id")
    private Long roomId;

    @Schema(description ="消息类型")
    @NotNull
    private Integer msgType;

    @Schema(description ="消息内容，类型不同传值不同")
    @NotNull
    private Object body;

	@Schema(description ="跳过消息校验")
	private boolean skip = false;

	@Schema(description ="临时消息 [前端需要传过来]")
	private boolean isTemp = false;

	@Schema(description ="系统推送消息")
	private boolean isPushMessage = false;

	@Schema(description ="仅存库不推送（流式消息 stream_end 落库时使用，避免与流式推送重复）")
	private boolean skipPush = false;

	/**
	 * ISS-015: 流式消息 stream_start 时间戳,落库时用于覆盖 create_time(=前端 sendTime)。
	 * 仅在 skipPush=true 时生效(防止常规客户端伪造历史时间),并被 clamp 到 [now-5min, now]。
	 * 接受 epoch millis(Number) 或 ISO-8601 字符串,见 LuohuoLocalDateTimeDeserializer。
	 */
	@Schema(description ="消息原始发送时间(流式消息 stream_start;仅 skipPush=true 时生效,自动 clamp 到 [now-5min, now])")
	private LocalDateTime sendTime;

	/**
	 * REQ-004 M2-2: aiclaw 扩展字段，用于传递 thinkingId、autoReply 等元数据。
	 * 不持久化到 im_message 表，仅透传至 WS push。
	 */
	@Schema(description ="aiclaw 扩展字段（thinkingId、autoReply 等，不持久化）")
	private Map<String, Object> extra;

	@Schema(description ="客户端生成的临时消息 id（F3-1 #42：仅回显给发送者做乐观气泡精确 reconcile，不参与业务）")
	private String clientMsgId;
}
