package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 消息
 * @author nyh
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageResp implements Serializable {

    @Schema(description ="发送者信息")
    private UserInfo fromUser;
    @Schema(description ="消息详情")
    private Message message;

    @Data
    public static class UserInfo {
        @Schema(description ="用户id")
        private String uid;
        @Schema(description ="用户类型 1系统 2机器人 3普通用户 4aiclaw（REQ-004 S23：供 aiclaw 反环路与 respondToAi 判定）")
        private Integer userType;
        @Schema(description = "发送者显示名（REQ-021：群昵称优先，回退用户名；供 aiclaw 群语境标注发言人）")
        private String name;
    }

    @Data
    public static class Message {
        @Schema(description ="消息id")
        private String id;
        @Schema(description ="房间id")
        private String roomId;
        @Schema(description ="房间类型：1=群聊,2=单聊")
        private Integer roomType;
        @Schema(description ="消息发送时间")
        private LocalDateTime sendTime;
        @Schema(description ="消息类型 1正常文本 2.撤回消息")
        private Integer type;
        @Schema(description ="消息内容不同的消息类型，内容体不同")
        private Object body;
		@Schema(description = "扩展标记统计（type为MessageMarkTypeEnum的type字段）")
		private Map<Integer, MarkItem> messageMarks;
		@Schema(description = "aiclaw 扩展信息，仅推送给 aiclaw 用户时附加")
		private AiclawExt aiclaw;
		/**
		 * REQ-004 M2-2: aiclaw 扩展字段（thinkingId、autoReply 等，不持久化，仅 WS push 透传）
		 */
		@Schema(description = "aiclaw 扩展字段（thinkingId、autoReply 等，WS 推送透传）")
		private Map<String, Object> extra;
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		@Schema(description="客户端临时消息 id 回显（F3-1 #42：nullable；仅发送者据此精确 reconcile 乐观气泡；WS receiveMessage + /chat/msg/list sync 两路都回带）")
		private String clientMsgId;
    }

	@Data
	@AllArgsConstructor
	public static class MarkItem {
		@Schema(description = "标记数量")
		private Integer count;
		@Schema(description = "当前用户是否标记")
		private Boolean userMarked;
	}

	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class AiclawExt implements Serializable {
		@Schema(description = "发送者昵称")
		private String senderName;
		@Schema(description = "是否为 aiclaw 的 owner")
		private Boolean isOwner;
		@Schema(description = "人设（系统 prompt）")
		private String publicPersona;
		@Schema(description = "关系说明")
		private String relationDesc;
	}
}
