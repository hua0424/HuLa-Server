package com.luohuo.flex.im.common.event.listener;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.common.event.MessageSendEvent;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.dto.ChatMsgSendDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#446：旧 AI 回调与空微信回调删除后，事务消息事件仍只产生既有可靠路由意图。
 */
class MessageSendListenerTest {

    private AnnotationConfigApplicationContext ctx;
    private MQProducer producer;

    private void boot() {
        producer = mock(MQProducer.class);
        ctx = new AnnotationConfigApplicationContext();
        // 手动单例不经过注解后处理，避免 Mockito 子类继承 @Resource 被误注入
        ctx.getBeanFactory().registerSingleton("mqProducer", producer);
        ctx.register(MessageSendListener.class);
        ctx.refresh();
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        ContextUtil.remove();
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    private MessageSendEvent event(Long msgId, Long uid) {
        ChatMsgSendDto dto = ChatMsgSendDto.builder()
                .msgId(msgId)
                .uid(uid)
                .extra(Map.of("autoReply", true))
                .build();
        return new MessageSendEvent(this, dto);
    }

    @Test
    void routeIntentKeepsPayloadTenantExtraAndDedupKey() {
        ContextUtil.setTenantId(7L);
        boot();

        ctx.publishEvent(event(306L, 42L));

        var dto = ArgumentCaptor.forClass(MsgSendMessageDTO.class);
        verify(producer, times(1))
                .sendSecureMsg(eq(MqConstant.MSG_PUSH_OUTPUT_TOPIC), dto.capture(), eq(306L));
        assertEquals(306L, dto.getValue().getMsgId());
        assertEquals(42L, dto.getValue().getUid());
        assertEquals(7L, dto.getValue().getTenantId());
        assertEquals(Map.of("autoReply", true), dto.getValue().getExtra());
        verifyNoMoreInteractions(producer);
    }

    @Test
    void fallbackExecutionFiresWithoutTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        boot();

        ctx.publishEvent(event(307L, 43L));

        verify(producer, times(1))
                .sendSecureMsg(eq(MqConstant.MSG_PUSH_OUTPUT_TOPIC), any(MsgSendMessageDTO.class), eq(307L));
    }

    @Test
    void retiredCallbacksAreNoLongerRegistered() {
        List<Method> listeners = Arrays.stream(MessageSendListener.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(TransactionalEventListener.class))
                .toList();
        assertEquals(1, listeners.size());
        assertEquals("messageRoute", listeners.get(0).getName());
        TransactionalEventListener ann = listeners.get(0).getAnnotation(TransactionalEventListener.class);
        assertEquals(TransactionPhase.BEFORE_COMMIT, ann.phase());
        assertTrue(ann.fallbackExecution());

        assertThrows(NoSuchMethodException.class,
                () -> MessageSendListener.class.getDeclaredMethod("handlerMsg", MessageSendEvent.class));
        assertThrows(NoSuchMethodException.class,
                () -> MessageSendListener.class.getDeclaredMethod("publishChatToWechat", MessageSendEvent.class));
        assertThrows(NoSuchMethodException.class,
                () -> MessageSendListener.class.getDeclaredMethod("isHotRoom",
                        com.luohuo.flex.im.domain.entity.Room.class));
    }
}
