package com.luohuo.flex.common;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#345 在线查询核验回归测试：存在即在线不可信，陈旧成员必须经设备级核验。
 *
 * <p>覆盖三点：
 * <ul>
 *   <li><b>快路</b>——score 新鲜直接判在线，不做设备扫描；</li>
 *   <li><b>慢路保活</b>——用户 score 陈旧但设备仍有新鲜心跳时判在线，不回收；</li>
 *   <li><b>慢路回收</b>——用户与设备均陈旧时判离线，并尽力回收用户成员。</li>
 * </ul>
 */
class OnlineServiceTest {

	private OnlineService onlineService;
	private CachePlusOps cachePlusOps;
	private RedisTemplate<String, Object> redisTemplate;
	private ZSetOperations<String, Object> zSetOps;

	private String usersKey;

	@BeforeEach
	@SuppressWarnings({"unchecked", "rawtypes"})
	void setUp() {
		onlineService = new OnlineService();
		cachePlusOps = mock(CachePlusOps.class);
		redisTemplate = mock(RedisTemplate.class);
		zSetOps = mock(ZSetOperations.class);
		ReflectionTestUtils.setField(onlineService, "cachePlusOps", cachePlusOps);
		ReflectionTestUtils.setField(onlineService, "redisTemplate", redisTemplate);

		usersKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();

		when(redisTemplate.opsForZSet()).thenReturn((ZSetOperations) zSetOps);
		when(cachePlusOps.sMembers(any())).thenReturn(Collections.emptySet());
	}

	private Cursor<ZSetOperations.TypedTuple<Object>> emptyCursor() {
		Cursor cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(false);
		return cursor;
	}

	@Test
	@DisplayName("快路：score 新鲜直接判在线，不扫描设备集合")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void freshScoreIsOnlineWithoutDeviceScan() {
		when(cachePlusOps.getZSetScores(eq(usersKey), any(List.class)))
				.thenReturn(List.of((Object) (double) (System.currentTimeMillis() - 1000)));

		Set<Long> online = onlineService.getOnlineUsersList(List.of(100L));

		assertEquals(Set.of(100L), online);
		verify(redisTemplate, never()).opsForZSet();
	}

	@Test
	@DisplayName("慢路保活：用户 score 陈旧但设备有新鲜心跳，判在线且不回收")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void staleUserScoreWithLiveDeviceStaysOnline() {
		long now = System.currentTimeMillis();
		when(cachePlusOps.getZSetScores(eq(usersKey), any(List.class)))
				.thenReturn(List.of((Object) (double) (now - 600_000)));
		ZSetOperations.TypedTuple<Object> liveDevice = mock(ZSetOperations.TypedTuple.class);
		when(liveDevice.getScore()).thenReturn((double) (now - 1000));
		Cursor cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(true, false);
		when(cursor.next()).thenReturn(liveDevice);
		when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cursor);

		Map<Long, Boolean> status = onlineService.getUsersOnlineStatus(List.of(100L));

		assertTrue(status.get(100L));
		verify(zSetOps, never()).remove(anyString(), any());
	}

	@Test
	@DisplayName("慢路回收：用户与设备均陈旧，判离线并回收用户成员")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void staleUserAndDevicesIsOfflineAndReclaimed() {
		long now = System.currentTimeMillis();
		when(cachePlusOps.getZSetScores(eq(usersKey), any(List.class)))
				.thenReturn(List.of((Object) (double) (now - 600_000)));
		Cursor<ZSetOperations.TypedTuple<Object>> cursor = emptyCursor();
		when(zSetOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cursor);

		Set<Long> online = onlineService.getOnlineUsersList(List.of(100L));

		assertTrue(online.isEmpty());
		// 用户成员以 String 形式写入，回收必须用 String 删，否则因序列化不一致删不掉
		verify(zSetOps).remove(eq(usersKey), eq("100"));
	}

	@Test
	@DisplayName("空入参返回空集合，不访问缓存")
	void emptyInputReturnsEmpty() {
		assertTrue(onlineService.getOnlineUsersList(Collections.emptyList()).isEmpty());
		assertFalse(onlineService.getUsersOnlineStatus(Collections.emptyList()).getOrDefault(1L, false));
		verify(cachePlusOps, never()).getZSetScores(anyString(), any(List.class));
	}
}
