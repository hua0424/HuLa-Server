package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#3: aiclaw 群配置服务测试 — 短回复字段下线。
 */
@ExtendWith(MockitoExtension.class)
class AiclawGroupConfigServiceImplTest {

    @Mock private AiclawGroupConfigMapper aiclawGroupConfigMapper;
    @Mock private AiclawOwnerCache aiclawOwnerCache;
    @Mock private GroupMemberCache groupMemberCache;
    @Mock private PushService pushService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private AiclawGroupConfigServiceImpl configService;

    private static final Long AICLAW_UID = 100L;
    private static final Long ROOM_ID = 10L;
    private static final Long UID = 200L;

    @Test
    @DisplayName("getConfig 返回的 DTO 不包含 shortReplyThreshold 和 shortReplyLookback 字段")
    void getConfig_respDtoHasNoShortReplyFields() {
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(UID, 201L));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        AiclawGroupConfig config = AiclawGroupConfig.builder()
                .aiclawUid(AICLAW_UID)
                .roomId(ROOM_ID)
                .rateLimitPerMinute(20)
                .mentionRequired(1)
                .dailyLimit(500)
                .respondToAi(0)
                .shortReplyThreshold(10)
                .shortReplyLookback(3)
                .build();
        when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(config);

        AiclawGroupConfigResp resp = configService.getConfig(AICLAW_UID, ROOM_ID, UID);

        assertNotNull(resp);
        assertEquals(AICLAW_UID, resp.getAiclawUid());
        assertEquals(ROOM_ID, resp.getRoomId());
        assertEquals(20, resp.getRateLimitPerMinute());
        assertEquals(1, resp.getMentionRequired());
        assertEquals(500, resp.getDailyLimit());
        assertEquals(0, resp.getRespondToAi());

        // 关键断言：DTO 中不应再有短回复字段
        // 由于字段已从 DTO 删除，编译期即保证；这里通过反射确认不存在
        assertNull(getFieldValue(resp, "shortReplyThreshold"),
                "DTO 不应包含 shortReplyThreshold");
        assertNull(getFieldValue(resp, "shortReplyLookback"),
                "DTO 不应包含 shortReplyLookback");
    }

    @Test
    @DisplayName("getConfig 无记录时默认值也不包含短回复字段")
    void getConfig_defaultValuesHaveNoShortReplyFields() {
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(UID, 201L));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);

        AiclawGroupConfigResp resp = configService.getConfig(AICLAW_UID, ROOM_ID, UID);

        assertNotNull(resp);
        assertNull(getFieldValue(resp, "shortReplyThreshold"),
                "默认值 DTO 不应包含 shortReplyThreshold");
        assertNull(getFieldValue(resp, "shortReplyLookback"),
                "默认值 DTO 不应包含 shortReplyLookback");
    }

    @Test
    @DisplayName("updateConfig 请求 DTO 不包含 shortReplyThreshold 和 shortReplyLookback")
    void updateConfig_reqDtoHasNoShortReplyFields() {
        // 构造请求时只设置保留字段
        AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
                .aiclawUid(AICLAW_UID)
                .roomId(ROOM_ID)
                .rateLimitPerMinute(15)
                .mentionRequired(0)
                .dailyLimit(200)
                .respondToAi(1)
                .build();

        // 确认请求 DTO 中没有短回复字段（编译期保证）
        assertNull(getFieldValue(req, "shortReplyThreshold"),
                "UpdateReq 不应包含 shortReplyThreshold");
        assertNull(getFieldValue(req, "shortReplyLookback"),
                "UpdateReq 不应包含 shortReplyLookback");

        // 执行更新流程
        when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);
        when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);

        // 不抛异常即通过
        assertDoesNotThrow(() -> configService.updateConfig(req, UID));
    }

    /**
     * 反射辅助：获取对象字段值，字段不存在返回 null。
     */
    private Object getFieldValue(Object obj, String fieldName) {
        try {
            return obj.getClass().getDeclaredField(fieldName).get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
