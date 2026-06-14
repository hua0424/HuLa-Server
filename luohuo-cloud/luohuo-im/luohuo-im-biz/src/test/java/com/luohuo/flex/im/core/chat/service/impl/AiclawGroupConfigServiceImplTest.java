package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
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

	/**
	 * aichatoverview#26: 初始化 AiclawGroupConfig 的 MyBatis-Plus TableInfo 缓存，
	 * 使 LambdaQueryWrapper 能在纯单元测试中解析 lambda 列名并物化 SQL，
	 * 从而让 scope 断言可读取绑定参数。
	 */
	@BeforeAll
	static void initTableInfoCache() {
		MapperBuilderAssistant assistant =
				new MapperBuilderAssistant(new Configuration(), "");
		assistant.setCurrentNamespace(AiclawGroupConfigMapper.class.getName());
		TableInfoHelper.initTableInfo(assistant, AiclawGroupConfig.class);
	}

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
		// REQ-004 S5: 群聊默认改为「需要 @ 触发」，无记录时默认值 0 -> 1
		assertEquals(1, resp.getMentionRequired());
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

	// =====================================================================
	// aichatoverview#26: listSelfConfigs — 按认证身份预热「这个 aiclaw 自己」的所有群配置
	//
	// IDOR-safety（S7 教训）：service 只查询传入的 aiclawUid 这一个值，且 controller
	// 仅以 ContextUtil.getUid() 调用本方法、端点不声明任何 query 参数，因此一个 aiclaw
	// 无法借 query 拉取另一个 aiclaw 的配置。下面的 scope 测试用 ArgumentCaptor 固化
	// 「service 只查传入的 aiclawUid」这一契约。
	// =====================================================================

	@Test
	@DisplayName("listSelfConfigs 返回该 aiclaw 的全部群配置行")
	void listSelfConfigsReturnsAllRowsForThatAiclaw() {
		AiclawGroupConfig row1 = AiclawGroupConfig.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(10L)
				.rateLimitPerMinute(20)
				.mentionRequired(1)
				.dailyLimit(500)
				.respondToAi(0)
				.build();
		AiclawGroupConfig row2 = AiclawGroupConfig.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(11L)
				.rateLimitPerMinute(5)
				.mentionRequired(0)
				.dailyLimit(100)
				.respondToAi(1)
				.build();
		when(aiclawGroupConfigMapper.selectList(any())).thenReturn(List.of(row1, row2));

		List<AiclawGroupConfigResp> resp = configService.listSelfConfigs(AICLAW_UID);

		assertNotNull(resp);
		assertEquals(2, resp.size());
		// 每条都是该 aiclaw 自己的行
		assertTrue(resp.stream().allMatch(r -> AICLAW_UID.equals(r.getAiclawUid())));
		// 字段映射正确
		AiclawGroupConfigResp r1 = resp.stream().filter(r -> r.getRoomId().equals(10L)).findFirst().orElseThrow();
		assertEquals(20, r1.getRateLimitPerMinute());
		assertEquals(1, r1.getMentionRequired());
		assertEquals(500, r1.getDailyLimit());
		assertEquals(0, r1.getRespondToAi());
		AiclawGroupConfigResp r2 = resp.stream().filter(r -> r.getRoomId().equals(11L)).findFirst().orElseThrow();
		assertEquals(5, r2.getRateLimitPerMinute());
		assertEquals(0, r2.getMentionRequired());
		assertEquals(100, r2.getDailyLimit());
		assertEquals(1, r2.getRespondToAi());
	}

	@Test
	@DisplayName("listSelfConfigs 无配置时返回空 List 不抛错")
	void listSelfConfigsEmptyWhenNoneConfigured() {
		when(aiclawGroupConfigMapper.selectList(any())).thenReturn(Collections.emptyList());

		List<AiclawGroupConfigResp> resp = configService.listSelfConfigs(AICLAW_UID);

		assertNotNull(resp);
		assertTrue(resp.isEmpty());
	}

	@Test
	@DisplayName("listSelfConfigs 查询仅作用于传入的 aiclawUid（IDOR scope）")
	void listSelfConfigsScopedToArgumentAiclawUid() {
		when(aiclawGroupConfigMapper.selectList(any())).thenReturn(Collections.emptyList());

		configService.listSelfConfigs(AICLAW_UID);

		// 捕获传给 selectList 的 wrapper，断言其条件 SQL 段仅绑定传入的 aiclawUid，
		// 没有掺入其他 aiclaw 的过滤条件。
		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaQueryWrapper<AiclawGroupConfig>> captor =
				ArgumentCaptor.forClass(LambdaQueryWrapper.class);
		verify(aiclawGroupConfigMapper).selectList(captor.capture());

		LambdaQueryWrapper<AiclawGroupConfig> wrapper = captor.getValue();
		// 物化 SQL：触发 lambda 列名解析与绑定参数填充。
		String sql = wrapper.getTargetSql();
		// 条件只作用于 aiclaw_uid 列（不掺入 room_id 等其他过滤，预热取全部群）。
		assertTrue(sql.contains("aiclaw_uid"));
		assertFalse(sql.contains("room_id"));
		// 仅有一个绑定参数，且其值就是传入的 aiclawUid——查询只作用于「这个 aiclaw」，
		// 没有掺入其他 aiclawUid，体现 IDOR scope。
		assertEquals(1, wrapper.getParamNameValuePairs().size());
		assertTrue(wrapper.getParamNameValuePairs().containsValue(AICLAW_UID));
		// selectList 仅被调用一次——不会发出携带其他 aiclawUid 的额外查询
		verify(aiclawGroupConfigMapper, times(1)).selectList(any());
	}
}
