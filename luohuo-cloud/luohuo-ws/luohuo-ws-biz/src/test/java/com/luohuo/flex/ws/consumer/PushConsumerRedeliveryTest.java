package com.luohuo.flex.ws.consumer;

import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.ws.websocket.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PushConsumerRedeliveryTest {
    @Test
    void failedWebSocketWriteMustNotAcknowledgeMqBeforeRedeliveryOfSameMessage() {
        SessionManager sessions = mock(SessionManager.class);
        PushConsumer consumer = new PushConsumer();
        ReflectionTestUtils.setField(consumer, "sessionManager", sessions);
        WsBaseResp<?> payload = new WsBaseResp<>();
        NodePushDTO delivery = new NodePushDTO(payload, Map.of("device", 17L), 306L, 11L);
        AtomicInteger writes = new AtomicInteger();
        when(sessions.sendToDevice(eq(17L), eq("device"), any())).thenAnswer(call ->
                Mono.defer(() -> writes.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("websocket write interrupted")) : Mono.empty()));

        // RocketMQ retries only if onMessage throws; an async subscribe that logs and returns is lost.
        assertThrows(RuntimeException.class, () -> consumer.onMessage(delivery));
        consumer.onMessage(delivery);
        assertEquals(2, writes.get());
        assertEquals(306L, delivery.getHashId());
    }
}
