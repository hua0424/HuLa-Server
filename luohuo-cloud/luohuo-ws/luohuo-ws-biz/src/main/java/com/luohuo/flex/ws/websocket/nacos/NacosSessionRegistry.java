package com.luohuo.flex.ws.websocket.nacos;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.model.cache.CacheHashKey;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.router.RouterCacheKeyBuilder;
import com.luohuo.flex.ws.websocket.SessionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Nacos会话注册中心
 *
 * 负责将当前节点注册到Nacos，并维护用户会话的路由信息。
 * 定时更新节点的心跳和会话信息。
 */
@Slf4j
@Component
public class NacosSessionRegistry {

	// 节点实例缓存
	private Instance nodeInstance;

    private NamingService namingService;

	@Lazy
	@Resource
	private SessionManager sessionManager;

	@Resource
	private CachePlusOps cachePlusOps;

	RedisTemplate<String, Object> redisTemplate;

    // 节点唯一标识
    private String nodeId;

	private String nodeIp;

	private int nodePort;

	@Autowired
	public NacosSessionRegistry(NacosServiceManager nacosServiceManager, RedisTemplate<String, Object> redisTemplate,
			@Value("${luohuo.node-id}") String nodeId, @Value("${server.port}") int nodePort, NacosDiscoveryProperties discoveryProperties) {
		this.redisTemplate = redisTemplate;
		this.nodeId = nodeId;
		this.nodePort = nodePort;

		// 确定节点IP
		this.nodeIp = determineNodeIp(discoveryProperties);

		log.info("节点IP: {}", nodeIp);

		// 创建命名服务实例
		this.namingService = nacosServiceManager.getNamingService(discoveryProperties.getNacosProperties());
	}

	private String determineNodeIp(NacosDiscoveryProperties properties) {
		if (StringUtils.hasText(properties.getIp())) {
			return properties.getIp();
		}
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (Exception e) {
			return "127.0.0.1";
		}
	}

	@PostConstruct
	public void init() {
		// 初始化节点实例
		nodeInstance = createNodeInstance();
		registerNodeInstance();
	}

	private Instance createNodeInstance() {
		Instance instance = new Instance();
		instance.setIp(nodeIp);
		instance.setPort(nodePort);
		instance.setHealthy(true);
		instance.setEphemeral(true);

		Map<String, String> metadata = new HashMap<>();
		metadata.put("nodeId", nodeId);
		metadata.put("startTime", Instant.now().toString());
		instance.setMetadata(metadata);
		return instance;
	}

	private void registerNodeInstance() {
		try {
			namingService.registerInstance("ws-cluster", "WS_GROUP", nodeInstance);
			log.info("节点注册成功: {}", nodeId);
		} catch (NacosException e) {
			log.error("节点注册失败", e);
		}
	}

	/**
	 * 添加用户路由信息
	 * @param uid 用户id
	 */
	public void addUserRoute(Long uid, String clientId) {
		String deviceField = uid + ":" + clientId;

		// 1. 设备指纹→节点映射
		cachePlusOps.hSet(RouterCacheKeyBuilder.buildDeviceNodeMap(deviceField), nodeId);

		// 2. 节点→设备指纹映射
		cachePlusOps.sAdd(RouterCacheKeyBuilder.buildNodeDevices(nodeId), deviceField);

		// 3. 更新节点元数据
		Map<String, String> metadata = nodeInstance.getMetadata();
		metadata.put("lastActive", Instant.now().toString());
		nodeInstance.setMetadata(metadata);
		log.info("添加用户路由: uid={}, clientId={}, node={}", uid, clientId, nodeId);
	}

	/**
	 * 移除用户路由信息
	 * @param uid 用户id
	 */
	public void removeDeviceRoute(Long uid, String clientId) {
		String deviceField = uid + ":" + clientId;

		// 清理设备→节点映射
		CacheHashKey deviceNodeMap = RouterCacheKeyBuilder.buildDeviceNodeMap(deviceField);
		cachePlusOps.hDel(deviceNodeMap);

		// 清理节点→设备映射
		CacheKey nodeDevices = RouterCacheKeyBuilder.buildNodeDevices(nodeId);
		cachePlusOps.sRem(nodeDevices, deviceField);
	}

	/**
	 * 清理节点在Redis中的所有路由信息
	 */
	public void cleanupNodeRoutes(String cleanNodeId) {
		cleanNodeId = StrUtil.isEmpty(cleanNodeId) ? nodeId : cleanNodeId;
		// 1. 清理节点→设备映射
		CacheKey cacheKey = RouterCacheKeyBuilder.buildNodeDevices(cleanNodeId);
		Set<Object> deviceFields = redisTemplate.opsForSet().members(cacheKey.getKey());

		if (!CollectionUtils.isEmpty(deviceFields)) {
			// 批量删除全局Hash中的映射
			CacheHashKey deviceNodeMap = RouterCacheKeyBuilder.buildDeviceNodeMap("");
			redisTemplate.opsForHash().delete(deviceNodeMap.getKey(), deviceFields.toArray());

			// 清理节点本地映射
			redisTemplate.delete(cacheKey.getKey());
		}
		log.info("节点路由清理完成: nodeId={}, 清理设备数={}", cleanNodeId, deviceFields != null ? deviceFields.size() : 0);
	}

	public void deregisterNode() {
		try {
			namingService.deregisterInstance("ws-cluster", "WS_GROUP", nodeIp, nodePort);
			log.info("Nacos节点注销成功: {}", nodeId);
		} catch (NacosException e) {
			log.error("Nacos节点注销失败", e);
		}
	}
    
    @Scheduled(fixedRate = 5000)
	public void updateNodeMetrics() {
		try {
			// 更新元数据
			Map<String, String> metadata = new HashMap<>(nodeInstance.getMetadata());
			metadata.put("lastHeartbeat", Instant.now().toString());
			metadata.put("sessionCount", String.valueOf(sessionManager.getSessionCount()));

			// 添加客户端ID列表
			List<String> clientIds = sessionManager.getClientIds();
			if (!clientIds.isEmpty()) {
				metadata.put("clientIds", String.join(",", clientIds.subList(0, Math.min(10, clientIds.size()))));
			}

			// 重新注册更新后的实例
			nodeInstance.setMetadata(metadata);
			namingService.registerInstance("ws-cluster", "WS_GROUP", nodeInstance);
		} catch (NacosException e) {
			log.error("心跳更新失败", e);
		}
	}

	public Set<String> getAllActiveNodeIds() {
		try {
			// P1-1（PR #76）：实例存在即活跃——不做 isHealthy 过滤，健康检查抖动（Nacos gRPC 9848 超时
			// 被标 unhealthy）不再把存活节点排除；真死节点由 Nacos ephemeral 实例自动移除兜底
			List<Instance> instances = namingService.getAllInstances("ws-cluster", "WS_GROUP");
			return instances.stream()
					.map(instance -> instance.getMetadata().get("nodeId"))
					.collect(Collectors.toSet());
		} catch (NacosException e) {
			// #214 fail-safe：查询失败返回 null 而非空集——调用方（cleanStaleRoutes）据此区分
			// 「查询失败」与「确实无活跃节点」，两种情形下都禁止清理，避免异常瞬间全量误清
			log.error("获取活跃节点失败", e);
			return null;
		}
	}

	public Set<String> getAllRedisNodeIds() {
		// 1. 获取基础key模式
		String baseKey = RouterCacheKeyBuilder.buildNodeDevices("").getKey();
		String pattern = baseKey + "*";

		// 2. 使用SCAN命令安全遍历
		ScanOptions options = ScanOptions.scanOptions()
				.match(pattern)
				.count(100)
				.build();

		Set<String> nodeIds = new HashSet<>();
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				String key = cursor.next();
				// 3. 提取纯节点ID
				if(key.startsWith(baseKey)) {
					String[] parts = key.split(":");
					nodeIds.add(parts[parts.length - 1]);
				}
			}
		}
		return nodeIds;
	}

	@Scheduled(fixedDelay = 30000)
	public void cleanStaleRoutes() {
		// 1. 获取所有需要对比的ID集合
		// 空集 fail-safe（#214）：Nacos 查询失败（null）或无任何活跃节点（空集）时禁止清理，
		// 避免基础设施抖动瞬间把全部节点路由误清（fail-destructive → 静默丢帧黑洞）
		Set<String> activeNodes = getAllActiveNodeIds();
		if (activeNodes == null || activeNodes.isEmpty()) {
			log.warn("cleanStaleRoutes 跳过：活跃节点集合为空或查询失败（activeNodes={}），不执行任何路由清理", activeNodes);
			return;
		}
		Set<String> redisNodes = getAllRedisNodeIds();
		if (redisNodes.isEmpty()) {
			log.warn("cleanStaleRoutes 跳过：Redis 中无任何节点路由（redisNodes 为空），无残留可清理");
			return;
		}

		// 2. 计算需要清理的节点ID —— 本节点豁免（#214）：自身心跳抖动被标 unhealthy 时不清理自己，
		// 否则在线会话路由被清 → 推送静默黑洞（08-07 实证 26 分钟黑洞窗口）
		Set<String> staleNodes = redisNodes.stream()
				.filter(node -> !activeNodes.contains(node))
				.filter(node -> !node.equals(nodeId))
				.collect(Collectors.toSet());

		// 3. 批量清理
		staleNodes.forEach(node -> {
			log.info("发现残留节点数据，开始清理: {}", node);
			try {
				cleanupNodeRoutes(node);
				cleanNodeCompletely(node);
			} catch (Exception e) {
				log.error("节点清理失败: {}", node, e);
			}
		});
	}

	/**
	 * 路由自愈补挂（#214）：定期比对本节点活跃设备与 Redis 路由表，
	 * 路由缺失或指向其他节点（如误清残留）时重新补挂 addUserRoute，
	 * 避免误清后已连接会话不补挂导致的长时间推送黑洞（08-07 实证 26 分钟）。
	 */
	@Scheduled(fixedDelay = 60000)
	public void selfHealRoutes() {
		Map<Long, Set<String>> activeDevices = sessionManager.getActiveDevices();
		if (CollUtil.isEmpty(activeDevices)) {
			return;
		}

		int healed = 0;
		for (Map.Entry<Long, Set<String>> entry : activeDevices.entrySet()) {
			Long uid = entry.getKey();
			for (String clientId : entry.getValue()) {
				String deviceField = uid + ":" + clientId;
				String routedNode = null;
				try {
					CacheResult<String> cacheResult = cachePlusOps.hGet(RouterCacheKeyBuilder.buildDeviceNodeMap(deviceField));
					routedNode = cacheResult == null ? null : cacheResult.getValue();
				} catch (Exception e) {
					log.warn("路由自愈检查失败: uid={}, clientId={}", uid, clientId, e);
					continue;
				}
				if (routedNode == null) {
					// 路由缺失（如误清/从未注册）：补挂到本节点
					addUserRoute(uid, clientId);
					healed++;
					log.info("路由自愈补挂: uid={}, clientId={}, 补挂到本节点={}", uid, clientId, nodeId);
				} else if (!nodeId.equals(routedNode)) {
					// P2-1（PR #76）：异指他节点不抢路由不补挂——该设备可能已迁移/重连到新节点，仅记录不一致
					log.info("路由自愈检查: uid={}, clientId={} 路由指向他节点 {}，不抢不补", uid, clientId, routedNode);
				}
			}
		}
		if (healed > 0) {
			log.info("路由自愈完成: 补挂设备数={}", healed);
		}
	}

	private void cleanNodeCompletely(String nodeId) {
		// 1. 获取节点所有设备
		CacheKey nodeDevicesKey = RouterCacheKeyBuilder.buildNodeDevices(nodeId);
		Set<String> deviceFields = redisTemplate.opsForSet()
				.members(nodeDevicesKey.getKey())
				.stream()
				.map(Object::toString)
				.collect(Collectors.toSet());

		if (CollectionUtils.isEmpty(deviceFields)) {
			return;
		}

		// 2. 准备在线状态清理数据
		Map<Long, Set<String>> uidToClients = deviceFields.stream()
				.map(field -> field.split(":"))
				.filter(parts -> parts.length == 2)
				.collect(Collectors.groupingBy(
						parts -> Long.parseLong(parts[0]),
						Collectors.mapping(parts -> parts[1], Collectors.toSet())
				));

		// 3. 执行原子化清理
		uidToClients.forEach((uid, clients) -> clients.forEach(client -> sessionManager.syncOnline(uid, client, false)));
		log.info("节点完全清理完成: node={}, 设备数={}", nodeId, deviceFields.size());
	}
}