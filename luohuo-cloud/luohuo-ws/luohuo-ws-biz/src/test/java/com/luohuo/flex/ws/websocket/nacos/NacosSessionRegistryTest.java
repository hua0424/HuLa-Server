package com.luohuo.flex.ws.websocket.nacos;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.flex.router.RouterCacheKeyBuilder;
import com.luohuo.flex.ws.websocket.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#214: {@link NacosSessionRegistry} 路由清理 fail-safe + 路由自愈补挂 回归测试。
 *
 * <p>覆盖四点：
 * <ul>
 *   <li><b>豁免</b>——本节点心跳失败被 Nacos 标 unhealthy（activeNodes 不含本节点、Redis 路由含本节点）
 *       时，不清理本节点路由（修复前会误清，黑洞窗口约 26 分钟）；</li>
 *   <li><b>禁清</b>——{@link #getAllActiveNodeIds()} 查询失败（返回 null）或返回空集时，不执行任何清理；</li>
 *   <li><b>自愈</b>——本节点活跃设备的 Redis 路由缺失或指向其他节点时，补挂 {@link #addUserRoute}。</li>
 * </ul>
 */
class NacosSessionRegistryTest {

	private static final String NODE_ID = "node-test-1";

	private NamingService namingService;
	private RedisTemplate<String, Object> redisTemplate;
	private SessionManager sessionManager;
	private CachePlusOps cachePlusOps;
	private NacosSessionRegistry registry;

	@BeforeEach
	void setUp() {
		NacosServiceManager nacosServiceManager = mock(NacosServiceManager.class);
		namingService = mock(NamingService.class);
		when(nacosServiceManager.getNamingService(any())).thenReturn(namingService);

		NacosDiscoveryProperties discoveryProperties = mock(NacosDiscoveryProperties.class);
		when(discoveryProperties.getNacosProperties()).thenReturn(new Properties());

		redisTemplate = mock(RedisTemplate.class);
		sessionManager = mock(SessionManager.class);
		cachePlusOps = mock(CachePlusOps.class);

		registry = spy(new NacosSessionRegistry(nacosServiceManager, redisTemplate, NODE_ID, 8080, discoveryProperties));
		ReflectionTestUtils.setField(registry, "sessionManager", sessionManager);
		ReflectionTestUtils.setField(registry, "cachePlusOps", cachePlusOps);
		// 创建 nodeInstance（否则 addUserRoute 更新元数据会 NPE）；registerInstance 走 mock，无真实网络
		registry.init();
	}

	private Instance instance(String nodeId, boolean healthy) {
		Instance i = mock(Instance.class);
		when(i.isHealthy()).thenReturn(healthy);
		when(i.getMetadata()).thenReturn(Collections.singletonMap("nodeId", nodeId));
		return i;
	}

	private Cursor<String> mockCursor(String... keys) {
		Cursor<String> cursor = mock(Cursor.class);
		if (keys.length == 0) {
			when(cursor.hasNext()).thenReturn(false);
		} else {
			Boolean[] flags = new Boolean[keys.length + 1];
			Arrays.fill(flags, 0, keys.length, true);
			flags[keys.length] = false;
			when(cursor.hasNext()).thenReturn(true, flags);
			when(cursor.next()).thenReturn(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
		}
		return cursor;
	}

	@Test
	@DisplayName("豁免：本节点不在实例列表且 Redis 路由含本节点时，不清理本节点路由")
	void cleanStaleRoutes_skipsOwnNodeWhenNotInActiveNodes() throws Exception {
		// given: Nacos 实例列表不含本节点（ephemeral 移除/注册窗口期）；Redis 中残留本节点路由 key
		Instance otherNode = instance("node-other", true);
		when(namingService.getAllInstances(eq("ws-cluster"), eq("WS_GROUP")))
				.thenReturn(List.of(otherNode));
		Cursor<String> cursor = mockCursor("luohuo:router:node-devices:string:" + NODE_ID);
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		// when
		registry.cleanStaleRoutes();

		// then: 本节点路由不得被清理
		verify(registry, never()).cleanupNodeRoutes(eq(NODE_ID));
		verify(registry, never()).cleanupNodeRoutes(anyString());
	}

	@Test
	@DisplayName("禁清：Nacos 查询失败时 getAllActiveNodeIds 返回 null（与'无活跃节点'可区分），不执行任何清理")
	void cleanStaleRoutes_skipsAllWhenNacosQueryFails() throws Exception {
		// given: Nacos 查询抛异常 → getAllActiveNodeIds 返回 null；Redis 中尚有其他节点路由 key
		when(namingService.getAllInstances(anyString(), anyString()))
				.thenThrow(new NacosException(500, "Nacos 超时"));
		Cursor<String> cursor = mockCursor("luohuo:router:node-devices:string:node-other");
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		// when
		registry.cleanStaleRoutes();

		// then: 失败返回 null 而非空集（调用处可区分）
		assertNull(registry.getAllActiveNodeIds());
		verify(registry, never()).cleanupNodeRoutes(anyString());
	}

	@Test
	@DisplayName("P1-1：unhealthy 实例存在即活跃（不因健康检查抖动被排除，真死靠 ephemeral 移除兜底）")
	void getAllActiveNodeIds_includesUnhealthyInstances() throws Exception {
		// given: 实例列表含 healthy 与 unhealthy 实例
		Instance healthy = instance("node-a", true);
		Instance unhealthy = instance("node-b", false);
		when(namingService.getAllInstances(eq("ws-cluster"), eq("WS_GROUP")))
				.thenReturn(List.of(healthy, unhealthy));

		// when
		Set<String> activeNodes = registry.getAllActiveNodeIds();

		// then: 两个实例都存在即视为活跃
		org.assertj.core.api.Assertions.assertThat(activeNodes).contains("node-a", "node-b");
	}

	@Test
	@DisplayName("禁清：activeNodes 为空集（无任何活跃节点）时不执行任何清理")
	void cleanStaleRoutes_skipsAllWhenNoActiveNodes() throws Exception {
		// given: Nacos 返回空实例列表；Redis 中尚有其他节点路由 key
		when(namingService.getAllInstances(anyString(), anyString())).thenReturn(List.of());
		Cursor<String> cursor = mockCursor("luohuo:router:node-devices:string:node-other");
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		// when
		registry.cleanStaleRoutes();

		// then: 空集同样禁止清理
		verify(registry, never()).cleanupNodeRoutes(anyString());
	}

	@Test
	@DisplayName("自愈：本节点活跃设备的 Redis 路由缺失时补挂 addUserRoute")
	void selfHealRoutes_remountsMissingRoutes() {
		// given: 本节点两个活跃设备，Redis 路由均缺失
		when(sessionManager.getActiveDevices())
				.thenReturn(Map.of(100L, Set.of("clientA"), 200L, Set.of("clientB")));
		when(cachePlusOps.hGet(any()))
				.thenReturn(new CacheResult<>(RouterCacheKeyBuilder.buildDeviceNodeMap("100:clientA"), null));

		// when
		registry.selfHealRoutes();

		// then: 两个设备都补挂
		verify(registry).addUserRoute(100L, "clientA");
		verify(registry).addUserRoute(200L, "clientB");
	}

	@Test
	@DisplayName("自愈：路由已指向本节点时不重复补挂")
	void selfHealRoutes_skipsWhenAlreadyOnThisNode() {
		// given: 活跃设备路由已指向本节点
		when(sessionManager.getActiveDevices()).thenReturn(Map.of(100L, Set.of("clientA")));
		when(cachePlusOps.hGet(any()))
				.thenReturn(new CacheResult<>(RouterCacheKeyBuilder.buildDeviceNodeMap("100:clientA"), NODE_ID));

		// when
		registry.selfHealRoutes();

		// then: 不重复补挂
		verify(registry, never()).addUserRoute(anyLong(), anyString());
	}

	@Test
	@DisplayName("P2-1：路由指向其他节点时只记录不一致，不补挂不抢他节点")
	void selfHealRoutes_doesNotStealWhenPointingToOtherNode() {
		// given: 活跃设备路由指向其他节点（可能已迁移到新节点）
		when(sessionManager.getActiveDevices()).thenReturn(Map.of(100L, Set.of("clientA")));
		when(cachePlusOps.hGet(any()))
				.thenReturn(new CacheResult<>(RouterCacheKeyBuilder.buildDeviceNodeMap("100:clientA"), "node-other"));

		// when
		registry.selfHealRoutes();

		// then: 不抢路由
		verify(registry, never()).addUserRoute(anyLong(), anyString());
	}
}
