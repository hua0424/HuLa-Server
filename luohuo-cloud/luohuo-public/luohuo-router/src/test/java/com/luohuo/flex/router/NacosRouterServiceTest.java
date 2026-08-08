package com.luohuo.flex.router;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * aichatoverview#214: {@link NacosRouterService} 路由查询 Nacos 失败降级 回归测试。
 *
 * <p>覆盖两点：
 * <ul>
 *   <li><b>降级</b>——{@link #findNodeDeviceUser(List)} 在 {@code getAllActiveNodes()} 抛 BizException
 *       （Nacos 查询失败）时不过滤活跃节点（保守投递，宁可发到死节点也不静默丢）；</li>
 *   <li><b>降级</b>——{@link #getDeviceNode(Long, String)} 同样跳过活跃性检查，返回 nodeId 本身；</li>
 *   <li><b>对照</b>——Nacos 正常时仍按活跃节点过滤（防回归）。</li>
 * </ul>
 */
class NacosRouterServiceTest {

	private NamingService namingService;
	private RedisTemplate<String, Object> redisTemplate;
	private HashOperations<String, Object, Object> hashOps;
	private NacosRouterService routerService;

	@BeforeEach
	void setUp() {
		NacosServiceManager nacosServiceManager = mock(NacosServiceManager.class);
		namingService = mock(NamingService.class);
		when(nacosServiceManager.getNamingService(any())).thenReturn(namingService);

		NacosDiscoveryProperties discoveryProperties = mock(NacosDiscoveryProperties.class);
		when(discoveryProperties.getNacosProperties()).thenReturn(new Properties());

		redisTemplate = mock(RedisTemplate.class);
		hashOps = mock(HashOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOps);

		routerService = new NacosRouterService(nacosServiceManager, discoveryProperties, redisTemplate);
	}

	private Instance instance(String nodeId, boolean healthy) {
		Instance i = mock(Instance.class);
		when(i.isHealthy()).thenReturn(healthy);
		when(i.getMetadata()).thenReturn(Collections.singletonMap("nodeId", nodeId));
		return i;
	}

	private Map.Entry<Object, Object> hashEntry(String field, String nodeId) {
		Map.Entry<Object, Object> entry = mock(Map.Entry.class);
		when(entry.getKey()).thenReturn(field);
		when(entry.getValue()).thenReturn(nodeId);
		return entry;
	}

	@SuppressWarnings("unchecked")
	private Cursor<Map.Entry<Object, Object>> mockHashCursor(Map.Entry<Object, Object>... entries) {
		Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
		if (entries.length == 0) {
			when(cursor.hasNext()).thenReturn(false);
		} else {
			Boolean[] flags = new Boolean[entries.length + 1];
			java.util.Arrays.fill(flags, 0, entries.length, true);
			flags[entries.length] = false;
			when(cursor.hasNext()).thenReturn(true, flags);
			when(cursor.next()).thenReturn(entries[0], java.util.Arrays.copyOfRange(entries, 1, entries.length));
		}
		return cursor;
	}

	@Test
	@DisplayName("降级：Nacos 查询失败时 findNodeDeviceUser 不过滤活跃节点（保守投递，不静默丢）")
	void findNodeDeviceUser_degradesWhenNacosQueryFails() throws Exception {
		// given: Nacos 查询抛异常；Redis 路由映射含两个节点上的设备
		when(namingService.getAllInstances(anyString(), anyString()))
				.thenThrow(new NacosException(500, "Nacos 超时"));
		Cursor<Map.Entry<Object, Object>> cursor = mockHashCursor(
				hashEntry("100:clientA", "node-1"),
				hashEntry("100:clientB", "node-2"));
		when(hashOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cursor);

		// when
		Map<String, Map<String, Long>> result = routerService.findNodeDeviceUser(List.of(100L));

		// then: 两个节点的条目都进入结果（不过滤）
		assertThat(result).containsKeys("node-1", "node-2");
		assertThat(result.get("node-1")).containsEntry("clientA", 100L);
		assertThat(result.get("node-2")).containsEntry("clientB", 100L);
	}

	@Test
	@DisplayName("P1-1：unhealthy 实例存在即活跃（getAllActiveNodes 不因健康检查抖动排除实例）")
	void getAllActiveNodes_includesUnhealthyInstances() throws Exception {
		// given: 实例列表含 healthy 与 unhealthy 实例
		Instance healthy = instance("node-1", true);
		Instance unhealthy = instance("node-2", false);
		when(namingService.getAllInstances(anyString(), anyString()))
				.thenReturn(List.of(healthy, unhealthy));

		// when
		Set<String> activeNodes = routerService.getAllActiveNodes();

		// then: 实例存在即活跃
		assertThat(activeNodes).contains("node-1", "node-2");
	}

	@Test
	@DisplayName("对照：节点不在实例列表（ephemeral 已移除，真死）时仍被过滤")
	void findNodeDeviceUser_filtersNodesMissingFromNacosInstances() throws Exception {
		// given: 实例列表仅 node-1（node-2 已从 Nacos 移除）；Redis 路由映射两个节点上都有设备
		Instance activeNode = instance("node-1", true);
		when(namingService.getAllInstances(anyString(), anyString()))
				.thenReturn(List.of(activeNode));
		Cursor<Map.Entry<Object, Object>> cursor = mockHashCursor(
				hashEntry("100:clientA", "node-1"),
				hashEntry("100:clientB", "node-2"));
		when(hashOps.scan(anyString(), any(ScanOptions.class))).thenReturn(cursor);

		// when
		Map<String, Map<String, Long>> result = routerService.findNodeDeviceUser(List.of(100L));

		// then: 仅存在的节点进入结果，已移除节点被过滤
		assertThat(result).containsOnlyKeys("node-1");
		assertThat(result).doesNotContainKey("node-2");
	}

	@Test
	@DisplayName("降级：getDeviceNode 在 Nacos 查询失败时返回 nodeId 本身（跳过活跃性检查）")
	void getDeviceNode_returnsNodeIdWhenNacosQueryFails() throws Exception {
		// given: Redis 路由指向 node-1；Nacos 查询抛异常
		when(hashOps.get(anyString(), eq("100:clientA"))).thenReturn("node-1");
		when(namingService.getAllInstances(anyString(), anyString()))
				.thenThrow(new NacosException(500, "Nacos 超时"));

		// when
		String node = routerService.getDeviceNode(100L, "clientA");

		// then: 降级返回 nodeId，不抛异常
		assertThat(node).isEqualTo("node-1");
	}
}
