package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.AiclawFriendExtDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.mapper.AiclawMapper;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.entity.Aiclaw;
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

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawActivateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawActivateResp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AiclawServiceImpl 单元测试 —— REQ-009 #83: reportAgentType。
 *
 * <p>连接后由 plugins 上报 agent 类型，覆写 im_aiclaw.adapter_type。
 * 这里固化三条契约：正常覆写、空值保护(no-op)、未知 aiclaw 优雅返回。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiclawServiceImplTest {

	@Mock private AiclawDao aiclawDao;
	@Mock private UserDao userDao;
	@Mock private UserFriendDao userFriendDao;
	@Mock private FriendService friendService;
	@Mock private RoomService roomService;
	@Mock private ChatService chatService;
	@Mock private RoomFriendDao roomFriendDao;
	@Mock private MessageDao messageDao;
	@Mock private OnlineService onlineService;
	@Mock private AiclawFriendExtDao aiclawFriendExtDao;
	@Mock private UserSummaryCache userSummaryCache;
	@Mock private AiclawCryptoService cryptoService;
	@Mock private AiclawOwnerCache aiclawOwnerCache;
	@Mock private StringRedisTemplate stringRedisTemplate;

	@InjectMocks
	private AiclawServiceImpl aiclawService;

	private static final Long AICLAW_UID = 100L;

	/**
	 * 初始化 Aiclaw 的 MyBatis-Plus TableInfo 缓存，
	 * 使 LambdaUpdateWrapper 能在纯单元测试中解析 lambda 列名并物化 SQL，
	 * 从而让断言可读取绑定参数。
	 */
	@BeforeAll
	static void initTableInfoCache() {
		MapperBuilderAssistant assistant =
				new MapperBuilderAssistant(new Configuration(), "");
		assistant.setCurrentNamespace(AiclawMapper.class.getName());
		TableInfoHelper.initTableInfo(assistant, Aiclaw.class);
	}

	private Aiclaw existingAiclaw(String adapterType) {
		return Aiclaw.builder()
				.uid(AICLAW_UID)
				.ownerUid(200L)
				.adapterType(adapterType)
				.build();
	}

	@Test
	@DisplayName("reportAgentType: 已存在的 aiclaw 上报 opencode → 落库 adapter_type=opencode")
	void reportAgentType_existingAiclaw_updatesAdapterType() {
		// 既有 adapter_type 是创建期的 openclaw，应被上报值覆写（自愈）
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw"));
		when(aiclawDao.update(any())).thenReturn(true);

		aiclawService.reportAgentType(AICLAW_UID, "opencode");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaUpdateWrapper<Aiclaw>> captor =
				ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
		verify(aiclawDao).update(captor.capture());

		LambdaUpdateWrapper<Aiclaw> wrapper = captor.getValue();
		// 物化 SQL：触发 lambda 列名解析与绑定参数填充
		// SET 子句（getSqlSet）应作用于 adapter_type 列；WHERE 子句（getTargetSql）按 uid 定位
		assertTrue(wrapper.getSqlSet().contains("adapter_type"), "更新应作用于 adapter_type 列");
		assertTrue(wrapper.getTargetSql().contains("uid"), "更新应按 uid 定位行");
		// 绑定参数应同时含上报值与目标 uid（只动这一个 aiclaw 自己的行）
		assertTrue(wrapper.getParamNameValuePairs().containsValue("opencode"),
				"应把 adapter_type 设为上报值 opencode");
		assertTrue(wrapper.getParamNameValuePairs().containsValue(AICLAW_UID),
				"应只更新传入 uid 的行");
		// 缓存刷新，保持与 DB 一致
		verify(aiclawOwnerCache).refresh(AICLAW_UID);
	}

	@Test
	@DisplayName("reportAgentType: agentType=null → no-op，不触发任何更新（保留最后已知类型）")
	void reportAgentType_null_noOp() {
		aiclawService.reportAgentType(AICLAW_UID, null);

		verify(aiclawDao, never()).getByUid(any());
		verify(aiclawDao, never()).update(any());
		verify(aiclawOwnerCache, never()).refresh(any());
	}

	@Test
	@DisplayName("reportAgentType: agentType=\"\" → no-op，不触发任何更新")
	void reportAgentType_empty_noOp() {
		aiclawService.reportAgentType(AICLAW_UID, "");

		verify(aiclawDao, never()).update(any());
		verify(aiclawOwnerCache, never()).refresh(any());
	}

	@Test
	@DisplayName("reportAgentType: agentType=\"  \"(空白) → no-op，不触发任何更新")
	void reportAgentType_blank_noOp() {
		aiclawService.reportAgentType(AICLAW_UID, "  ");

		verify(aiclawDao, never()).update(any());
		verify(aiclawOwnerCache, never()).refresh(any());
	}

	@Test
	@DisplayName("reportAgentType: aiclaw 不存在 → 不更新、不抛错（优雅返回，绝不新建）")
	void reportAgentType_aiclawNotFound_graceful() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(null);

		assertDoesNotThrow(() -> aiclawService.reportAgentType(AICLAW_UID, "opencode"));

		verify(aiclawDao, never()).update(any());
		verify(aiclawOwnerCache, never()).refresh(any());
	}

	// ==================== REQ-122: 激活机器码查重 ====================

	private static final String CONNECTION_TOKEN = "conn-token-abcdef";

	/**
	 * 构造一份可被 activate() 消费的解密载荷，并把 self aiclaw 装配好
	 * （auth_status=0 未激活 + 与 connectionToken 匹配的 bcrypt hash），
	 * 让流程能走到「机器码查重」这一步。
	 */
	private Aiclaw stubActivatablePath(String machineCode) {
		JSONObject payload = new JSONObject();
		payload.set("uid", AICLAW_UID);
		payload.set("connectionToken", CONNECTION_TOKEN);
		payload.set("timestamp", System.currentTimeMillis());
		when(cryptoService.decryptActivationToken("act-token")).thenReturn(payload);

		Aiclaw self = existingAiclaw("openclaw");
		self.setId(1L);
		self.setAuthStatus(0);
		self.setTokenHash(BCrypt.hashpw(CONNECTION_TOKEN));
		self.setTokenPrefix("pref1234");
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(self);
		return self;
	}

	private AiclawActivateReq activateReq(String machineCode) {
		AiclawActivateReq req = new AiclawActivateReq();
		req.setActivationToken("act-token");
		req.setMachineCode(machineCode);
		return req;
	}

	@Test
	@DisplayName("activate: machineCode 已被另一个 aiclaw 占用 → 抛 BizException，绝不绑定/覆盖")
	void activate_machineCodeHeldByAnother_rejected() {
		stubActivatablePath("dup-machine");
		// 另一个 uid 已占用该机器码
		Aiclaw other = Aiclaw.builder().uid(999L).build();
		when(aiclawDao.getOtherHolderByMachineCode("dup-machine", AICLAW_UID)).thenReturn(other);

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.activate(activateReq("dup-machine")));
		assertTrue(ex.getMessage().contains("已被其他AI助理占用"), "应给出机器码占用的明确错误");

		// 失败要 LOUD：不得写库、不得刷缓存
		verify(aiclawDao, never()).updateById(any());
		verify(aiclawOwnerCache, never()).refresh(any());
	}

	@Test
	@DisplayName("activate: machineCode 无其他占用 → 正常激活并绑定机器码")
	void activate_machineCodeFree_proceeds() {
		stubActivatablePath("free-machine");
		// 无其他占用者
		when(aiclawDao.getOtherHolderByMachineCode("free-machine", AICLAW_UID)).thenReturn(null);
		// saveTokenCache 会写 Redis，桩掉 opsForValue 避免 NPE
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

		AiclawActivateResp resp = aiclawService.activate(activateReq("free-machine"));

		assertEquals(AICLAW_UID, resp.getUid());
		// 走过查重且落库激活
		verify(aiclawDao).getOtherHolderByMachineCode("free-machine", AICLAW_UID);
		verify(aiclawDao).updateById(any());
		verify(aiclawOwnerCache).refresh(AICLAW_UID);
	}
}
