package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThinkingCleanupTest {
    @Mock private AiclawThinkingMapper mapper;
    @Mock private PushService pushService;
    @Mock private AiclawProperties properties;
    @InjectMocks private ThinkingService service;

    @AfterEach
    void clear() { ContextUtil.remove(); }

    private AiclawThinking row(long id, long tenant, LocalDateTime time) {
        AiclawThinking row = new AiclawThinking();
        row.setId(id);
        row.setTenantId(tenant);
        row.setAiclawUid(100L);
        row.setRoomId(10L);
        row.setCreateTime(time);
        row.setStatus(0);
        return row;
    }

    private void timeoutConfigured() {
        AiclawProperties.Thinking config = new AiclawProperties.Thinking();
        when(properties.getThinking()).thenReturn(config);
    }

    @Test
    void expiredRowUsesItsOwnTenantAndOnlyCasWinnerBroadcastsToCurrentDbMembers() {
        timeoutConfigured();
        ContextUtil.setTenantId(999L); // Never use the previous request's tenant as a scheduler identity.
        AiclawThinking first = row(11L, 2L, LocalDateTime.now().minusMinutes(10));
        AiclawThinking second = row(12L, 3L, LocalDateTime.now().minusMinutes(10));
        when(mapper.selectCleanupCandidates(any(LocalDateTime.class), eq(0L))).thenReturn(List.of(first, second));
        when(mapper.selectOwned(11L, 2L, 100L, 10L)).thenReturn(first);
        when(mapper.selectOwned(12L, 3L, 100L, 10L)).thenReturn(second, row(12L, 3L, second.getCreateTime()));
        when(mapper.finalizeActive(11L, 2L, 100L, 10L, "", null, 3, "timeout")).thenReturn(1);
        when(mapper.selectCurrentMemberUids(10L, 2L)).thenReturn(List.of(200L));

        service.cleanupActiveThinkings();

        verify(mapper).finalizeActive(12L, 3L, 100L, 10L, "", null, 3, "timeout");
        verify(mapper, never()).selectCurrentMemberUids(10L, 3L);
        @SuppressWarnings("rawtypes") ArgumentCaptor<WsBaseResp> packet = ArgumentCaptor.forClass(WsBaseResp.class);
        verify(pushService).sendPushMsg(packet.capture(), eq(List.of(200L)), eq(0L));
        assertEquals("thinkingEnd", packet.getValue().getType());
        WSThinkingEnd end = (WSThinkingEnd) packet.getValue().getData();
        assertEquals("11", end.getThinkingId());
        assertEquals("10", end.getRoomId());
        assertEquals("error", end.getStatus());
        assertEquals("timeout", end.getError());
        assertNull(ContextUtil.getTenantId());
        assertNull(ContextUtil.getUserId());
    }

    @Test
    void newlyRevokedRowClosesWithoutWaitingForTimeoutAndEmptyMembershipNeverPushes() {
        timeoutConfigured();
        AiclawThinking revoked = row(15L, 4L, LocalDateTime.now());
        when(mapper.selectCleanupCandidates(any(LocalDateTime.class), eq(0L))).thenReturn(List.of(revoked));
        when(mapper.selectOwned(15L, 4L, 100L, 10L)).thenReturn(revoked);
        when(mapper.finalizeActive(15L, 4L, 100L, 10L, "", null, 2, "authorization_revoked"))
                .thenReturn(1);
        when(mapper.selectCurrentMemberUids(10L, 4L)).thenReturn(List.of());

        service.cleanupActiveThinkings();

        verify(pushService, never()).sendPushMsg(any(), anyList(), anyLong());
        verify(mapper).finalizeActive(15L, 4L, 100L, 10L, "", null, 2, "authorization_revoked");
        assertNull(ContextUtil.getTenantId());
    }

    @Test
    void keysetAdvancesThenWrapsWithoutRebroadcastingTerminalRows() {
        timeoutConfigured();
        AiclawThinking old = row(18L, 2L, LocalDateTime.now().minusMinutes(10));
        when(mapper.selectCleanupCandidates(any(LocalDateTime.class), eq(0L))).thenReturn(List.of(old), List.of());
        when(mapper.selectCleanupCandidates(any(LocalDateTime.class), eq(18L))).thenReturn(List.of());
        when(mapper.selectOwned(18L, 2L, 100L, 10L)).thenReturn(old);
        when(mapper.finalizeActive(18L, 2L, 100L, 10L, "", null, 3, "timeout")).thenReturn(1);
        when(mapper.selectCurrentMemberUids(10L, 2L)).thenReturn(List.of());
        service.cleanupActiveThinkings();
        service.cleanupActiveThinkings();
        service.cleanupActiveThinkings();
        verify(mapper, times(1)).finalizeActive(anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                isNull(), anyInt(), anyString());
    }
}
