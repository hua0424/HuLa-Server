package com.luohuo.flex.im.common.event.listener;

import com.luohuo.flex.im.common.event.MessageRecallEvent;
import com.luohuo.flex.model.entity.dto.ChatMsgRecallDTO;
import com.luohuo.flex.im.core.chat.service.cache.MsgCache;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.luohuo.flex.im.common.config.ThreadPoolConfig.LUOHUO_EXECUTOR;

/**
 * 消息撤回监听器
 *
 * @author nyh
 */
@Slf4j
@Component
public class MessageRecallListener {
    @Resource
    private MsgCache msgCache;
    @Resource
    private PushService pushService;

    @Async(LUOHUO_EXECUTOR)
    @TransactionalEventListener(classes = MessageRecallEvent.class, fallbackExecution = true)
    public void evictMsg(MessageRecallEvent event) {
        ChatMsgRecallDTO recallDTO = event.getRecallDTO();
        msgCache.delete(recallDTO.getMsgId());
    }

    @Async(LUOHUO_EXECUTOR)
    @TransactionalEventListener(classes = MessageRecallEvent.class, fallbackExecution = true)
    public void sendToAll(MessageRecallEvent event) {
        // #482：异步监听自己消费回执，撤回推送失败只记录，不回滚已提交的撤回业务。
        PushSubmission submission = pushService.submitPushMsg(WsAdapter.buildMsgRecall(event.getRecallDTO()), event.getUidList(), event.getRecallDTO().getRecallUid());
        if (submission != null && !submission.isFullyAdmitted()) {
            log.info("消息撤回推送未全部接收: msgId={}, admission={}, routeStatus={}, 未接收={}",
                    event.getRecallDTO().getMsgId(), submission.getAdmission(), submission.getRouteStatus(), submission.getUnreceivedUids());
        }
    }

}
