package com.luohuo.flex.im.core.user.dao;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.luohuo.flex.im.core.user.mapper.AiclawMapper;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * AI助理扩展表 DAO
 */
@Service
public class AiclawDao extends ServiceImpl<AiclawMapper, Aiclaw> {

	public Aiclaw getByUid(Long uid) {
		return lambdaQuery().eq(Aiclaw::getUid, uid).one();
	}

	public Aiclaw getByTokenPrefix(String tokenPrefix) {
		return lambdaQuery().eq(Aiclaw::getTokenPrefix, tokenPrefix).one();
	}

	public List<Aiclaw> listByOwner(Long ownerUid) {
		return lambdaQuery().eq(Aiclaw::getOwnerUid, ownerUid).list();
	}

	/**
	 * REQ-009#84: 批量识别 uid 列表中的 aiclaw（按 uid 字段查询，非 @TableId）。
	 *
	 * @param uids 待筛查的 uid 集合
	 * @return 其中属于 aiclaw 的记录；uids 为 null/空时返回空 List
	 */
	public List<Aiclaw> listByUids(Collection<Long> uids) {
		if (uids == null || uids.isEmpty()) {
			return Collections.emptyList();
		}
		return lambdaQuery().in(Aiclaw::getUid, uids).list();
	}

	public Aiclaw getByOwnerAndUid(Long ownerUid, Long uid) {
		return lambdaQuery()
				.eq(Aiclaw::getOwnerUid, ownerUid)
				.eq(Aiclaw::getUid, uid)
				.one();
	}

	/**
	 * REQ-122: 查询占用指定 machineCode 的「其他」aiclaw（排除自身 uid）。
	 *
	 * <p>machineCode 即 WS clientId，推送路由以 clientId→uid 建映射；同一 clientId 被两个 uid
	 * 共用会静默覆盖、其中一方被永久踢出群推送。激活时用此方法查重，令冲突失败而非静默覆盖。</p>
	 *
	 * @param machineCode 机器码（WS clientId）
	 * @param selfUid     激活中的 aiclaw uid（排除自身，允许其重新绑定自己的码）
	 * @return 占用该机器码的其他 aiclaw；不存在返回 null
	 */
	public Aiclaw getOtherHolderByMachineCode(String machineCode, Long selfUid) {
		return lambdaQuery()
				.eq(Aiclaw::getMachineCode, machineCode)
				.ne(Aiclaw::getUid, selfUid)
				.last("LIMIT 1")
				.one();
	}

	/**
	 * 查询已停用超过 24h 的 aiclaw 记录
	 */
	public List<Aiclaw> listExpiredDeactivated(LocalDateTime cutoff) {
		return lambdaQuery()
				.eq(Aiclaw::getAuthStatus, 2)
				.lt(Aiclaw::getDeactivatedAt, cutoff)
				.list();
	}
}
