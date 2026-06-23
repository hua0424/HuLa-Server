package com.luohuo.flex.im.core.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
}
