package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.im.core.chat.dao.GroupMemberDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawCommandPolicyMapper;
import com.luohuo.flex.im.core.chat.service.AiclawCommandPolicyService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.AiclawCommandPolicy;
import com.luohuo.flex.im.domain.entity.GroupMember;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCommandPolicyUpdateReq;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * aichatoverview#504 主人命令权限判定矩阵。
 *
 * <p>冻结语义：群覆盖 mode=list 名单外=该群不可调用（不回落默认，含主人）；私聊忽略名单；
 * 名单内主人调 approval_required=自批放行。
 */
@ExtendWith(MockitoExtension.class)
class AiclawCommandPolicyServiceTest {

	@Mock private AiclawCommandPolicyMapper mapper;
	@Mock private AiclawOwnerCache ownerCache;
	@Mock private GroupMemberCache memberCache;
	@Mock private RoomGroupCache roomGroupCache;
	@Mock private GroupMemberDao groupMemberDao;
	@Mock private PushService pushService;
	@Mock private StringRedisTemplate stringRedisTemplate;
	@Mock private ValueOperations<String, String> valueOps;
	@Spy private AiclawProperties aiclawProperties = new AiclawProperties();

	@InjectMocks
	private AiclawCommandPolicyServiceImpl service;

	@Test
	@DisplayName("#504 mapper 必须带 @Repository，否则线上 MapperScan 不注册（单测扫描器会掩盖此缺陷）")
	void mapperCarriesRepositoryForProductionScan() {
		assertTrue(AiclawCommandPolicyMapper.class.isAnnotationPresent(Repository.class),
				"MybatisAutoConfiguration 只注册 @Repository mapper");
	}

	private static final Long AGENT = 30L;
	private static final Long OWNER = 11L;
	private static final Long MEMBER = 22L;
	private static final Long OUTSIDER = 33L;
	private static final long GROUP = 20L;

	@BeforeAll
	static void initTableInfoCache() {
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
		assistant.setCurrentNamespace(AiclawCommandPolicyMapper.class.getName());
		TableInfoHelper.initTableInfo(assistant, AiclawCommandPolicy.class);
	}

	private void noRows() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);
		when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
	}

	private void rows(AiclawCommandPolicy... rows) {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);
		when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rows));
	}

	private static AiclawCommandPolicy row(long scope, String capability, String state, String mode, String allowed) {
		AiclawCommandPolicy row = AiclawCommandPolicy.builder().aiclawUid(AGENT).roomId(scope)
				.capabilityId(capability).state(state).mode(mode).allowedUids(allowed).version(3).build();
		row.setId(1L);
		return row;
	}

	@Test
	@DisplayName("#504 默认规则：skill 与四个原生命令开放，其余 command 关闭")
	void defaults_openEntryPoints_closedOthers() {
		noRows();
		assertTrue(service.decide(AGENT, 0, "skill:run503-native-proof", MEMBER, false).isOpen());
		assertTrue(service.decide(AGENT, GROUP, "session.reset", MEMBER, false).isOpen());
		assertTrue(service.decide(AGENT, GROUP, "session.compact", MEMBER, false).isOpen());
		assertTrue(service.decide(AGENT, 0, "model.list", MEMBER, false).isOpen());
		assertTrue(service.decide(AGENT, 0, "model.set", MEMBER, false).isOpen());
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, GROUP, "command:init", MEMBER, false).getVerdict());
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, 0, "command:whatever", MEMBER, false).getVerdict());
	}

	@Test
	@DisplayName("#504 默认规则行 close：全作用域拒绝")
	void defaultRowClosed_deniesEverywhere() {
		rows(row(0, "session.reset", "closed", null, null));
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, 0, "session.reset", MEMBER, false).getVerdict());
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, GROUP, "session.reset", MEMBER, false).getVerdict());
		// 主人同样受 closed 约束。
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, GROUP, "session.reset", OWNER, true).getVerdict());
	}

	@Test
	@DisplayName("#504 群覆盖 open+all：群成员放行；默认规则仍管私聊")
	void groupRowOpenAll_memberAdmitted() {
		rows(row(GROUP, "skill:x", "open", "all", null));
		assertTrue(service.decide(AGENT, GROUP, "skill:x", MEMBER, false).isOpen());
		// 私聊走默认（skill 默认开放），覆盖行不影响。
		assertTrue(service.decide(AGENT, 0, "skill:x", MEMBER, false).isOpen());
	}

	@Test
	@DisplayName("#504 群覆盖 open+list：名单外拒绝且不回落默认（含主人）；私聊忽略名单")
	void groupRowList_offlistDenied_ownerPasses_dmIgnoresList() {
		rows(row(GROUP, "skill:x", "open", "list", "[22]"));
		assertTrue(service.decide(AGENT, GROUP, "skill:x", MEMBER, false).isOpen());
		AiclawCommandPolicyService.Decision off = service.decide(AGENT, GROUP, "skill:x", OUTSIDER, false);
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED, off.getVerdict());
		assertTrue(off.getReason().contains("offlist"));
		// F-504-D6：主人名单外同样拒绝（不豁免、不回落默认）。
		AiclawCommandPolicyService.Decision ownerOff = service.decide(AGENT, GROUP, "skill:x", OWNER, true);
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED, ownerOff.getVerdict());
		assertTrue(ownerOff.getReason().contains("offlist"));
		// 私聊忽略名单：非名单用户仍按默认开放。
		assertTrue(service.decide(AGENT, 0, "skill:x", OUTSIDER, false).isOpen());
	}

	@Test
	@DisplayName("#504 群覆盖 approval+list：名单外主人也拒绝；名单内主人自批、他人挂起")
	void groupApprovalList_offlistDeniedEvenOwner_inlistOwnerSelfApproves() {
		rows(row(GROUP, "session.reset", "approval_required", "list", "[22]"));
		// 名单外主人：先过名单 → 拒绝（自批不豁免名单）。
		AiclawCommandPolicyService.Decision ownerOff = service.decide(AGENT, GROUP, "session.reset", OWNER, true);
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED, ownerOff.getVerdict());
		assertTrue(ownerOff.getReason().contains("offlist"));
		// 名单外他人：同样拒绝。
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, GROUP, "session.reset", OUTSIDER, false).getVerdict());
		// 名单内主人：自批放行。
		assertTrue(service.decide(AGENT, GROUP, "session.reset", MEMBER, true).isOpen());
		// 名单内他人：挂起（本票拒绝终态）。
		assertEquals(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED,
				service.decide(AGENT, GROUP, "session.reset", MEMBER, false).getVerdict());
	}

	@Test
	@DisplayName("#504 approval 行：主人自批放行，他人挂起（本票拒绝终态）")
	void approvalRow_ownerSelfApproves_otherPends() {
		rows(row(0, "session.reset", "approval_required", null, null));
		assertTrue(service.decide(AGENT, GROUP, "session.reset", OWNER, true).isOpen());
		assertEquals(AiclawCommandPolicyService.Verdict.APPROVAL_REQUIRED,
				service.decide(AGENT, GROUP, "session.reset", MEMBER, false).getVerdict());
	}

	@Test
	@DisplayName("#504 缓存命中不再查库")
	void cacheHit_skipsDb() {
		AiclawCommandPolicy cached = row(0, "session.reset", "closed", null, null);
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(JSONUtil.toJsonStr(List.of(cached)));
		assertEquals(AiclawCommandPolicyService.Verdict.CLOSED,
				service.decide(AGENT, GROUP, "session.reset", MEMBER, false).getVerdict());
		verify(mapper, never()).selectList(any());
	}

	@Test
	@DisplayName("#504 非主人读写均拒绝")
	void nonOwner_forbidden() {
		when(ownerCache.getOwnerUid(AGENT)).thenReturn(OWNER);
		assertThrows(BizException.class, () -> service.readForOwner(MEMBER, AGENT, null));
		AiclawCommandPolicyUpdateReq req = new AiclawCommandPolicyUpdateReq();
		req.setAiclawUid(AGENT);
		req.setItems(List.of());
		assertThrows(BizException.class, () -> service.updateForOwner(MEMBER, req));
	}

	@Test
	@DisplayName("#504 主人新建行 + 版本冲突拒绝")
	void ownerCreate_thenStaleVersionConflict() {
		when(ownerCache.getOwnerUid(AGENT)).thenReturn(OWNER);
		// P1-3 群覆盖写 membership：群存在 + 操作者在群 + Agent 在群（直查 DB 范式）。
		RoomGroup rg = mock(RoomGroup.class);
		when(rg.getId()).thenReturn(7L);
		when(roomGroupCache.getByRoomIdFromDb(GROUP)).thenReturn(rg);
		GroupMember anyone = mock(GroupMember.class);
		when(groupMemberDao.getMemberByGroupId(7L, OWNER)).thenReturn(anyone);
		when(groupMemberDao.getMemberByGroupId(7L, AGENT)).thenReturn(anyone);
		// 更新路径不读缓存（写后直接驱逐），此处无需 stub redis。
		AiclawCommandPolicyUpdateReq.Item item = new AiclawCommandPolicyUpdateReq.Item();
		item.setCapabilityId("skill:x");
		item.setState("open");
		item.setMode("list");
		item.setAllowedUids(List.of(MEMBER));
		AiclawCommandPolicyUpdateReq req = new AiclawCommandPolicyUpdateReq();
		req.setAiclawUid(AGENT);
		req.setRoomId(GROUP);
		req.setItems(List.of(item));

		when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
		when(mapper.insert(any(AiclawCommandPolicy.class))).thenReturn(1);
		service.updateForOwner(OWNER, req);
		verify(mapper).insert(argThat((AiclawCommandPolicy row) ->
				"[22]".equals(row.getAllowedUids()) && Integer.valueOf(0).equals(row.getVersion())));
		verify(stringRedisTemplate).delete(anyString());

		// 已有行：版本失配拒绝。
		AiclawCommandPolicy existing = row(GROUP, "skill:x", "open", "list", "[22]");
		when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
		item.setVersion(999);
		assertThrows(BizException.class, () -> service.updateForOwner(OWNER, req));
		// 版本命中但 CAS 影响 0 行 → 并发冲突。
		item.setVersion(3);
		when(mapper.updateStateCas(any(), any(), any(), any(), any())).thenReturn(0);
		assertThrows(BizException.class, () -> service.updateForOwner(OWNER, req));
		// 正常更新。
		when(mapper.updateStateCas(any(), any(), any(), any(), any())).thenReturn(1);
		service.updateForOwner(OWNER, req);
	}

	@Test
	@DisplayName("#504 P1-3：非群 roomId 拒绝（与既有写路径风格一致）")
	void groupWrite_nonGroupRoom_rejected() {
		when(ownerCache.getOwnerUid(AGENT)).thenReturn(OWNER);
		when(roomGroupCache.getByRoomIdFromDb(GROUP)).thenReturn(null);
		BizException failure = assertThrows(BizException.class, () -> service.updateForOwner(OWNER, groupScopeReq()));
		assertEquals("该AI助理不在此群中", failure.getMessage());
		verifyNoInteractions(mapper);
	}

	@Test
	@DisplayName("#504 P1-3：操作者非群成员拒绝")
	void groupWrite_operatorNotMember_rejected() {
		when(ownerCache.getOwnerUid(AGENT)).thenReturn(OWNER);
		RoomGroup rg = mock(RoomGroup.class);
		when(rg.getId()).thenReturn(7L);
		when(roomGroupCache.getByRoomIdFromDb(GROUP)).thenReturn(rg);
		when(groupMemberDao.getMemberByGroupId(7L, OWNER)).thenReturn(null);
		BizException failure = assertThrows(BizException.class, () -> service.updateForOwner(OWNER, groupScopeReq()));
		assertTrue(failure.getMessage().contains("无法修改命令权限"));
		verifyNoInteractions(mapper);
	}

	@Test
	@DisplayName("#504 P1-3：Agent 已退群拒绝")
	void groupWrite_agentNotMember_rejected() {
		when(ownerCache.getOwnerUid(AGENT)).thenReturn(OWNER);
		RoomGroup rg = mock(RoomGroup.class);
		when(rg.getId()).thenReturn(7L);
		when(roomGroupCache.getByRoomIdFromDb(GROUP)).thenReturn(rg);
		when(groupMemberDao.getMemberByGroupId(7L, OWNER)).thenReturn(mock(GroupMember.class));
		when(groupMemberDao.getMemberByGroupId(7L, AGENT)).thenReturn(null);
		BizException failure = assertThrows(BizException.class, () -> service.updateForOwner(OWNER, groupScopeReq()));
		assertEquals("该AI助理不在此群中", failure.getMessage());
		verifyNoInteractions(mapper);
	}

	private static AiclawCommandPolicyUpdateReq groupScopeReq() {
		AiclawCommandPolicyUpdateReq.Item item = new AiclawCommandPolicyUpdateReq.Item();
		item.setCapabilityId("skill:x");
		item.setState("open");
		item.setMode("list");
		item.setAllowedUids(List.of(MEMBER));
		AiclawCommandPolicyUpdateReq req = new AiclawCommandPolicyUpdateReq();
		req.setAiclawUid(AGENT);
		req.setRoomId(GROUP);
		req.setItems(List.of(item));
		return req;
	}

	@Test
	@DisplayName("#504 permissionsFor：closed 移除、approval 标注")
	void permissionsFor_filtersClosed_marksApproval() {
		rows(row(GROUP, "skill:x", "open", "all", null),
				row(GROUP, "session.reset", "approval_required", null, null),
				row(GROUP, "model.list", "closed", null, null));
		Map<String, String> permissions = service.permissionsFor(AGENT, GROUP, MEMBER, false,
				List.of("skill:x", "session.reset", "model.list"));
		assertEquals("open", permissions.get("skill:x"));
		assertEquals("approval_required", permissions.get("session.reset"));
		assertFalse(permissions.containsKey("model.list"));
	}
}
