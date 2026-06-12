package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
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
 * 字段已从 DTO 编译期移除，无需运行时反射验证。
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
	@DisplayName("getConfig 有 DB 记录时返回正确的保留字段")
	void getConfig_withDbRecord_returnsExpectedFields() {
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
	}

	@Test
	@DisplayName("getConfig 无记录时返回默认值")
	void getConfig_noRecord_returnsDefaults() {
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(UID, 201L));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);

		AiclawGroupConfigResp resp = configService.getConfig(AICLAW_UID, ROOM_ID, UID);

		assertNotNull(resp);
		assertEquals(AICLAW_UID, resp.getAiclawUid());
		assertEquals(ROOM_ID, resp.getRoomId());
		assertEquals(10, resp.getRateLimitPerMinute());
		assertEquals(0, resp.getMentionRequired());
		assertEquals(1000, resp.getDailyLimit());
		assertEquals(1, resp.getRespondToAi());
	}

	@Test
	@DisplayName("updateConfig 只更新保留字段，不抛异常")
	void updateConfig_onlyUpdatesRetainedFields() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.rateLimitPerMinute(15)
				.mentionRequired(0)
				.dailyLimit(200)
				.respondToAi(1)
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);

		assertDoesNotThrow(() -> configService.updateConfig(req, UID));
	}
}
