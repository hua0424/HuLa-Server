package com.luohuo.flex.im.core.chat.service.impl;

import com.luohuo.basic.exception.BizException;
import cn.hutool.json.JSONUtil;
import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSGroupConfigChange;
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
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private AiclawDao aiclawDao;

	@InjectMocks
	private AiclawGroupConfigServiceImpl configService;

	private static final Long AICLAW_UID = 100L;
	private static final Long ROOM_ID = 10L;
	private static final Long UID = 200L;
	private static final String ACCOUNT = "G123456";

	private RoomGroup roomGroupWithAccount() {
		RoomGroup rg = new RoomGroup();
		rg.setRoomId(ROOM_ID);
		rg.setAccount(ACCOUNT);
		return rg;
	}

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
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		AiclawGroupConfigResp resp = configService.getConfig(AICLAW_UID, ROOM_ID, UID);

		assertNotNull(resp);
		assertEquals(AICLAW_UID, resp.getAiclawUid());
		assertEquals(ROOM_ID, resp.getRoomId());
		assertEquals(10, resp.getRateLimitPerMinute());
		// REQ-004 S5: 群聊默认改为「需要 @ 触发」，无记录时默认值 0 -> 1
		assertEquals(1, resp.getMentionRequired());
		assertEquals(1000, resp.getDailyLimit());
		assertEquals(1, resp.getRespondToAi());
		// REQ-009#82: 无记录默认 approved=0（NOT null），account 为 mock 的群号
		assertEquals(0, resp.getApproved());
		assertEquals(ACCOUNT, resp.getAccount());
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

	// =====================================================================
	// REQ-009 #82: (aiclaw, 群) 批准合同 —— approved / workspaceDir 字段级群主权限。
	// grandfather SQL 回填由部署期 DB 断言验证（见 docs/sql/req-009-aiclaw-group-approval.sql），
	// 不在本单元测试范畴。
	// =====================================================================

	@Test
	@DisplayName("REQ-009#82: 群主设置 approved=1 + workspaceDir 成功，落库实体含正确值")
	void ownerSetsApprovedAndWorkspaceDir_succeeds() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.approved(1)
				.workspaceDir("/x")
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID); // UID = 群主
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		configService.updateConfig(req, UID);

		ArgumentCaptor<AiclawGroupConfig> captor = ArgumentCaptor.forClass(AiclawGroupConfig.class);
		verify(aiclawGroupConfigMapper).insert(captor.capture());
		AiclawGroupConfig saved = captor.getValue();
		assertEquals(1, saved.getApproved());
		assertEquals("/x", saved.getWorkspaceDir());
	}

	@Test
	@DisplayName("REQ-009#82: aiclaw 本人自我批准 approved → BizException（拒绝）")
	void aiclawSelfSetsApproved_throws() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.approved(1)
				.build();

		// owner != caller：owner 是 UID，调用者是 aiclaw 本人
		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);

		BizException ex = assertThrows(BizException.class,
				() -> configService.updateConfig(req, AICLAW_UID));
		assertTrue(ex.getMessage().contains("只有AI助理主人"));
		verify(aiclawGroupConfigMapper, never()).insert(any(AiclawGroupConfig.class));
		verify(aiclawGroupConfigMapper, never()).updateById(any(AiclawGroupConfig.class));
	}

	@Test
	@DisplayName("REQ-009#82: aiclaw 本人设置 workspaceDir → BizException（拒绝）")
	void aiclawSelfSetsWorkspaceDir_throws() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.workspaceDir("/hack")
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);

		BizException ex = assertThrows(BizException.class,
				() -> configService.updateConfig(req, AICLAW_UID));
		assertTrue(ex.getMessage().contains("只有AI助理主人"));
		verify(aiclawGroupConfigMapper, never()).insert(any(AiclawGroupConfig.class));
		verify(aiclawGroupConfigMapper, never()).updateById(any(AiclawGroupConfig.class));
	}

	@Test
	@DisplayName("REQ-009#82: aiclaw 本人只设置旧字段（rateLimit）→ 仍成功，不触发群主校验")
	void aiclawSelfSetsOnlyOldField_succeeds() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.rateLimitPerMinute(7)
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID); // owner=UID, caller=aiclaw 本人
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		assertDoesNotThrow(() -> configService.updateConfig(req, AICLAW_UID));
		verify(aiclawGroupConfigMapper).insert(any(AiclawGroupConfig.class));
	}

	@Test
	@DisplayName("REQ-009#82: 撤销——群主发 approved=0（不带 workspaceDir），既有 workspaceDir 保留")
	void ownerRevokeApproved_preservesWorkspaceDir() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.approved(0)
				.build();

		AiclawGroupConfig existing = AiclawGroupConfig.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.rateLimitPerMinute(10)
				.mentionRequired(1)
				.dailyLimit(1000)
				.respondToAi(1)
				.approved(1)
				.workspaceDir("/keep")
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(existing);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		configService.updateConfig(req, UID);

		ArgumentCaptor<AiclawGroupConfig> captor = ArgumentCaptor.forClass(AiclawGroupConfig.class);
		verify(aiclawGroupConfigMapper).updateById(captor.capture());
		AiclawGroupConfig saved = captor.getValue();
		assertEquals(0, saved.getApproved());
		assertEquals("/keep", saved.getWorkspaceDir());
	}

	@Test
	@DisplayName("REQ-009#82: updateConfig 广播——ConfigDTO 含 approved/workspaceDir，外层含 account")
	void updateConfigBroadcast_carriesApprovedWorkspaceDirAndAccount() {
		AiclawGroupConfigUpdateReq req = AiclawGroupConfigUpdateReq.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.approved(1)
				.workspaceDir("/ws")
				.build();

		when(aiclawOwnerCache.getOwnerUid(AICLAW_UID)).thenReturn(UID);
		when(groupMemberCache.getMemberUidList(ROOM_ID)).thenReturn(List.of(AICLAW_UID, UID));
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		configService.updateConfig(req, UID);

		// WsAdapter.buildGroupConfigChange 把 WSGroupConfigChange 包进 WsBaseResp.data，
		// 透传给 pushService.sendPushMsg(WsBaseResp<?>, List<Long>, Long)；这里捕获该信封并取出载荷。
		@SuppressWarnings("unchecked")
		ArgumentCaptor<WsBaseResp<?>> msgCaptor = ArgumentCaptor.forClass(WsBaseResp.class);
		verify(pushService).sendPushMsg(msgCaptor.capture(), anyList(), eq(UID));
		Object data = msgCaptor.getValue().getData();
		assertTrue(data instanceof WSGroupConfigChange, "WsBaseResp.data 应为 WSGroupConfigChange");
		WSGroupConfigChange change = (WSGroupConfigChange) data;
		assertEquals(ACCOUNT, change.getAccount());
		assertNotNull(change.getConfig());
		assertEquals(1, change.getConfig().getApproved());
		assertEquals("/ws", change.getConfig().getWorkspaceDir());
	}

	// =====================================================================
	// REQ-009 #84: 批准门控 —— isApproved 读取 + filterUnapprovedAiclawRecipients 收件人过滤。
	// 合同（#82）：approved == 1 为已批准；null/0（无记录/默认）为未批准。
	// =====================================================================

	private String cachedRespJson(Long aiclawUid, Long roomId, Integer approved) {
		AiclawGroupConfigResp resp = AiclawGroupConfigResp.builder()
				.aiclawUid(aiclawUid)
				.roomId(roomId)
				.approved(approved)
				.build();
		return JSONUtil.toJsonStr(resp);
	}

	@Test
	@DisplayName("REQ-009#84: isApproved 缓存命中 approved=1 → true")
	void isApproved_cacheHitApproved_returnsTrue() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(cachedRespJson(AICLAW_UID, ROOM_ID, 1));

		assertTrue(configService.isApproved(AICLAW_UID, ROOM_ID));
		// 命中缓存不应回落 DB
		verify(aiclawGroupConfigMapper, never()).selectOne(any());
	}

	@Test
	@DisplayName("REQ-009#84: isApproved 缓存命中 approved=0 → false")
	void isApproved_cacheHitNotApproved_returnsFalse() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(cachedRespJson(AICLAW_UID, ROOM_ID, 0));

		assertFalse(configService.isApproved(AICLAW_UID, ROOM_ID));
		verify(aiclawGroupConfigMapper, never()).selectOne(any());
	}

	@Test
	@DisplayName("REQ-009#84: isApproved 缓存命中 approved=null → false")
	void isApproved_cacheHitApprovedNull_returnsFalse() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(cachedRespJson(AICLAW_UID, ROOM_ID, null));

		assertFalse(configService.isApproved(AICLAW_UID, ROOM_ID));
		verify(aiclawGroupConfigMapper, never()).selectOne(any());
	}

	@Test
	@DisplayName("REQ-009#84: isApproved 缓存未命中 + DB 记录 approved=1 → true")
	void isApproved_cacheMissDbApproved_returnsTrue() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);
		AiclawGroupConfig config = AiclawGroupConfig.builder()
				.aiclawUid(AICLAW_UID)
				.roomId(ROOM_ID)
				.approved(1)
				.build();
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(config);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		assertTrue(configService.isApproved(AICLAW_UID, ROOM_ID));
	}

	@Test
	@DisplayName("REQ-009#84: isApproved 缓存未命中 + 无 DB 记录 → false（默认沉默）")
	void isApproved_cacheMissNoRow_returnsFalse() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		assertFalse(configService.isApproved(AICLAW_UID, ROOM_ID));
	}

	@Test
	@DisplayName("REQ-009#84: filterUnapprovedAiclawRecipients 剔除未批准 aiclaw，保留普通用户与已批准 aiclaw")
	void filterUnapprovedAiclawRecipients_removesUnapprovedAiclaws() {
		Long normalUser = 300L;
		Long aiclawApproved = 301L;
		Long aiclawUnapproved = 302L;
		Long aiclawNoRow = 303L;

		List<Long> members = List.of(normalUser, aiclawApproved, aiclawUnapproved, aiclawNoRow);

		// aiclawDao 识别出收件人中的 3 个 aiclaw（normalUser 不是 aiclaw）
		when(aiclawDao.listByUids(members)).thenReturn(List.of(
				aiclawRow(aiclawApproved),
				aiclawRow(aiclawUnapproved),
				aiclawRow(aiclawNoRow)));

		// 缓存：approved=1 / approved=0 / 未命中（回落 DB 无记录）
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(contains(":" + aiclawApproved + ":")))
				.thenReturn(cachedRespJson(aiclawApproved, ROOM_ID, 1));
		when(valueOps.get(contains(":" + aiclawUnapproved + ":")))
				.thenReturn(cachedRespJson(aiclawUnapproved, ROOM_ID, 0));
		when(valueOps.get(contains(":" + aiclawNoRow + ":"))).thenReturn(null);
		// aiclawNoRow 缓存未命中 → 回落 DB 也无记录 → 未批准
		when(aiclawGroupConfigMapper.selectOne(any())).thenReturn(null);
		when(roomGroupCache.get(ROOM_ID)).thenReturn(roomGroupWithAccount());

		List<Long> result = configService.filterUnapprovedAiclawRecipients(members, ROOM_ID);

		// 普通用户保留，已批准 aiclaw 保留；两个未批准 aiclaw 被剔除
		assertEquals(List.of(normalUser, aiclawApproved), result);
		assertTrue(result.contains(normalUser));
		assertTrue(result.contains(aiclawApproved));
		assertFalse(result.contains(aiclawUnapproved));
		assertFalse(result.contains(aiclawNoRow));
	}

	@Test
	@DisplayName("REQ-009#84: filterUnapprovedAiclawRecipients 空/Null 列表原样返回")
	void filterUnapprovedAiclawRecipients_nullOrEmpty_returnsAsIs() {
		assertNull(configService.filterUnapprovedAiclawRecipients(null, ROOM_ID));
		assertTrue(configService.filterUnapprovedAiclawRecipients(Collections.emptyList(), ROOM_ID).isEmpty());
		// 空/Null 不应触发任何查询
		verify(aiclawDao, never()).listByUids(any());
	}

	@Test
	@DisplayName("REQ-009#84: filterUnapprovedAiclawRecipients 无 aiclaw 成员时原样返回，不调 isApproved")
	void filterUnapprovedAiclawRecipients_noAiclawMembers_returnsAsIs() {
		List<Long> members = List.of(400L, 401L);
		when(aiclawDao.listByUids(members)).thenReturn(Collections.emptyList());

		List<Long> result = configService.filterUnapprovedAiclawRecipients(members, ROOM_ID);

		assertEquals(members, result);
		// 无 aiclaw → 不触碰缓存/DB
		verify(stringRedisTemplate, never()).opsForValue();
	}

	private Aiclaw aiclawRow(Long uid) {
		Aiclaw a = new Aiclaw();
		a.setUid(uid);
		return a;
	}
}
