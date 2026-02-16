package com.luohuo.flex.im.core.chat.consumer;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.domain.entity.msg.TextMsgReq;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;
import com.luohuo.flex.im.domain.vo.request.ChatMessageReq;
import com.luohuo.flex.model.entity.dto.AiNodeFinalReplyDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * AI 最终回复回流消费者（WS -> IM）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstant.AI_NODE_REPLY_TOPIC,
		consumerGroup = MqConstant.AI_NODE_REPLY_TOPIC_GROUP,
		messageModel = MessageModel.CLUSTERING)
public class AiNodeReplyConsumer implements RocketMQListener<AiNodeFinalReplyDTO> {

	private final ChatService chatService;

	@Override
	public void onMessage(AiNodeFinalReplyDTO dto) {
		if (dto == null || dto.getRoomId() == null || dto.getAiUserId() == null) {
			return;
		}
		if (StrUtil.isBlank(dto.getContent())) {
			return;
		}

		Long tenantId = dto.getTenantId();
		if (tenantId != null) {
			ContextUtil.setTenantId(tenantId);
		}
		if (dto.getAiUserId() != null) {
			ContextUtil.setUid(dto.getAiUserId());
		}
		try {
			ChatMessageReq req = new ChatMessageReq();
			req.setRoomId(dto.getRoomId());
			req.setMsgType(MessageTypeEnum.BOT.getType());
			req.setBody(TextMsgReq.builder().content(dto.getContent()).build());
			req.setSkip(true);
			req.setPushMessage(true);
			Long msgId = chatService.sendMsg(req, dto.getAiUserId());
			log.info("AI回复已落库并推送: requestId={}, msgId={}, roomId={}", dto.getRequestId(), msgId, dto.getRoomId());
		} catch (Exception ex) {
			log.error("AI回复回流失败: requestId={}, roomId={}", dto.getRequestId(), dto.getRoomId(), ex);
		} finally {
			ContextUtil.clearTenantContext();
		}
	}
}
