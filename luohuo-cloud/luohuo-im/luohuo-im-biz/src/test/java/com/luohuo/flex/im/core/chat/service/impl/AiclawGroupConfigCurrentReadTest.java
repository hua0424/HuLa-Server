package com.luohuo.flex.im.core.chat.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.GroupRunAdmissionService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigStateResp;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#484 (T3)：aiclaw 群配置「当前事实」窄读。
 *
 * <p>核心守卫：当前读不得复用 Redis 配置缓存（{@code getConfig} 会把「无配置行」的默认值也写进缓存，
 * 迟到旧快照会让 approved/workspaceDir 长期停在旧值），因此把 StringRedisTemplate mock 成
 * 「返回一条 approved=0 的旧配置」，断言读结果仍来自 DB。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiclawGroupConfigCurrentReadTest {

	@Mock private AiclawGroupConfigMapper aiclawGroupConfigMapper;
	@Spy private AiclawProperties aiclawProperties = new AiclawProperties();
	@Mock private AiclawOwnerCache aiclawOwnerCache;
	@Mock private GroupMemberCache groupMemberCache;
	@Mock private PushService pushService;
	@Mock private StringRedisTemplate stringRedisTemplate;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private AiclawDao aiclawDao;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private GroupRunAdmissionService groupRunAdmissionService;

	@InjectMocks private AiclawGroupConfigServiceImpl service;

	private static final Long ROOM_ID = 10L;
	private static final Long GROUP_ID = 99L;
	private static final Long AICLAW_UID = 200L;
	private static final Long OWNER_UID = 300L;

	@BeforeAll
	static void initTableInfo() {
		// LambdaQueryWrapper 需要 MyBatis-Plus 的 TableInfo 元数据，单测无 Spring 容器时手工注册
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AiclawGroupConfig.class);
	}

	private RoomGroup roomGroup() {
		RoomGroup roomGroup = new RoomGroup();
		roomGroup.setId(GROUP_ID);
		roomGroup.setRoomId(ROOM_ID);
		roomGroup.setAccount("acc");
		return roomGroup;
	}

	private GroupMember member(Long uid) {
		GroupMember m = new GroupMember();
		m.setGroupId(GROUP_ID);
		m.setUid(uid);
		m.setRoleId(3);
		return m;
	}

	@Test
	@DisplayName("#484 当前读：绕过 Redis 旧快照，读到刚批准的 approved 与 workspaceDir")
	void readsCurrentConfigIgnoringStaleRedis() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER_UID)).thenReturn(member(OWNER_UID));
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, AICLAW_UID)).thenReturn(member(AICLAW_UID));
		AiclawGroupConfig row = new AiclawGroupConfig();
		row.setAiclawUid(AICLAW_UID);
		row.setRoomId(ROOM_ID);
		row.setApproved(1);
		row.setWorkspaceDir("/w/acme");
		row.setRateLimitPerMinute(9);
		when(aiclawGroupConfigMapper.selectOne(any(Wrapper.class))).thenReturn(row);

		// 陈旧 Redis：只被 getConfig 使用；当前读若误用会读到 approved=0
		lenient().when(stringRedisTemplate.opsForValue()).thenThrow(
				new AssertionError("当前读不得访问 Redis 配置缓存"));

		AiclawGroupConfigStateResp resp = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);

		assertEquals(AiclawGroupConfigStateResp.OUTCOME_PRESENT, resp.getOutcome());
		assertTrue(resp.getConfigured());
		assertNotNull(resp.getConfig());
		assertEquals(1, resp.getConfig().getApproved());
		assertEquals("/w/acme", resp.getConfig().getWorkspaceDir());
		assertEquals(9, resp.getConfig().getRateLimitPerMinute());
		assertEquals("acc", resp.getConfig().getAccount());
	}

	/** aichatoverview#485 T4：当前读同时带回授权代次，但它只是「这份输入属于哪个授权上下文」的标记。 */
	@Test
	@DisplayName("#485 当前读：带上按当前事实推导的授权代次")
	void carriesAuthorizationEpoch() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER_UID)).thenReturn(member(OWNER_UID));
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, AICLAW_UID)).thenReturn(member(AICLAW_UID));
		when(aiclawGroupConfigMapper.selectOne(any(Wrapper.class))).thenReturn(null);
		when(groupRunAdmissionService.currentEpoch(eq(AICLAW_UID), eq(ROOM_ID), any())).thenReturn("epoch-1");

		AiclawGroupConfigStateResp resp = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);

		assertEquals("epoch-1", resp.getAuthorizationEpoch());
	}

	/**
	 * aichatoverview#485 T4：无权访问不推导代次 —— 否则调用方会以为拿到了一份可用的授权上下文。
	 */
	@Test
	@DisplayName("#485 当前读：NOT_ACCESSIBLE 不返回授权代次")
	void notAccessibleHasNoEpoch() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(null);

		AiclawGroupConfigStateResp resp = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);

		assertNull(resp.getAuthorizationEpoch());
		verifyNoInteractions(groupRunAdmissionService);
	}

	@Test
	@DisplayName("#484 当前读：无配置行是合法状态（configured=false + approved=0），不是删除")
	void missingRowIsConfiguredFalseNotDeletion() {
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER_UID)).thenReturn(member(OWNER_UID));
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, AICLAW_UID)).thenReturn(member(AICLAW_UID));
		when(aiclawGroupConfigMapper.selectOne(any(Wrapper.class))).thenReturn(null);

		AiclawGroupConfigStateResp resp = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);

		assertEquals(AiclawGroupConfigStateResp.OUTCOME_PRESENT, resp.getOutcome());
		assertFalse(resp.getConfigured());
		assertNotNull(resp.getConfig());
		assertEquals(0, resp.getConfig().getApproved());
		assertNull(resp.getConfig().getWorkspaceDir());
	}

	@Test
	@DisplayName("#484 当前读：群不存在/读取者不在群/目标 aiclaw 已退群，一律同形 NOT_ACCESSIBLE")
	void notAccessibleIsUniform() {
		// 群查不到
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(null);
		AiclawGroupConfigStateResp noRoom = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);
		assertEquals(AiclawGroupConfigStateResp.OUTCOME_NOT_ACCESSIBLE, noRoom.getOutcome());
		assertNull(noRoom.getConfig());

		// 读取者不在群
		when(roomGroupCache.getByRoomIdFromDb(ROOM_ID)).thenReturn(roomGroup());
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER_UID)).thenReturn(null);
		AiclawGroupConfigStateResp notMember = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);
		assertEquals(AiclawGroupConfigStateResp.OUTCOME_NOT_ACCESSIBLE, notMember.getOutcome());

		// 目标 aiclaw 已退群：残留配置行不再代表当前事实
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, OWNER_UID)).thenReturn(member(OWNER_UID));
		when(groupMemberDao.getMemberByGroupId(GROUP_ID, AICLAW_UID)).thenReturn(null);
		AiclawGroupConfigStateResp aiclawGone = service.readCurrentGroupConfig(AICLAW_UID, ROOM_ID, OWNER_UID);
		assertEquals(AiclawGroupConfigStateResp.OUTCOME_NOT_ACCESSIBLE, aiclawGone.getOutcome());
		assertEquals(noRoom.getOutcome(), aiclawGone.getOutcome());
		assertNull(aiclawGone.getConfig());
	}

	@Test
	@DisplayName("#484 当前读：参数缺失不触发任何 DB 读，直接 NOT_ACCESSIBLE")
	void nullArgsShortCircuit() {
		AiclawGroupConfigStateResp resp = service.readCurrentGroupConfig(null, ROOM_ID, OWNER_UID);
		assertEquals(AiclawGroupConfigStateResp.OUTCOME_NOT_ACCESSIBLE, resp.getOutcome());
	}
}
