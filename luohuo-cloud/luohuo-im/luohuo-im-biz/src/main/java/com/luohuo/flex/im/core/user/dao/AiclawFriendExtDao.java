package com.luohuo.flex.im.core.user.dao;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.luohuo.flex.im.core.user.mapper.AiclawFriendExtMapper;
import com.luohuo.flex.im.domain.entity.AiclawFriendExt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * aiclaw 好友扩展表 DAO
 */
@Service
public class AiclawFriendExtDao extends ServiceImpl<AiclawFriendExtMapper, AiclawFriendExt> {

	public AiclawFriendExt getByAiclawAndFriend(Long aiclawUid, Long friendUid) {
		return lambdaQuery()
				.eq(AiclawFriendExt::getAiclawUid, aiclawUid)
				.eq(AiclawFriendExt::getFriendUid, friendUid)
				.one();
	}

	public List<AiclawFriendExt> listByAiclaw(Long aiclawUid) {
		return lambdaQuery()
				.eq(AiclawFriendExt::getAiclawUid, aiclawUid)
				.list();
	}
}
