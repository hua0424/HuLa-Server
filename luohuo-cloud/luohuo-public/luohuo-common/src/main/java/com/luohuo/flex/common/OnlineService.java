package com.luohuo.flex.common;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IM在线人员服务 [单个群人员大于10万时需要开启redis分片存储、分片查询]
 *
 * @author 乾乾
 */
@Slf4j
@Tag(name = "在线服务")
@Component
public class OnlineService {

	@Resource
	private CachePlusOps cachePlusOps;

	@Resource
	private RedisTemplate<String, Object> redisTemplate;

	private static final int PRESENCE_SCAN_COUNT = 200;
	private static final int PRESENCE_SCAN_MAX_PAGES = 10;

	/**
	 * 查询在线用户ID集合
	 * <p>aichatoverview#345：存在即在线不可信——异常断连/节点崩溃/历史永久残留会让 ZSET 成员永久存活。
	 * score 新鲜（{@link PresenceCacheKeyBuilder#PRESENCE_STALE_AFTER_MILLIS} 内）直接判在线；
	 * 陈旧或缺失的成员走设备级核验（仍有新鲜设备心跳则在线），否则按离线返回并尽力回收残留。
	 * @param uids 待查询的用户ID列表
	 * @return 在线的用户ID集合
	 */
	public Set<Long> getOnlineUsersList(@RequestBody List<Long> uids) {
		if (CollUtil.isEmpty(uids)) {
			return Collections.emptySet();
		}

		String onlineKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();
		long now = System.currentTimeMillis();
		Set<Long> onlineUsers = new HashSet<>();

		// 分页批量查询
		Lists.partition(new ArrayList<>(new HashSet<>(uids)), 500).forEach(batch -> {
			// 1. 批量查询分数
			List<Object> scores = cachePlusOps.getZSetScores(onlineKey, batch);

			// 2. 新鲜分数 = 在线；陈旧分数 = 核验后决定
			for (int i = 0; i < batch.size(); i++) {
				Long uid = batch.get(i);
				if (isFresh(scores.get(i), now) || hasLiveDevice(uid, now)) {
					onlineUsers.add(uid);
				} else {
					reclaimStalePresence(uid);
				}
			}
		});

		return onlineUsers;
	}

	/**
	 * 查询在线的人员
	 * @param uids
	 * @return 返回用户的在线状态（判定规则同 {@link #getOnlineUsersList}）
	 */
	public Map<Long, Boolean> getUsersOnlineStatus(@RequestBody List<Long> uids) {
		if (CollUtil.isEmpty(uids)) {
			return Collections.emptyMap();
		}
		String onlineKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();
		long now = System.currentTimeMillis();
		Map<Long, Boolean> statusMap = new HashMap<>();

		// 1. 管道批量查询分数
		List<Object> scores = cachePlusOps.getZSetScores(onlineKey, uids);

		// 2. 新鲜分数 = 在线；陈旧分数 = 核验后决定
		for (int i = 0; i < uids.size(); i++) {
			Long uid = uids.get(i);
			boolean online = isFresh(scores.get(i), now) || hasLiveDevice(uid, now);
			if (!online) {
				reclaimStalePresence(uid);
			}
			statusMap.put(uid, online);
		}
		return statusMap;
	}

	/**
	 * score 是否为有效心跳：数值型且年龄小于残留阈值。未来时间戳（节点时钟偏移）视为新鲜。
	 */
	private boolean isFresh(Object score, long now) {
		return score instanceof Number n && now - n.longValue() < PresenceCacheKeyBuilder.PRESENCE_STALE_AFTER_MILLIS;
	}

	/**
	 * 设备级核验：该用户在设备 ZSET 中是否仍有新鲜成员。
	 * <p>正常路径极少走到这里（存活连接的用户 score 本就新鲜）；ZSCAN 按成员前缀匹配、
	 * 命中首个新鲜成员即停。Redis 异常时保守返回在线（#214 同款 fail-safe：抖动不误判离线，
	 * 回收交给 ws 侧定时回收器）。
	 */
	private boolean hasLiveDevice(Long uid, long now) {
		String devicesKey = PresenceCacheKeyBuilder.globalOnlineDevicesKey().getKey();
		// 注意：ZSET 成员经 value 序列化器写入，String 成员带 JSON 引号
		// （redis-cli 可见 "uid:clientId" 形式），MATCH 必须带上前引号，否则永不命中
		ScanOptions options = ScanOptions.scanOptions().match("\"" + uid + ":*").count(PRESENCE_SCAN_COUNT).build();
		try (Cursor<ZSetOperations.TypedTuple<Object>> cursor = redisTemplate.opsForZSet().scan(devicesKey, options)) {
			int pages = 0;
			while (cursor.hasNext()) {
				if (++pages > PRESENCE_SCAN_COUNT * PRESENCE_SCAN_MAX_PAGES) {
					break;
				}
				ZSetOperations.TypedTuple<Object> tuple = cursor.next();
				if (tuple != null && isFresh(tuple.getScore(), now)) {
					return true;
				}
			}
		} catch (Exception e) {
			log.warn("在线核验设备扫描失败，保守判在线: uid={}", uid, e);
			return true;
		}
		return false;
	}

	/**
	 * 尽力回收该用户的残留：用户成员、扫描到的陈旧设备成员、在线群组关联。只做有限页扫描，
	 * 全量收敛由 ws 侧定时回收器负责。本方法永不抛异常，不影响查询结果。
	 */
	private void reclaimStalePresence(Long uid) {
		try {
			String onlineUsersKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();
			String devicesKey = PresenceCacheKeyBuilder.globalOnlineDevicesKey().getKey();
			// 成员以 String 形式写入（见 CachePlusOps zAdd 的 toString 归一），直接传 Long 会因序列化不一致删不掉
			redisTemplate.opsForZSet().remove(onlineUsersKey, uid.toString());

			ScanOptions options = ScanOptions.scanOptions().match("\"" + uid + ":*").count(PRESENCE_SCAN_COUNT).build();
			try (Cursor<ZSetOperations.TypedTuple<Object>> cursor = redisTemplate.opsForZSet().scan(devicesKey, options)) {
				int pages = 0;
				while (cursor.hasNext() && ++pages <= PRESENCE_SCAN_COUNT * PRESENCE_SCAN_MAX_PAGES) {
					ZSetOperations.TypedTuple<Object> tuple = cursor.next();
					if (tuple != null && tuple.getValue() != null) {
						redisTemplate.opsForZSet().remove(devicesKey, tuple.getValue());
					}
				}
			}

			CacheKey onlineUserGroupsKey = PresenceCacheKeyBuilder.onlineUserGroupsKey(uid);
			Set<Object> rooms = cachePlusOps.sMembers(onlineUserGroupsKey);
			if (CollUtil.isNotEmpty(rooms)) {
				for (Object room : rooms) {
					cachePlusOps.sRem(PresenceCacheKeyBuilder.onlineGroupMembersKey(room), uid);
				}
				redisTemplate.delete(onlineUserGroupsKey.getKey());
			}
			log.info("回收在线残留: uid={}", uid);
		} catch (Exception e) {
			log.warn("回收在线残留失败（不影响查询结果）: uid={}", uid, e);
		}
	}

	/**
	 * 批量查询群里面在线人员的数量
	 * @param roomIds
	 * @return 返回每个房间中的在线数量
	 */
	public Map<Long, Long> getBatchGroupOnlineCounts(@RequestBody List<Long> roomIds) {
		// 1. 使用缓存键构造键
		List<String> keys = roomIds.stream().map(id -> PresenceCacheKeyBuilder.onlineGroupMembersKey(id).getKey()).collect(Collectors.toList());

		// 2. 批量查询
		List<Long> counts = cachePlusOps.sMultiCard(keys);

		// 3. 大群组特殊处理
		Map<Long, Long> result = new HashMap<>();
		for (int i = 0; i < roomIds.size(); i++) {
			result.put(roomIds.get(i), counts.get(i));
		}
		return result;
	}

	/**
	 * 查询群组在线成员列表
	 *
	 * @param roomId 房间id
	 * @return 返回在线成员的uid
	 */
	public List<Long> getGroupOnlineMembers(@PathVariable @NotNull Long roomId) {
		return getGroupMembers(roomId, true);
	}

	public List<Long> getGroupMembers(@RequestBody Long roomId) {
		return getGroupMembers(roomId, false);
	}

	public List<Long> getGroupMembers(@PathVariable @NotNull Long roomId, boolean onlineOnly) {
		// 1. 根据查询类型构建缓存键
		CacheKey cacheKey = onlineOnly ? PresenceCacheKeyBuilder.onlineGroupMembersKey(roomId) : PresenceCacheKeyBuilder.groupMembersKey(roomId);

		// 2. 获取集合大小
		Long total = cachePlusOps.sSize(cacheKey.getKey());
		if (total == null || total == 0) {
			return new ArrayList<>();
		}

		// 3. 查询成员
		return cachePlusOps.sMembers(cacheKey).stream().map(obj -> Long.parseLong(obj.toString())).collect(Collectors.toList());
	}
}
