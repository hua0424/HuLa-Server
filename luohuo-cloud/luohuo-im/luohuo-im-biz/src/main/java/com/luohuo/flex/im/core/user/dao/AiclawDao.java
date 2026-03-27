package com.luohuo.flex.im.core.user.dao;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.luohuo.flex.im.core.user.mapper.AiclawMapper;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
