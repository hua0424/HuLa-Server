package com.luohuo.flex.im.common.event.listener;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.common.event.MessageSendEvent;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 消息发送监听器
 *
 * @author zhongzb create on 2022/08/26
 */
@Slf4j
@Component
public class MessageSendListener {
    @Resource
    private MQProducer mqProducer;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, classes = MessageSendEvent.class, fallbackExecution = true)
    public void messageRoute(MessageSendEvent event) {
        Long msgId = event.getChatMsgSendDto().getMsgId();
        MsgSendMessageDTO dto = MsgSendMessageDTO.builder()
                .msgId(msgId)
                .uid(event.getChatMsgSendDto().getUid())
                .tenantId(ContextUtil.getTenantId())
                .extra(event.getChatMsgSendDto().getExtra())
                .build();
        mqProducer.sendSecureMsg(MqConstant.MSG_PUSH_OUTPUT_TOPIC, dto, msgId);
    }
}
