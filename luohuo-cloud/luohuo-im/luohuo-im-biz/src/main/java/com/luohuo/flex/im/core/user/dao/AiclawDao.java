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
	 * 查询已停用超过 24h 的 aiclaw 记录
	 */
	public List<Aiclaw> listExpiredDeactivated(LocalDateTime cutoff) {
		return lambdaQuery()
				.eq(Aiclaw::getAuthStatus, 2)
				.lt(Aiclaw::getDeactivatedAt, cutoff)
				.list();
	}
}
