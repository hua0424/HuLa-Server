package com.luohuo.flex.im.core.user.service.cache;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * aiclaw 主人关系缓存
 * - im:aiclaw:owner:{aiclawUid} → ownerUid (TTL 24h)
 * - im:aiclaw:list:{ownerUid} → [aiclawUid1, aiclawUid2, ...] (TTL 24h)
 */
@Slf4j
@Component
@AllArgsConstructor
public class AiclawOwnerCache {

	private final StringRedisTemplate stringRedisTemplate;
	private final AiclawDao aiclawDao;

	private static final String OWNER_KEY_PREFIX = "im:aiclaw:owner:";
	private static final String LIST_KEY_PREFIX = "im:aiclaw:list:";
	private static final Duration TTL = Duration.ofHours(24);

	/**
	 * 查询 aiclaw 的 ownerUid（带缓存）
	 */
	public Long getOwnerUid(Long aiclawUid) {
		String key = OWNER_KEY_PREFIX + aiclawUid;
		String cached = stringRedisTemplate.opsForValue().get(key);
		if (cached != null) {
			return Long.valueOf(cached);
		}
		Aiclaw aiclaw = aiclawDao.getByUid(aiclawUid);
		if (aiclaw == null) {
			return null;
		}
		Long ownerUid = aiclaw.getOwnerUid();
		stringRedisTemplate.opsForValue().set(key, String.valueOf(ownerUid), TTL);
		return ownerUid;
	}

	/**
	 * 查询主人的 aiclaw 列表（带缓存）
	 */
	public Set<Long> getAiclawUids(Long ownerUid) {
		String key = LIST_KEY_PREFIX + ownerUid;
		String cached = stringRedisTemplate.opsForValue().get(key);
		if (cached != null) {
			JSONArray arr = JSONUtil.parseArray(cached);
			return arr.stream().map(o -> Long.valueOf(o.toString())).collect(Collectors.toSet());
		}
		List<Aiclaw> list = aiclawDao.listByOwner(ownerUid);
		Set<Long> uids = list.stream().map(Aiclaw::getUid).collect(Collectors.toSet());
		stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(uids), TTL);
		return uids;
	}

	/**
	 * 刷新指定 aiclaw 的缓存
	 */
	public void refresh(Long aiclawUid) {
		stringRedisTemplate.delete(OWNER_KEY_PREFIX + aiclawUid);
		Aiclaw aiclaw = aiclawDao.getByUid(aiclawUid);
		if (aiclaw != null) {
			stringRedisTemplate.opsForValue().set(
					OWNER_KEY_PREFIX + aiclawUid,
					String.valueOf(aiclaw.getOwnerUid()),
					TTL);
			// 同时刷新主人列表缓存
			stringRedisTemplate.delete(LIST_KEY_PREFIX + aiclaw.getOwnerUid());
		}
	}

	/**
	 * 删除缓存（aiclaw 注销时调用）
	 */
	public void evict(Long aiclawUid, Long ownerUid) {
		stringRedisTemplate.delete(OWNER_KEY_PREFIX + aiclawUid);
		if (ownerUid != null) {
			stringRedisTemplate.delete(LIST_KEY_PREFIX + ownerUid);
		}
	}
}
