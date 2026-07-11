package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * aichatoverview#170: 群在线状态同步的<b>无环单点</b>。
 *
 * <p>原 {@code RoomAppServiceImpl.asyncOnline} 的逻辑，拆分后由 shell + GroupMembershipManager +
 * GroupLifecycleManager 三处共用——抽此单点消除手抄副本（本轮重构的主旨）。依赖仅
 * {@link OnlineService} + {@link CachePlusOps}，不反向依赖任何调用方，故三处注入均无环。
 * 纯方法、无 AOP 注解，语义与拆分前逐字一致。</p>
 */
@Component
@AllArgsConstructor
public class PresenceSyncHelper {

	private final OnlineService onlineService;
	private final CachePlusOps cachePlusOps;

	/**
	 * 处理群成员的在线状态缓存（在线加入 / 离线移除）。
	 */
	public void syncOnline(List<Long> uidList, Long roomId, boolean online) {
		Set<Long> onlineList = onlineService.getOnlineUsersList(uidList);
		if (CollUtil.isEmpty(onlineList)) {
			return;
		}

		CacheKey ogmKey = PresenceCacheKeyBuilder.onlineGroupMembersKey(roomId);
		for (Long uid : onlineList) {
			CacheKey ougKey = PresenceCacheKeyBuilder.onlineUserGroupsKey(uid);

			if (online) {
				// 处理在线的状态
				cachePlusOps.sAdd(ogmKey, uid);
				cachePlusOps.sAdd(ougKey, roomId);
			} else {
				// 处理离线的状态
				cachePlusOps.sRem(ougKey, roomId);
				cachePlusOps.sRem(ogmKey, uid);
			}
		}
	}
}
