package com.luohuo.flex.im.core.chat.service.cache;

import com.luohuo.flex.im.common.constant.RedisKey;
import com.luohuo.flex.im.common.service.cache.AbstractRedisStringCache;
import com.luohuo.flex.im.core.chat.dao.RoomGroupDao;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 群组基本信息的缓存 [roomId版]
 * @author nyh
 */
@Component
public class RoomGroupCache extends AbstractRedisStringCache<Long, RoomGroup> {
    @Resource
    private RoomGroupDao roomGroupDao;

    @Override
    protected String getKey(Long roomId) {
        return RedisKey.getKey(RedisKey.GROUP_INFO_FORMAT, roomId);
    }

    @Override
    protected Long getExpireSeconds() {
        return 5 * 60L;
    }

    @Override
    protected Map<Long, RoomGroup> load(List<Long> roomIds) {
        List<RoomGroup> roomGroups = roomGroupDao.listByRoomIds(roomIds);
        return roomGroups.stream().collect(Collectors.toMap(RoomGroup::getRoomId, Function.identity()));
    }

	/**
	 * 直查 DB 取群信息，<b>不走本类的 Redis 缓存</b>（不经基类 {@link AbstractRedisStringCache#get}）。
	 *
	 * <p>命名以 {@code FromDb} 显式标注「绕过缓存」，避免被误当作 cache-aside 入口调用。
	 *
	 * <p>为何保持直查、不改走基类 {@code get(roomId)}：roomId 维度<b>没有失效 wiring</b>——
	 * 本类 {@link #removeById} 不 evict 该维度缓存，类内的 {@code @CacheEvict} 作用于
	 * account-keyed 的 {@code searchGroup}/{@code findGroup} 缓存，与 roomId 维度无关。
	 * 若改走基类 {@code get(roomId)}，命中后最长会有 {@link #getExpireSeconds()} 返回的
	 * 5min TTL 陈旧（getExpireSeconds 返 5*60），故此处保持直查 DB 取实时数据。
	 * 如需为 roomId 维度加缓存，必须另补对应的失效逻辑，属本次范围外。
	 */
	public RoomGroup getByRoomIdFromDb(Long roomId) {
		return roomGroupDao.getByRoomId(roomId);
	}

	public boolean removeById(Serializable id) {
		return roomGroupDao.removeById(id);
	}

	/**
	 * 根据群号查询群信息
	 */
	@Cacheable(cacheNames = "luohuo:room:findGroup", key = "#account")
	public List<RoomGroup> searchGroup(String account) {
		return roomGroupDao.searchGroup(account);
	}

	/**
	 * 当 key 的数据改变后需要调用此方法
	 * @param account
	 * @return
	 */
	@CacheEvict(cacheNames = "luohuo:room:findGroup", key = "#account")
	public List<Long> evictGroup(String account) {
		return null;
	}

	/**
	 * 清除所有群组相关缓存
	 */
	@CacheEvict(cacheNames = "luohuo:room:findGroup", allEntries = true)
	public void evictAllCaches() {
	}
}
