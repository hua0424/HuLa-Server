package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import com.luohuo.flex.model.entity.ws.WSGroupConfigChange;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * aiclaw 群聊配置服务实现
 */
@Slf4j
@Service
@AllArgsConstructor
public class AiclawGroupConfigServiceImpl implements AiclawGroupConfigService {

	private final AiclawGroupConfigMapper aiclawGroupConfigMapper;
	private final AiclawOwnerCache aiclawOwnerCache;
	private final GroupMemberCache groupMemberCache;
	private final PushService pushService;

	@Override
	public AiclawGroupConfigResp getConfig(Long aiclawUid, Long roomId, Long uid) {
		// 校验调用者是群成员
		List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
		if (memberUids == null || !memberUids.contains(uid)) {
			throw new BizException("您不在该群中，无法查看配置");
		}

		AiclawGroupConfig config = aiclawGroupConfigMapper.selectOne(
				new LambdaQueryWrapper<AiclawGroupConfig>()
						.eq(AiclawGroupConfig::getAiclawUid, aiclawUid)
						.eq(AiclawGroupConfig::getRoomId, roomId));

		if (config == null) {
			// 无记录时返回默认值
			return AiclawGroupConfigResp.builder()
					.aiclawUid(aiclawUid)
					.roomId(roomId)
					.rateLimitPerMinute(10)
					.mentionRequired(0)
					.dailyLimit(1000)
					.respondToAi(1)
					.shortReplyThreshold(10)
					.shortReplyLookback(3)
					.build();
		}

		return BeanUtil.copyProperties(config, AiclawGroupConfigResp.class);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateConfig(AiclawGroupConfigUpdateReq request, Long uid) {
		Long aiclawUid = request.getAiclawUid();
		Long roomId = request.getRoomId();

		// 校验调用者是 aiclaw 主人或 aiclaw 本人（带 Redis 缓存）
		Long ownerUid = aiclawOwnerCache.getOwnerUid(aiclawUid);
		if (ownerUid == null) {
			throw new BizException("AI助理不存在");
		}
		if (!uid.equals(ownerUid) && !uid.equals(aiclawUid)) {
			throw new BizException("只有AI助理主人或AI助理本人可以修改群配置");
		}

		// 校验 aiclaw 在该群中
		List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
		if (memberUids == null || !memberUids.contains(aiclawUid)) {
			throw new BizException("该AI助理不在此群中");
		}

		AiclawGroupConfig config = aiclawGroupConfigMapper.selectOne(
				new LambdaQueryWrapper<AiclawGroupConfig>()
						.eq(AiclawGroupConfig::getAiclawUid, aiclawUid)
						.eq(AiclawGroupConfig::getRoomId, roomId));

		if (config == null) {
			// 首次创建配置记录
			config = new AiclawGroupConfig();
			config.setAiclawUid(aiclawUid);
			config.setRoomId(roomId);
			fillConfigFields(config, request);
			aiclawGroupConfigMapper.insert(config);
			log.info("aiclaw group config created: aiclawUid={}, roomId={}", aiclawUid, roomId);
		} else {
			fillConfigFields(config, request);
			aiclawGroupConfigMapper.updateById(config);
			log.info("aiclaw group config updated: aiclawUid={}, roomId={}", aiclawUid, roomId);
		}

		// REQ-004 M3-5: WS 广播配置变更到群内所有成员
		WSGroupConfigChange.ConfigDTO configDTO = WSGroupConfigChange.ConfigDTO.builder()
				.rateLimitPerMinute(config.getRateLimitPerMinute())
				.mentionRequired(config.getMentionRequired())
				.dailyLimit(config.getDailyLimit())
				.respondToAi(config.getRespondToAi())
				.shortReplyThreshold(config.getShortReplyThreshold())
				.shortReplyLookback(config.getShortReplyLookback())
				.build();
		WSGroupConfigChange change = WSGroupConfigChange.builder()
				.aiclawUid(String.valueOf(aiclawUid))
				.roomId(String.valueOf(roomId))
				.config(configDTO)
				.build();
		pushService.sendPushMsg(WsAdapter.buildGroupConfigChange(change), memberUids, uid);
		log.debug("group config change broadcast: aiclawUid={}, roomId={}, members={}", aiclawUid, roomId, memberUids.size());
	}

	private void fillConfigFields(AiclawGroupConfig config, AiclawGroupConfigUpdateReq request) {
		if (request.getRateLimitPerMinute() != null) {
			config.setRateLimitPerMinute(request.getRateLimitPerMinute());
		}
		if (request.getMentionRequired() != null) {
			config.setMentionRequired(request.getMentionRequired());
		}
		if (request.getDailyLimit() != null) {
			config.setDailyLimit(request.getDailyLimit());
		}
		if (request.getRespondToAi() != null) {
			config.setRespondToAi(request.getRespondToAi());
		}
		if (request.getShortReplyThreshold() != null) {
			config.setShortReplyThreshold(request.getShortReplyThreshold());
		}
		if (request.getShortReplyLookback() != null) {
			config.setShortReplyLookback(request.getShortReplyLookback());
		}
	}
}
