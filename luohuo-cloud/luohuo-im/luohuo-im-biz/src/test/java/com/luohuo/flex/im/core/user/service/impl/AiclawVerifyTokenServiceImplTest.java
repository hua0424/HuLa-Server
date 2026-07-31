package com.luohuo.flex.im.core.user.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.RoomService;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.AiclawFriendExtDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.core.user.dao.UserFriendDao;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.cache.UserSummaryCache;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #184(a) 方案 B: AiclawServiceImpl.verifyAndCacheToken 单元测试。
 *
 * <p>固化 gateway 缓存缺失回源路径的契约：
 * <ul>
 *   <li>有效 token → 重建缓存 + 返回身份信息</li>
 *   <li>记录不存在 / bcrypt 不匹配 / authStatus≠1 / 已停用 → 返回 null，不写缓存，打 WARN(prefix)</li>
 * </ul>
 * 风格镜像 {@link AiclawServiceImplTest}（@Mock 每个 dep + @InjectMocks）。
 * 不需要 @BeforeAll TableInfo 缓存 —— verifyAndCacheToken 只读不写，不物化 LambdaUpdateWrapper SQL。
 */
@ExtendWith(MockitoExtension.class)
class AiclawVerifyTokenServiceImplTest {

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

	/** 36 字符 UUID 格式 token（与生产 connectionToken 同形态），prefix="aaaaaaaa" */
	private static final String TOKEN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
	private static final String PREFIX = "aaaaaaaa";
	private static final String CACHE_KEY = "aiclaw:token:" + PREFIX;
	private static final Duration TTL = Duration.ofDays(7);

	/** bcrypt 慢 —— 类加载时算一次复用，避免每个 case 重复算。 */
	private static final String VALID_HASH = BCrypt.hashpw(TOKEN);

	@SuppressWarnings("unchecked")
	private ValueOperations<String, String> stubValueOps() {
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		return valueOps;
	}

	private Aiclaw validRecord() {
		return Aiclaw.builder()
				.uid(100L)
				.ownerUid(200L)
				.tokenHash(VALID_HASH)
				.tokenPrefix(PREFIX)
				.machineCode("m1")
				.authStatus(1)
				.tenantId(1L)
				.build();
	}

	@Test
	@DisplayName("有效 token → 返回身份信息并重建缓存（形态与 saveTokenCache 一致）")
	void validToken_returnsInfoAndRebuildsCache() {
		Aiclaw record = validRecord();
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenReturn(record);
		ValueOperations<String, String> valueOps = stubValueOps();

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		assertNotNull(info);
		assertEquals(100L, info.getUid());
		assertEquals(200L, info.getOwnerUid());
		assertEquals(1, info.getAuthStatus());
		assertEquals(1L, info.getTenantId());
		assertEquals("m1", info.getMachineCode());

		ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
		verify(valueOps).set(eq(CACHE_KEY), jsonCaptor.capture(), eq(TTL));
		String json = jsonCaptor.getValue();
		// 形态与 saveTokenCache 一致：含 uid/ownerUid/tenantId/authStatus/tokenSha256/machineCode
		String expectedSha = SecureUtil.sha256(TOKEN);
		assertTrue(json.contains("\"uid\":100"), "cache JSON 应含 uid");
		assertTrue(json.contains("\"tokenSha256\":\"" + expectedSha + "\""), "cache JSON 应含正确 tokenSha256");
		assertTrue(json.contains("\"tenantId\":1"), "cache JSON 应含 tenantId=1");
		assertTrue(json.contains("\"machineCode\":\"m1\""), "cache JSON 应含 machineCode");
	}

	@Test
	@DisplayName("记录不存在（含 is_del=1 被 @TableLogic 过滤）→ 返回 null，不写缓存，WARN 含 prefix")
	void recordNull_returnsNullAndLogsWarn() {
		// 注：is_del=1 的已删除行在单元测试中即表现为 getByTokenPrefix 返回 null
		// （@TableLogic 在 lambdaQuery 自动过滤；mock 直接返回 null 即可覆盖该路径）
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenReturn(null);

		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		Logger log = (Logger) LoggerFactory.getLogger(AiclawServiceImpl.class);
		log.addAppender(appender);
		appender.start();

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		appender.stop();
		assertNull(info);
		verify(stringRedisTemplate, never()).opsForValue();
		boolean hasRecordNotFoundWarn = appender.list.stream()
				.anyMatch(e -> e.getLevel().toString().equals("WARN")
						 && e.getFormattedMessage().contains("record not found")
					 && e.getFormattedMessage().contains("prefix=" + PREFIX));
		assertTrue(hasRecordNotFoundWarn, "应打 WARN 含 'record not found' 与 prefix");
	}

	@Test
	@DisplayName("bcrypt 不匹配（token 错误）→ 返回 null，不写缓存")
	void bcryptMismatch_returnsNull() {
		Aiclaw record = validRecord();
		// 记录存在但 tokenHash 是另一个 token 的 hash
		record.setTokenHash(BCrypt.hashpw("wrong-token-xxxxxxxxxxxx"));
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenReturn(record);

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		assertNull(info);
		verify(stringRedisTemplate, never()).opsForValue();
	}

	@Test
	@DisplayName("已停用（authStatus=2 + deactivatedAt 已设）→ 返回 null，不写缓存")
	void deactivated_returnsNull() {
		Aiclaw record = validRecord();
		record.setAuthStatus(2);
		record.setDeactivatedAt(LocalDateTime.now());
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenReturn(record);

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		assertNull(info);
		verify(stringRedisTemplate, never()).opsForValue();
	}

	@Test
	@DisplayName("authStatus≠1（未激活=0）→ 返回 null，不写缓存")
	void authStatusNotOne_returnsNull() {
		Aiclaw record = validRecord();
		record.setAuthStatus(0);
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenReturn(record);

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		assertNull(info);
		verify(stringRedisTemplate, never()).opsForValue();
	}

	@Test
	@DisplayName("空白/过短 token → 返回 null，不查 DAO，不写缓存")
	void blankOrShortToken_returnsNull() {
		AiclawTokenInfo info = aiclawService.verifyAndCacheToken("short");

		assertNull(info);
		verify(aiclawDao, never()).getByTokenPrefix(anyString());
		verify(stringRedisTemplate, never()).opsForValue();
	}

	/**
	 * #184(b) Bug1: verifyAndCacheToken 走 anyTenant 路径，必须在查 mapper 前设置租户上下文。
	 *
	 * <p>线上复现：verify-token 接口无登录态、无租户上下文，而 im_aiclaw 的 mapper 租户拦截器
	 * 在 SQL 执行点检查 {@code ContextUtil.getTenantId()} → 缺则 NPE「ContextUtil 不存在租户编号」。
	 * 单测里 mapper 被 @Mock，租户拦截器不会真实触发；故本用例在 mapper 桩的 Answer 里镜像拦截器
	 * 的检查点：在 {@code getByTokenPrefix} 被调瞬间读 {@code ContextUtil.getTenantId()} 并断言=1L。
	 *
	 * <p>RED 暴露：若 verifyAndCacheToken 缺 {@code setTenantId(1L)}，本用例 Answer 读到 null →
	 * assertEquals 失败（单测层即复现 bug 条件，非纯行为验证）。真实 mybatis-plus 拦截器抛 NPE 的
	 * 端到端验证在部署 task 10 自愈实测。
	 */
	@Test
	@DisplayName("#184(b) Bug1: verifyAndCacheToken 在查 mapper 前设置租户上下文（镜像拦截器检查点）")
	void verifyAndCacheToken_setsTenantContextBeforeMapperCall() {
		// 模拟 prod anyTenant 入口：调用前线程无租户上下文（防其他用例 ThreadLocal 泄漏干扰）
		com.luohuo.basic.context.ContextUtil.clearTenantContext();
		assertNull(com.luohuo.basic.context.ContextUtil.getTenantId(),
				"前置：调用前应无租户上下文");

		Aiclaw record = validRecord();
		// Answer 在 mapper 调用瞬间读 ContextUtil —— 镜像 prod 租户拦截器的检查时机
		when(aiclawDao.getByTokenPrefix(PREFIX)).thenAnswer(inv -> {
			Long tenantAtCall = com.luohuo.basic.context.ContextUtil.getTenantId();
			assertEquals(1L, tenantAtCall,
					"mapper 调用时必须已有租户上下文（缺 setTenantId → 租户拦截器 NPE：不存在租户编号）");
			return record;
		});
		stubValueOps();

		AiclawTokenInfo info = aiclawService.verifyAndCacheToken(TOKEN);

		assertNotNull(info);
		// 清理：避免本用例 setTenantId 残留 ThreadLocal 影响后续用例（im 侧 verifyAndCacheToken 无清理逻辑）
		com.luohuo.basic.context.ContextUtil.clearTenantContext();
	}
}
