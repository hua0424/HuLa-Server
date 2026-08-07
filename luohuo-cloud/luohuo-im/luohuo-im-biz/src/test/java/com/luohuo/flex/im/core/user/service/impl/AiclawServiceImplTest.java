package com.luohuo.flex.im.core.user.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.service.SysConfigService;
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
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.entity.UserFriend;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawReportHostInfoReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawFriendResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawListResp;
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

import java.util.List;
import java.util.Set;

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
	@Mock private PushService pushService;
	@Mock private com.luohuo.flex.im.core.user.service.cache.UserCache userCache;
	@Mock private com.luohuo.basic.cache.repository.CachePlusOps cachePlusOps;
	@Mock private SysConfigService sysConfigService;

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

	// ==================== #188 T1: updateProfile 缓存失效 ====================

	private static final Long OWNER_UID = 200L;

	private Aiclaw ownedAiclaw() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setId(1L);
		when(aiclawDao.getByOwnerAndUid(OWNER_UID, AICLAW_UID)).thenReturn(aiclaw);
		return aiclaw;
	}

	@Test
	@DisplayName("updateProfile: 落库后必须删除 userSummaryCache（否则好友列表/消息读到陈旧名称/头像/简介）")
	void updateProfile_invalidatesUserSummaryCache() {
		ownedAiclaw();
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		aiclawService.updateProfile(req, OWNER_UID);

		verify(userDao).updateById(any());
		verify(userSummaryCache, times(1)).delete(AICLAW_UID);
	}

	// ==================== #192: updateProfile 资料变更 WS 推送 ====================

	@Test
	@DisplayName("#192 updateProfile: 必须同时删除 userCache（合并消息预览读面，对齐 UserServiceImpl.modifyInfo 双删）")
	void updateProfile_invalidatesUserCache() {
		ownedAiclaw();
		when(cachePlusOps.sMembers(any())).thenReturn(java.util.Collections.emptySet());
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		aiclawService.updateProfile(req, OWNER_UID);

		verify(userCache, times(1)).delete(AICLAW_UID);
	}

	@Test
	@DisplayName("#192 updateProfile: 推送 userInfoChange(profile) 帧，目标 = 反向好友 ∪ {本人} ∪ {owner}（P2-1 显式含 ownerUid），uid 以 String 承载")
	@SuppressWarnings("unchecked")
	void updateProfile_pushesUserInfoChangeToFriendsAndSelf() {
		ownedAiclaw();
		java.util.Set<Object> reverseFriends = new java.util.HashSet<>(java.util.Arrays.asList("300", "301"));
		when(cachePlusOps.sMembers(any())).thenReturn(reverseFriends);
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		aiclawService.updateProfile(req, OWNER_UID);

		ArgumentCaptor<com.luohuo.flex.model.entity.WsBaseResp> msgCaptor =
				ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
		ArgumentCaptor<java.util.List<Long>> listCaptor = ArgumentCaptor.forClass(java.util.List.class);
		verify(pushService, times(1)).sendPushMsg(msgCaptor.capture(), listCaptor.capture(), eq(OWNER_UID));

		com.luohuo.flex.model.entity.WsBaseResp<?> frame = msgCaptor.getValue();
		assertEquals("userInfoChange", frame.getType(), "帧类型应为 userInfoChange");
		assertTrue(frame.getData() instanceof com.luohuo.flex.model.entity.ws.WSUserInfoChange,
				"帧载荷应为 WSUserInfoChange");
		com.luohuo.flex.model.entity.ws.WSUserInfoChange data =
				(com.luohuo.flex.model.entity.ws.WSUserInfoChange) frame.getData();
		assertEquals(String.valueOf(AICLAW_UID), data.getUid(),
				"uid 应以 String 承载（防 JS 精度丢失）");
		assertEquals(com.luohuo.flex.model.entity.ws.WSUserInfoChange.PROFILE, data.getChangeType(),
				"changeType 应为 profile");
		java.util.List<Long> targets = listCaptor.getValue();
		assertTrue(targets.contains(AICLAW_UID), "推送目标应含本人 uid");
		assertTrue(targets.contains(300L) && targets.contains(301L), "推送目标应含全部反向好友");
		assertTrue(targets.contains(OWNER_UID), "P2-1: 推送目标应显式含 ownerUid（防御 owner 已解除好友关系边界）");
		assertEquals(4, targets.size(), "推送目标 = 反向好友 ∪ {本人} ∪ {owner}，不多不少");
	}

	// ==================== #192 P2-1: updateProfile 推送目标显式含 ownerUid ====================

	@Test
	@DisplayName("#192 P2-1 updateProfile: owner 不在反向好友集（已解除好友关系）也必须收到自己 aiclaw 的资料变更推送")
	@SuppressWarnings("unchecked")
	void updateProfile_pushesToOwnerEvenIfNotReverseFriend() {
		ownedAiclaw();
		// 防御边界：owner 已删除与 aiclaw 的好友关系 → 反向好友集不含 owner(200)
		java.util.Set<Object> reverseFriends = new java.util.HashSet<>(java.util.Arrays.asList("300", "301"));
		when(cachePlusOps.sMembers(any())).thenReturn(reverseFriends);
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		aiclawService.updateProfile(req, OWNER_UID);

		ArgumentCaptor<java.util.List<Long>> listCaptor = ArgumentCaptor.forClass(java.util.List.class);
		verify(pushService, times(1)).sendPushMsg(any(), listCaptor.capture(), eq(OWNER_UID));
		java.util.List<Long> targets = listCaptor.getValue();
		assertTrue(targets.contains(OWNER_UID),
				"推送目标必须显式含 ownerUid——owner 必须收到自己 aiclaw 的资料变更，即使已解除好友关系");
		assertTrue(targets.contains(AICLAW_UID) && targets.contains(300L) && targets.contains(301L),
				"推送目标仍应含本人 + 全部反向好友");
		assertEquals(4, targets.size(), "推送目标 = 反向好友 ∪ {本人} ∪ {owner}，Set 去重");
	}

	// ==================== #192 P2-3: 推送计算异常降级（Redis 故障只丢推送，不回滚写路径） ====================

	@Test
	@DisplayName("#192 P2-3 updateProfile: 推送计算异常（sMembers 抛）只丢推送，写路径照常（updateById + 双缓存删除）")
	void updateProfile_pushComputationFails_writePathStillSucceeds() {
		ownedAiclaw();
		when(cachePlusOps.sMembers(any())).thenThrow(new RuntimeException("redis down"));
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		// 推送=低延迟优化非正确性依赖：Redis 故障绝不回滚写路径事务（catch 后不得重抛）
		assertDoesNotThrow(() -> aiclawService.updateProfile(req, OWNER_UID));

		verify(userDao).updateById(any());
		verify(userSummaryCache).delete(AICLAW_UID);
		verify(userCache).delete(AICLAW_UID);
	}

	@Test
	@DisplayName("#192 P2-3 updateProfile: sendPushMsg 抛异常同样降级，方法正常返回")
	void updateProfile_sendPushMsgFails_writePathStillSucceeds() {
		ownedAiclaw();
		when(cachePlusOps.sMembers(any())).thenReturn(java.util.Collections.emptySet());
		doThrow(new RuntimeException("push down")).when(pushService)
				.sendPushMsg(any(), any(java.util.List.class), any());
		com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq req =
				com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq.builder()
						.uid(AICLAW_UID)
						.name("新名字")
						.build();

		assertDoesNotThrow(() -> aiclawService.updateProfile(req, OWNER_UID));

		verify(userDao).updateById(any());
	}

	// ==================== #188 T2: 自作用域拉取 getSelfPersona ====================

	@Test
	@DisplayName("getSelfPersona: aiclaw 存在且有人设 → 返回其人设")
	void getSelfPersona_existingWithPersona_returnsPersona() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setPublicPersona("你是一个严谨的助手");
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);

		com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawPersonaResp resp =
				aiclawService.getSelfPersona(AICLAW_UID);

		assertNotNull(resp);
		assertEquals("你是一个严谨的助手", resp.getPublicPersona());
	}

	@Test
	@DisplayName("getSelfPersona: 人设为 null（未设置/已清空）→ null 透传，不报错")
	void getSelfPersona_nullPersona_passesThroughNull() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setPublicPersona(null);
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);

		com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawPersonaResp resp =
				aiclawService.getSelfPersona(AICLAW_UID);

		assertNotNull(resp);
		assertNull(resp.getPublicPersona());
	}

	@Test
	@DisplayName("getSelfPersona: aiclaw 不存在 → 抛 BizException(\"AI助理不存在\")")
	void getSelfPersona_notFound_throwsBizException() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.getSelfPersona(AICLAW_UID));
		assertTrue(ex.getMessage().contains("AI助理不存在"));
	}

	// ==================== #188 T3: setPersona WS 失效推送 ====================

	@Test
	@DisplayName("setPersona: 落库后向该 aiclaw 推送 aiclawPersonaChanged 帧（低延迟失效优化）")
	@SuppressWarnings("unchecked")
	void setPersona_pushesInvalidationFrame() {
		ownedAiclaw();

		aiclawService.setPersona(AICLAW_UID, "新人设", OWNER_UID);

		ArgumentCaptor<com.luohuo.flex.model.entity.WsBaseResp> msgCaptor =
				ArgumentCaptor.forClass(com.luohuo.flex.model.entity.WsBaseResp.class);
		ArgumentCaptor<java.util.List<Long>> listCaptor = ArgumentCaptor.forClass(java.util.List.class);
		verify(pushService, times(1)).sendPushMsg(msgCaptor.capture(), listCaptor.capture(), eq(OWNER_UID));

		com.luohuo.flex.model.entity.WsBaseResp<?> frame = msgCaptor.getValue();
		assertEquals("aiclawPersonaChanged", frame.getType(), "帧类型应为 aiclawPersonaChanged");
		assertTrue(frame.getData() instanceof com.luohuo.flex.model.entity.ws.WSAiclawPersonaChanged,
				"帧载荷应为 WSAiclawPersonaChanged");
		com.luohuo.flex.model.entity.ws.WSAiclawPersonaChanged data =
				(com.luohuo.flex.model.entity.ws.WSAiclawPersonaChanged) frame.getData();
		assertEquals(String.valueOf(AICLAW_UID), data.getAiclawUid(),
				"aiclawUid 应以 String 承载（防 JS 精度丢失）");
		assertTrue(listCaptor.getValue().contains(AICLAW_UID), "推送目标应含该 aiclawUid");
	}

	@Test
	@DisplayName("setPersona: 清空人设（空串→存 null）也必须推送失效帧")
	void setPersona_clearPersona_alsoPushes() {
		ownedAiclaw();

		aiclawService.setPersona(AICLAW_UID, "", OWNER_UID);

		// 清空语义：走 LambdaUpdateWrapper 显式 set（updateById 的 NOT_NULL 策略会吞掉 null 列）
		verify(aiclawDao).update(any(LambdaUpdateWrapper.class));
		// 失效推送不省略——plugins 必须得知人设已清空
		verify(pushService, times(1)).sendPushMsg(any(), any(java.util.List.class), eq(OWNER_UID));
	}

	// ==================== #188 P1: setPersona 清空人设必须真正落库 ====================

	/**
	 * 捕获 setPersona 传给 {@code aiclawDao.update(...)} 的 LambdaUpdateWrapper。
	 */
	@SuppressWarnings("unchecked")
	private LambdaUpdateWrapper<Aiclaw> capturePersonaUpdateWrapper() {
		ArgumentCaptor<LambdaUpdateWrapper<Aiclaw>> captor =
				ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
		verify(aiclawDao).update(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("#188 P1 setPersona: 清空人设 → UPDATE 显式含 public_persona=null（NOT_NULL 策略吞 null 列的回归证据）")
	void setPersona_clearPersona_updateSqlExplicitlySetsNull() {
		ownedAiclaw();

		aiclawService.setPersona(AICLAW_UID, "", OWNER_UID);

		LambdaUpdateWrapper<Aiclaw> wrapper = capturePersonaUpdateWrapper();
		// 关键断言：SET 子句必须显式出现 public_persona 列、且其绑定值为 null。
		// updateById + 全局 NOT_NULL 策略会把 null 列整个排除出 UPDATE（静默无效），
		// 只有显式 .set(col, null) 才会把 public_persona 保留在 SET 中。
		String sqlSet = wrapper.getSqlSet();
		assertTrue(sqlSet.contains("public_persona="),
				"清空人设必须把 public_persona 显式留在 SET 子句，实际 SET: " + sqlSet);
		assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
				"public_persona 的绑定值必须为 null（显式清空），实际参数: "
						+ wrapper.getParamNameValuePairs());
		assertTrue(wrapper.getTargetSql().contains("uid"), "更新应按 uid 定位行");
		assertTrue(wrapper.getParamNameValuePairs().containsValue(AICLAW_UID),
				"应只更新传入 uid 的行");
		// 清空也要推送失效帧（plugins 重拉拿到 null）
		verify(pushService, times(1)).sendPushMsg(any(), any(java.util.List.class), eq(OWNER_UID));
	}

	@Test
	@DisplayName("#188 P1 setPersona: 非空人设走同一 LambdaUpdateWrapper 且 SET 含设定值")
	void setPersona_nonEmptyPersona_updateSqlSetsValue() {
		ownedAiclaw();

		aiclawService.setPersona(AICLAW_UID, "新人设", OWNER_UID);

		LambdaUpdateWrapper<Aiclaw> wrapper = capturePersonaUpdateWrapper();
		assertTrue(wrapper.getSqlSet().contains("public_persona"),
				"更新应作用于 public_persona 列，实际 SET: " + wrapper.getSqlSet());
		assertTrue(wrapper.getParamNameValuePairs().containsValue("新人设"),
				"应把 public_persona 设为传入值");
		// 物化 WHERE 子句，确保 eq 条件绑定参数落进 paramNameValuePairs
		assertTrue(wrapper.getTargetSql().contains("uid"), "更新应按 uid 定位行");
		assertTrue(wrapper.getParamNameValuePairs().containsValue(AICLAW_UID),
				"应只更新传入 uid 的行");
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

	// ==================== #193: reportHostInfo 写 adapter_config（按字段合并） ====================

	private AiclawReportHostInfoReq hostInfoReq(String hostname, String ip, String workspaceBase) {
		return AiclawReportHostInfoReq.builder()
				.hostname(hostname).ip(ip).workspaceBase(workspaceBase).build();
	}

	/** 捕获 update wrapper 并取出 SET 的 adapter_config 绑定值（JSON 串）解析为 JSONObject。 */
	@SuppressWarnings("unchecked")
	private JSONObject captureWrittenAdapterConfig() {
		ArgumentCaptor<LambdaUpdateWrapper<Aiclaw>> captor =
				ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
		verify(aiclawDao).update(captor.capture());
		LambdaUpdateWrapper<Aiclaw> wrapper = captor.getValue();
		assertTrue(wrapper.getSqlSet().contains("adapter_config"),
				"更新必须走 LambdaUpdateWrapper 显式 set adapter_config 列，实际 SET: " + wrapper.getSqlSet());
		assertTrue(wrapper.getTargetSql().contains("uid"), "更新应按 uid 定位行");
		assertTrue(wrapper.getParamNameValuePairs().containsValue(AICLAW_UID), "应只更新传入 uid 的行");
		String json = wrapper.getParamNameValuePairs().values().stream()
				.filter(v -> v instanceof String && ((String) v).startsWith("{"))
				.map(v -> (String) v).findFirst()
				.orElseThrow(() -> new AssertionError("SET 绑定参数中应含 adapter_config JSON 串: "
						+ wrapper.getParamNameValuePairs()));
		return JSONUtil.parseObj(json);
	}

	@Test
	@DisplayName("reportHostInfo: 空 adapter_config + 全字段上报 → 三个字段全部写入")
	void reportHostInfo_emptyConfig_allFieldsWritten() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw")); // adapterConfig=null

		aiclawService.reportHostInfo(AICLAW_UID, hostInfoReq("dev-box", "10.0.0.1", "/data/aichat"));

		JSONObject written = captureWrittenAdapterConfig();
		assertEquals("dev-box", written.getStr("hostname"));
		assertEquals("10.0.0.1", written.getStr("ip"));
		assertEquals("/data/aichat", written.getStr("workspaceBase"));
	}

	@Test
	@DisplayName("reportHostInfo: 已有值 + blank 字段 → blank 保留旧值、非 blank 覆写（按字段合并）")
	void reportHostInfo_blankFieldsKeepOld_nonBlankOverwrite() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setAdapterConfig("{\"hostname\":\"old-host\",\"ip\":\"1.1.1.1\",\"workspaceBase\":\"/old\"}");
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);

		aiclawService.reportHostInfo(AICLAW_UID, hostInfoReq("new-host", null, "  "));

		JSONObject written = captureWrittenAdapterConfig();
		assertEquals("new-host", written.getStr("hostname"), "非 blank 字段应覆写");
		assertEquals("1.1.1.1", written.getStr("ip"), "null 字段应保留旧值");
		assertEquals("/old", written.getStr("workspaceBase"), "空白字段应保留旧值");
	}

	@Test
	@DisplayName("reportHostInfo: 未知 uid → 不更新、不抛错（warn 优雅返回，对齐 reportAgentType）")
	void reportHostInfo_unknownUid_graceful() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(null);

		assertDoesNotThrow(() -> aiclawService.reportHostInfo(AICLAW_UID,
				hostInfoReq("dev-box", "10.0.0.1", "/data/aichat")));

		verify(aiclawDao, never()).update(any());
	}

	@Test
	@DisplayName("reportHostInfo: adapter_config 为非法 JSON → 按空对象合并不炸，上报字段正常写入")
	void reportHostInfo_invalidJson_treatedAsEmpty() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setAdapterConfig("not-json{{{");
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(aiclaw);

		assertDoesNotThrow(() -> aiclawService.reportHostInfo(AICLAW_UID,
				hostInfoReq("dev-box", null, "/data/aichat")));

		JSONObject written = captureWrittenAdapterConfig();
		assertEquals("dev-box", written.getStr("hostname"));
		assertEquals("/data/aichat", written.getStr("workspaceBase"));
	}

	@Test
	@DisplayName("reportHostInfo: 三个字段全 blank → no-op 不查库不落库（无任何可合并内容）")
	void reportHostInfo_allBlank_noOp() {
		aiclawService.reportHostInfo(AICLAW_UID, hostInfoReq(null, "", "  "));

		verify(aiclawDao, never()).getByUid(any());
		verify(aiclawDao, never()).update(any());
	}

	// ==================== #193: list 返回 hostname/ip/ownerWorkspaceDir ====================

	private void stubListPath(Aiclaw aiclaw) {
		when(aiclawDao.listByOwner(OWNER_UID)).thenReturn(List.of(aiclaw));
		User user = User.builder().name("bot").build();
		user.setId(AICLAW_UID);
		when(userDao.listByIds(any())).thenReturn(List.of(user));
		when(onlineService.getOnlineUsersList(any())).thenReturn(Set.of());
	}

	@Test
	@DisplayName("list: 已上报主机信息 → hostname/ip 透传，ownerWorkspaceDir=workspaceBase/uid/owner")
	void list_withHostInfo_returnsHostFieldsAndOwnerDir() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setAdapterConfig("{\"hostname\":\"dev-box\",\"ip\":\"10.0.0.1\",\"workspaceBase\":\"/data/aichat\"}");
		stubListPath(aiclaw);

		List<AiclawListResp> resps = aiclawService.list(OWNER_UID);

		assertEquals(1, resps.size());
		AiclawListResp resp = resps.get(0);
		assertEquals("dev-box", resp.getHostname());
		assertEquals("10.0.0.1", resp.getIp());
		assertEquals("/data/aichat/" + AICLAW_UID + "/owner", resp.getOwnerWorkspaceDir());
	}

	@Test
	@DisplayName("list: 未上报（adapter_config 为 null）→ hostname/ip/ownerWorkspaceDir 全 null")
	void list_withoutHostInfo_nullFields() {
		stubListPath(existingAiclaw("openclaw")); // adapterConfig=null

		List<AiclawListResp> resps = aiclawService.list(OWNER_UID);

		AiclawListResp resp = resps.get(0);
		assertNull(resp.getHostname());
		assertNull(resp.getIp());
		assertNull(resp.getOwnerWorkspaceDir(), "无 workspaceBase 时目录字段必须为 null");
	}

	@Test
	@DisplayName("list: 有 hostname/ip 但无 workspaceBase → hostname/ip 有值、ownerWorkspaceDir 为 null")
	void list_hostInfoWithoutWorkspaceBase_dirNull() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setAdapterConfig("{\"hostname\":\"dev-box\",\"ip\":\"10.0.0.1\"}");
		stubListPath(aiclaw);

		AiclawListResp resp = aiclawService.list(OWNER_UID).get(0);
		assertEquals("dev-box", resp.getHostname());
		assertNull(resp.getOwnerWorkspaceDir());
	}

	// ==================== #193: getFriends 返回 dmWorkspaceDir ====================

	private void stubFriendsPath(Aiclaw aiclaw, Long friendUid) {
		when(aiclawDao.getByOwnerAndUid(OWNER_UID, AICLAW_UID)).thenReturn(aiclaw);
		UserFriend uf = new UserFriend();
		uf.setUid(AICLAW_UID);
		uf.setFriendUid(friendUid);
		when(userFriendDao.list(org.mockito.ArgumentMatchers
				.<com.baomidou.mybatisplus.core.conditions.Wrapper<UserFriend>>any())).thenReturn(List.of(uf));
		when(onlineService.getOnlineUsersList(any())).thenReturn(Set.of());
		when(aiclawFriendExtDao.listByAiclaw(AICLAW_UID)).thenReturn(List.of());
	}

	@Test
	@DisplayName("getFriends: 有 workspaceBase → dmWorkspaceDir=workspaceBase/aiclawUid/dm/friendUid")
	void getFriends_withWorkspaceBase_returnsDmDir() {
		Aiclaw aiclaw = existingAiclaw("openclaw");
		aiclaw.setAdapterConfig("{\"workspaceBase\":\"/data/aichat\"}");
		stubFriendsPath(aiclaw, 300L);

		List<AiclawFriendResp> resps = aiclawService.getFriends(AICLAW_UID, OWNER_UID);

		assertEquals(1, resps.size());
		assertEquals("/data/aichat/" + AICLAW_UID + "/dm/300", resps.get(0).getDmWorkspaceDir());
	}

	@Test
	@DisplayName("getFriends: 无 workspaceBase → dmWorkspaceDir 为 null")
	void getFriends_withoutWorkspaceBase_dmDirNull() {
		stubFriendsPath(existingAiclaw("openclaw"), 300L); // adapterConfig=null

		List<AiclawFriendResp> resps = aiclawService.getFriends(AICLAW_UID, OWNER_UID);

		assertNull(resps.get(0).getDmWorkspaceDir());
	}

	// ==================== REQ-018 #217: getSelfPrompts agent prompt 模板下发 ====================

	private static final String PROMPT_REPLY_CONTRACT = "你是 HuLa 聊天会话里的 AI 助理。要把回复发送到当前聊天，你必须在 bash 中实际运行命令：{reply_command}。⚠️ 只有运行这条 bash 命令才会真正发送消息。";
	private static final String PROMPT_IDENTITY_ANCHOR = "你是本 HuLa 聊天会话的 AI 助理 {displayName}（uid {uid}）。凡系统路由到你这里的消息，都是在对你说话。";
	private static final String PROMPT_PERSONA_SECTION = "你的人设：\n{persona}";

	/** 3 个 key 全配时的 sysConfigService 桩：每个 key 返回各自模板原文。 */
	private void stubAllPromptsConfigured() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw"));
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT)).thenReturn(PROMPT_REPLY_CONTRACT);
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR)).thenReturn(PROMPT_IDENTITY_ANCHOR);
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_PERSONA_SECTION)).thenReturn(PROMPT_PERSONA_SECTION);
	}

	@Test
	@DisplayName("getSelfPrompts: 3 个模板 key 全配置 → 返回原文，key 名与顺序正确（占位符原样不渲染）")
	void getSelfPrompts_allConfigured_returnsOriginalText() {
		stubAllPromptsConfigured();

		java.util.Map<String, String> prompts = aiclawService.getSelfPrompts(AICLAW_UID);

		assertEquals(3, prompts.size(), "应恰好返回 3 个模板");
		assertEquals(PROMPT_REPLY_CONTRACT, prompts.get(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT));
		assertEquals(PROMPT_IDENTITY_ANCHOR, prompts.get(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR));
		assertEquals(PROMPT_PERSONA_SECTION, prompts.get(AiclawServiceImpl.PROMPT_KEY_PERSONA_SECTION));
		// key 名本身也要原样下发（plugins 侧按固定 key 消费）
		java.util.List<String> keys = new java.util.ArrayList<>(prompts.keySet());
		assertEquals(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT, keys.get(0));
		assertEquals(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR, keys.get(1));
		assertEquals(AiclawServiceImpl.PROMPT_KEY_PERSONA_SECTION, keys.get(2));
	}

	@Test
	@DisplayName("getSelfPrompts: 缺 agent.prompt.reply_contract（get 返回空串）→ BizException 指明缺失 key")
	void getSelfPrompts_missingReplyContract_throwsWithKey() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw"));
		// 只 stub 首个 key 为空串——实现按序读取，首个缺失即抛错，后面的 key 不会被读到（避免 Mockito 严格校验报多余 stub）
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT)).thenReturn("");

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.getSelfPrompts(AICLAW_UID));
		assertTrue(ex.getMessage().contains(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT),
				"错误信息必须指明缺哪个 key，实际: " + ex.getMessage());
	}

	@Test
	@DisplayName("getSelfPrompts: 缺 agent.prompt.identity_anchor → BizException 指明缺失 key")
	void getSelfPrompts_missingIdentityAnchor_throwsWithKey() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw"));
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT)).thenReturn(PROMPT_REPLY_CONTRACT);
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR)).thenReturn("");

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.getSelfPrompts(AICLAW_UID));
		assertTrue(ex.getMessage().contains(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR),
				"错误信息必须指明缺哪个 key，实际: " + ex.getMessage());
	}

	@Test
	@DisplayName("getSelfPrompts: 缺 agent.prompt.persona_section → BizException 指明缺失 key")
	void getSelfPrompts_missingPersonaSection_throwsWithKey() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(existingAiclaw("openclaw"));
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_REPLY_CONTRACT)).thenReturn(PROMPT_REPLY_CONTRACT);
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_IDENTITY_ANCHOR)).thenReturn(PROMPT_IDENTITY_ANCHOR);
		when(sysConfigService.get(AiclawServiceImpl.PROMPT_KEY_PERSONA_SECTION)).thenReturn("");

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.getSelfPrompts(AICLAW_UID));
		assertTrue(ex.getMessage().contains(AiclawServiceImpl.PROMPT_KEY_PERSONA_SECTION),
				"错误信息必须指明缺哪个 key，实际: " + ex.getMessage());
	}

	@Test
	@DisplayName("getSelfPrompts: aiclaw 不存在 → 抛 BizException(\"AI助理不存在\")（对齐 getSelfPersona 自作用域校验）")
	void getSelfPrompts_aiclawNotFound_throwsBizException() {
		when(aiclawDao.getByUid(AICLAW_UID)).thenReturn(null);

		BizException ex = assertThrows(BizException.class,
				() -> aiclawService.getSelfPrompts(AICLAW_UID));
		assertTrue(ex.getMessage().contains("AI助理不存在"));
		verify(sysConfigService, never()).get(anyString());
	}
}
