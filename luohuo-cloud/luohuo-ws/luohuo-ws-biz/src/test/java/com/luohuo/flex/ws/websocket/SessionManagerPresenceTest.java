package com.luohuo.flex.ws.websocket;

import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.router.RouterCacheKeyBuilder;
import com.luohuo.flex.ws.service.PushService;
import com.luohuo.flex.ws.websocket.nacos.NacosSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#345 在线状态收敛回归测试：下线守卫、存活触达、残留回收。
 *
 * <p>覆盖四点：
 * <ul>
 *   <li><b>守卫放行</b>——路由指向本节点/已死节点且本地无会话时，正常执行下线清理；</li>
 *   <li><b>守卫拦截</b>——本地仍有会话、路由已指向存活他节点、活跃节点集不可用时跳过清理，
 *       迟到下线信号不踢掉重连的新连接；</li>
 *   <li><b>触达刷新</b>——已注册会话的任意消息刷新设备/用户 score（节流内一次），未注册会话不复活残留；</li>
 *   <li><b>回收器</b>——活跃节点集不可用时整轮跳过；陈旧设备按守卫规则清理。</li>
 * </ul>
 */
class SessionManagerPresenceTest {

	private static final Long UID = 7L;
	private static final String CLIENT = "c1";
	private static final String DEVICE = "7:c1";
	private static final String SELF_NODE = "node-self";

	private SessionManager manager;
	private CachePlusOps cachePlusOps;
	private NacosSessionRegistry registry;

	private String devicesKey;
	private String usersKey;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		manager = new SessionManager();
		cachePlusOps = mock(CachePlusOps.class);
		registry = mock(NacosSessionRegistry.class);
		ReflectionTestUtils.setField(manager, "cachePlusOps", cachePlusOps);
		ReflectionTestUtils.setField(manager, "nacosSessionRegistry", registry);
		ReflectionTestUtils.setField(manager, "pushService", mock(PushService.class));

		devicesKey = PresenceCacheKeyBuilder.globalOnlineDevicesKey().getKey();
		usersKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();

		when(registry.getNodeId()).thenReturn(SELF_NODE);
		// 默认：无群组、无反向好友、设备集合为空（首/末设备），批量计数为空
		when(cachePlusOps.sMembers(any())).thenReturn(Collections.emptySet());
		when(cachePlusOps.zCard(anyString())).thenReturn(0L);
		when(cachePlusOps.sMultiCard(any(List.class))).thenReturn(Collections.emptyList());
	}

	private void routeTo(String nodeId) {
		when(cachePlusOps.hGet(eq(RouterCacheKeyBuilder.buildDeviceNodeMap(DEVICE))))
				.thenReturn(new CacheResult<>(RouterCacheKeyBuilder.buildDeviceNodeMap(DEVICE), nodeId));
	}

	private WebSocketSession session(String id) {
		WebSocketSession s = mock(WebSocketSession.class);
		when(s.getId()).thenReturn(id);
		return s;
	}

	@Test
	@DisplayName("守卫放行：路由指向本节点且本地无会话，正常执行下线清理")
	void offlineProceedsWhenRoutePointsToSelf() {
		routeTo(SELF_NODE);

		assertTrue(manager.syncOnline(UID, CLIENT, false));

		verify(cachePlusOps).zRemove(eq(devicesKey), eq(DEVICE));
		verify(cachePlusOps).zRemove(eq(usersKey), eq(UID));
	}

	@Test
	@DisplayName("守卫放行：路由指向已死节点，允许清理残留")
	void offlineProceedsWhenRoutePointsToDeadNode() {
		routeTo("node-dead");
		when(registry.getAllActiveNodeIds()).thenReturn(Set.of(SELF_NODE));

		assertTrue(manager.syncOnline(UID, CLIENT, false));

		verify(cachePlusOps).zRemove(eq(devicesKey), eq(DEVICE));
	}

	@Test
	@DisplayName("守卫拦截：路由已指向存活他节点（跨节点重连），跳过迟到下线信号")
	void offlineSkippedWhenRouteMigratedToLiveNode() {
		routeTo("node-other");
		when(registry.getAllActiveNodeIds()).thenReturn(Set.of(SELF_NODE, "node-other"));

		assertFalse(manager.syncOnline(UID, CLIENT, false));

		verify(cachePlusOps, never()).zRemove(anyString(), any());
	}

	@Test
	@DisplayName("守卫拦截：活跃节点集不可用（fail-safe），跳过清理")
	void offlineSkippedWhenActiveNodesUnknown() {
		routeTo("node-other");
		when(registry.getAllActiveNodeIds()).thenReturn(null);

		assertFalse(manager.syncOnline(UID, CLIENT, false));

		verify(cachePlusOps, never()).zRemove(anyString(), any());
	}

	@Test
	@DisplayName("守卫拦截：本节点本地仍有该设备会话，任何下线信号都跳过")
	void offlineSkippedWhenLocalSessionStillMapped() {
		routeTo(SELF_NODE);
		manager.registerSession(session("s1"), CLIENT, UID);

		assertFalse(manager.syncOnline(UID, CLIENT, false));

		verify(cachePlusOps, never()).zRemove(anyString(), any());
	}

	@Test
	@DisplayName("触达刷新：已注册会话的消息刷新设备与用户 score，节流窗口内只写一次")
	void touchRefreshesScoresOncePerThrottleWindow() {
		routeTo(SELF_NODE);
		WebSocketSession s = session("s1");
		manager.registerSession(s, CLIENT, UID);
		clearInvocations(cachePlusOps);

		manager.touchPresence(s, UID);
		manager.touchPresence(s, UID);

		verify(cachePlusOps, times(1)).zAdd(eq(devicesKey), eq(DEVICE), anyDouble());
		verify(cachePlusOps, times(1)).zAdd(eq(usersKey), eq(UID), anyDouble());
	}

	@Test
	@DisplayName("触达刷新：未注册（已清理）会话的消息不复活残留")
	void touchIgnoresUnregisteredSession() {
		manager.touchPresence(session("ghost"), UID);

		verify(cachePlusOps, never()).zAdd(anyString(), any(), anyDouble());
	}

	@Test
	@DisplayName("回收器：活跃节点集不可用时整轮跳过，不扫描设备集合")
	void reaperSkipsWhenActiveNodesUnknown() {
		when(registry.getAllActiveNodeIds()).thenReturn(null);

		manager.reclaimStalePresence();

		verify(cachePlusOps, never()).zRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("回收器：陈旧且路由指向已死节点的设备被清理；守卫拦截的不动")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void reaperReclaimsStaleDeadDevice() {
		when(registry.getAllActiveNodeIds()).thenReturn(Set.of(SELF_NODE));
		ZSetOperations.TypedTuple<Object> staleTuple = mock(ZSetOperations.TypedTuple.class);
		when(staleTuple.getValue()).thenReturn("9:c9");
		when(cachePlusOps.zRangeByScoreWithScores(eq(devicesKey), anyDouble(), anyDouble(), anyLong(), anyLong()))
				.thenReturn((Set) Set.of(staleTuple));
		when(cachePlusOps.hGet(eq(RouterCacheKeyBuilder.buildDeviceNodeMap("9:c9"))))
				.thenReturn(new CacheResult<>(RouterCacheKeyBuilder.buildDeviceNodeMap("9:c9"), "node-dead"));

		manager.reclaimStalePresence();

		verify(cachePlusOps).zRemove(eq(devicesKey), eq("9:c9"));
		verify(cachePlusOps).zRemove(eq(usersKey), eq(9L));
	}

	@Test
	@DisplayName("正常断连全链路：cleanupSession 最终移除设备与用户在线状态")
	void cleanupSessionRemovesPresenceEventually() {
		routeTo(SELF_NODE);
		WebSocketSession s = session("s1");
		when(s.isOpen()).thenReturn(false);
		manager.registerSession(s, CLIENT, UID);

		manager.cleanupSession(s);

		verify(cachePlusOps, timeout(2000)).zRemove(eq(devicesKey), eq(DEVICE));
		verify(registry, timeout(2000)).removeDeviceRoute(eq(UID), eq(CLIENT));
	}
}
