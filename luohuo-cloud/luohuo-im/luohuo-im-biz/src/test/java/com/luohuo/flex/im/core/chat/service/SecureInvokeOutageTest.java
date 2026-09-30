package com.luohuo.flex.im.core.chat.service;

import cn.hutool.extra.spring.SpringUtil;
import com.luohuo.basic.annotation.SecureInvoke;
import com.luohuo.basic.dao.SecureInvokeRecordDao;
import com.luohuo.basic.domain.dto.SecureInvokeDTO;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.service.SecureInvokeService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecureInvokeOutageTest {
    public static class RecoveringPublisher {
        boolean available;
        int attempts;

        public void publish() {
            attempts++;
            if (!available) {
                throw new IllegalStateException("broker unavailable");
            }
        }
    }

    @Test
    void committedMessagePublicationSurvivesMoreThanThreeFailures() throws Exception {
        int budget = MQProducer.class.getMethod("sendSecureMsg", String.class, Object.class, Object.class)
                .getAnnotation(SecureInvoke.class).maxRetryTimes();
        assertEquals(200, budget);
        SecureInvokeRecordDao dao = mock(SecureInvokeRecordDao.class);
        SecureInvokeService service = new SecureInvokeService(dao, Runnable::run);
        when(dao.removeById(306L)).thenReturn(true);
        SecureInvokeDTO invocation = SecureInvokeDTO.builder()
                .className(RecoveringPublisher.class.getName())
                .methodName("publish")
                .parameterTypes("[]")
                .args("[]")
                .build();
        SecureInvokeRecord record = SecureInvokeRecord.builder()
                .secureInvokeDTO(invocation)
                .maxRetryTimes(budget)
                .build();
        record.setId(306L);
        when(dao.updateById(any(SecureInvokeRecord.class))).thenAnswer(call -> {
            SecureInvokeRecord update = call.getArgument(0);
            record.setRetryTimes(update.getRetryTimes());
            record.setNextRetryTime(update.getNextRetryTime());
            record.setState(update.getState());
            return true;
        });
        RecoveringPublisher publisher = new RecoveringPublisher();
        try (MockedStatic<SpringUtil> beans = mockStatic(SpringUtil.class)) {
            beans.when(() -> SpringUtil.getBean(RecoveringPublisher.class)).thenReturn(publisher);
            for (int i = 0; i < 8; i++) {
                service.doInvoke(record);
            }
            assertEquals(8, record.getRetryTimes());
            assertEquals(SecureInvokeRecord.STATUS_WAIT, record.getState());
            assertTrue(Duration.between(LocalDateTime.now(), record.getNextRetryTime()).toMinutes() <= 64);
            publisher.available = true;
            service.doInvoke(record);
        }
        assertEquals(9, publisher.attempts);
        verify(dao).removeById(306L);
    }

    @Test
    void rejectedInsertDoesNotRegisterPublicationCallback() {
        SecureInvokeRecordDao dao = mock(SecureInvokeRecordDao.class); // save defaults to false
        SecureInvokeService service = new SecureInvokeService(dao, Runnable::run);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> service.invoke(new SecureInvokeRecord(), true));
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().isEmpty());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void publishedMessageWithUndeletedIntentRemainsRetryable() {
        SecureInvokeRecordDao dao = mock(SecureInvokeRecordDao.class); // removeById defaults to false
        SecureInvokeService service = new SecureInvokeService(dao, Runnable::run);
        SecureInvokeDTO invocation = SecureInvokeDTO.builder()
                .className(RecoveringPublisher.class.getName()).methodName("publish")
                .parameterTypes("[]").args("[]").build();
        SecureInvokeRecord record = SecureInvokeRecord.builder()
                .secureInvokeDTO(invocation).maxRetryTimes(200).build();
        record.setId(307L);
        RecoveringPublisher publisher = new RecoveringPublisher();
        publisher.available = true;
        try (MockedStatic<SpringUtil> beans = mockStatic(SpringUtil.class)) {
            beans.when(() -> SpringUtil.getBean(RecoveringPublisher.class)).thenReturn(publisher);
            service.doInvoke(record);
        }
        assertEquals(1, publisher.attempts);
        verify(dao).removeById(307L);
        verify(dao).updateById(any(SecureInvokeRecord.class));
    }
}
