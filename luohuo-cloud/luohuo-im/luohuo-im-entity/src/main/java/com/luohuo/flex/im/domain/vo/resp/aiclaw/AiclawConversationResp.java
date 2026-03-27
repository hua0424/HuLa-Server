package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * aiclaw 对话列表项响应
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiclawConversationResp implements Serializable {

	@Schema(description = "好友用户ID")
	private Long friendUid;

	@Schema(description = "好友昵称")
	private String friendName;

	@Schema(description = "好友头像")
	private String friendAvatar;

	@Schema(description = "最后一条消息")
	private LastMessage lastMessage;

	@Schema(description = "聊天房间ID")
	private Long roomId;

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class LastMessage implements Serializable {
		@Schema(description = "消息内容")
		private String content;
		@Schema(description = "发送时间（13位时间戳）")
		private Long sendTime;
		@Schema(description = "消息类型")
		private Integer type;
	}
}
