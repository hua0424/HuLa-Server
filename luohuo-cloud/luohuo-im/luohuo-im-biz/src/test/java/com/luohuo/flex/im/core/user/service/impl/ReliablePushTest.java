package com.luohuo.flex.im.core.user.service.impl;

import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.basic.context.ContextUtil;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import com.luohuo.basic.aspect.SecureInvokeAspect;
import com.luohuo.basic.dao.SecureInvokeRecordDao;
import com.luohuo.basic.service.SecureInvokeService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.router.NacosRouterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReliablePushTest {
    private final PushService push = new PushService();
    private final MQProducer producer = mock(MQProducer.class);
    private final NacosRouterService router = mock(NacosRouterService.class);

    private void configure(Map<String, Map<String, Long>> route) {
        ReflectionTestUtils.setField(push, "mqProducer", producer);
        ReflectionTestUtils.setField(push, "routerService", router);
        when(router.findNodeDeviceUser(anyList())).thenReturn(route);
    }

    @Test
    void transactionalEnqueueUsesStablePerNodeKeyAndPayloadWithoutFireAndForget() {
        configure(Map.of("n1", Map.of("device", 42L)));
        WsBaseResp<String> payload = new WsBaseResp<>();
        payload.setData("message");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            push.sendReliablePushMsg(payload, List.of(42L), 306L, 42L);
            push.sendReliablePushMsg(payload, List.of(42L), 306L, 42L);
            var dto = org.mockito.ArgumentCaptor.forClass(NodePushDTO.class);
            verify(producer, times(2)).sendSecureMsg(eq(MqConstant.PUSH_TOPIC + "n1"), dto.capture(), eq("306:n1"));
            assertEquals(306L, dto.getValue().getHashId());
            assertEquals(Map.of("device", 42L), dto.getValue().getDeviceUserMap());
            assertSame(payload, dto.getValue().getWsBaseMsg());
            verify(producer, never()).sendMsg(anyString(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedIntentInsertPropagatesToFirstHopForRedelivery() {
        configure(Map.of("n1", Map.of("device", 42L)));
        doThrow(new IllegalStateException("DB insert failed"))
                .when(producer).sendSecureMsg(anyString(), any(), any());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> push.sendReliablePushMsg(new WsBaseResp<>(), List.of(42L), 306L, 42L));
            assertEquals("DB insert failed", error.getMessage());
            verify(producer, never()).sendMsg(anyString(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void objectArgumentSnapshotRoundTripsToNodePushDto() {
        WsBaseResp<Map<String, Object>> response = new WsBaseResp<>();
        response.setData(Map.of("message", Map.of("id", "opaque-306", "extra", Map.of("thinkingId", "t306"))));
        NodePushDTO original = new NodePushDTO(response, Map.of("device", 42L), 306L, 42L);
        String snapshot = JsonUtil.toJson(new Object[]{"topic", original, "306:n1"});
        assertFalse(snapshot.isEmpty());
        var args = JsonUtil.toJsonNode(snapshot);
        Object replayedArgument = JsonUtil.nodeToValue(args.get(1), Object.class);
        assertInstanceOf(Map.class, replayedArgument); // SecureInvokeService declared parameter is Object, not NodePushDTO.
        String rocketMqJson = JsonUtil.toJson(replayedArgument);
        NodePushDTO restored = JsonUtil.parseJson(rocketMqJson, NodePushDTO.class);
        assertNotNull(restored);
        assertEquals(306L, restored.getHashId());
        assertEquals(Map.of("device", 42L), restored.getDeviceUserMap());
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) restored.getWsBaseMsg().getData()).get("message");
        assertEquals("opaque-306", message.get("id"));
        assertEquals("t306", ((Map<?, ?>) message.get("extra")).get("thinkingId"));
    }

    @Test
    void transactionProxyBeginsAfterTenantRestorationAndUnproxiedCallFails() {
        assertThrows(IllegalStateException.class, () -> push.withMessageTransaction(() -> {}));
        var tm = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                assertEquals(306L, ContextUtil.getTenantId());
            }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        ProxyFactory factory = new ProxyFactory(push);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tm, new AnnotationTransactionAttributeSource()));
        ContextUtil.setTenantId(306L);
        try {
            ((PushService) factory.getProxy()).withMessageTransaction(() ->
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive()));
        } finally {
            ContextUtil.clearTenantContext();
            ContextUtil.remove();
        }
    }

    @Test
    void secureInvokeProxyPersistsBeforeAckButNeverPublishesOnRollback() {
        SecureInvokeRecordDao dao = mock(SecureInvokeRecordDao.class);
        when(dao.save(any())).thenReturn(true);
        RocketMQTemplate broker = mock(RocketMQTemplate.class);
        MQProducer target = new MQProducer();
        ReflectionTestUtils.setField(target, "rocketMQTemplate", broker);
        SecureInvokeAspect aspect = new SecureInvokeAspect();
        ReflectionTestUtils.setField(aspect, "secureInvokeService", new SecureInvokeService(dao, Runnable::run));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        ReflectionTestUtils.setField(push, "mqProducer", factory.getProxy());
        ReflectionTestUtils.setField(push, "routerService", router);
        when(router.findNodeDeviceUser(anyList())).thenReturn(Map.of("n1", Map.of("device", 42L)));
        var tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        });
        tx.execute(status -> {
            push.sendReliablePushMsg(new WsBaseResp<>(), List.of(42L), 306L, 42L);
            verify(dao).save(argThat(record -> record.getSecureInvokeDTO().getArgs().contains("306:n1")));
            verifyNoInteractions(broker);
            status.setRollbackOnly();
            return null;
        });
        verifyNoInteractions(broker);
    }

    @Test
    void noTransactionOrMissingOnlineNodeFailsButOfflineHasNoIntent() {
        configure(Map.of());
        assertThrows(IllegalStateException.class,
                () -> push.sendReliablePushMsg(new WsBaseResp<>(), List.of(42L), 306L, 42L));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            // A nonempty online recipient set without any node is a routing failure, not delivery.
            assertThrows(IllegalStateException.class,
                    () -> push.sendReliablePushMsg(new WsBaseResp<>(), List.of(42L), 306L, 42L));
            push.sendReliablePushMsg(new WsBaseResp<>(), List.of(), 306L, 42L);
            verifyNoInteractions(producer);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
