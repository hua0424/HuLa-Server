package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.mapper.AiclawGroupConfigMapper;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomGroupCache;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import com.luohuo.flex.model.entity.ws.WSGroupConfigChange;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * aiclaw 群聊配置服务实现
 */
@Slf4j
@Service
@AllArgsConstructor
public class AiclawGroupConfigServiceImpl implements AiclawGroupConfigService {

	private static final String REDIS_CONFIG_KEY_PREFIX = "im:aiclaw:group:config:";
	private static final Duration CONFIG_CACHE_TTL = Duration.ofMinutes(30);

	private final AiclawGroupConfigMapper aiclawGroupConfigMapper;
	private final AiclawOwnerCache aiclawOwnerCache;
	private final GroupMemberCache groupMemberCache;
	private final PushService pushService;
	private final StringRedisTemplate stringRedisTemplate;
	private final RoomGroupCache roomGroupCache;
	private final AiclawDao aiclawDao;

	@Override
	public AiclawGroupConfigResp getConfig(Long aiclawUid, Long roomId, Long uid) {
		// 校验调用者是群成员
		List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
		if (memberUids == null || !memberUids.contains(uid)) {
			throw new BizException("您不在该群中，无法查看配置");
		}

		// 先查 Redis 缓存
		String cacheKey = buildConfigCacheKey(aiclawUid, roomId);
		String cached = stringRedisTemplate.opsForValue().get(cacheKey);
		if (cached != null) {
			return JSONUtil.toBean(cached, AiclawGroupConfigResp.class);
		}

		AiclawGroupConfig config = aiclawGroupConfigMapper.selectOne(
				new LambdaQueryWrapper<AiclawGroupConfig>()
						.eq(AiclawGroupConfig::getAiclawUid, aiclawUid)
						.eq(AiclawGroupConfig::getRoomId, roomId));

		AiclawGroupConfigResp resp;
		if (config == null) {
			// 无记录时返回默认值
			// REQ-009#82: approved 默认 0（未批准/沉默）。此默认 Resp 会被缓存——若不显式置 0，
			// approved 为 null，下游 gate 会把 null 当作「已批准」而错误放行。workspace_dir 默认 null。
			resp = AiclawGroupConfigResp.builder()
					.aiclawUid(aiclawUid)
					.roomId(roomId)
					.rateLimitPerMinute(10)
					.mentionRequired(1)
					.dailyLimit(1000)
					.respondToAi(1)
					.approved(0)
					.build();
		} else {
			resp = BeanUtil.copyProperties(config, AiclawGroupConfigResp.class);
		}
		// REQ-009#82: 回填群号（默认值路径与实体路径都需要），供 plugins 派生工作目录 groupkey
		resp.setAccount(accountOf(roomId));

		// 写入 Redis 缓存（默认值也缓存，避免穿透）
		stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(resp), CONFIG_CACHE_TTL);
		return resp;
	}

	@Override
	public List<AiclawGroupConfigResp> listSelfConfigs(Long aiclawUid) {
		// aichatoverview#26: 仅按传入的 aiclawUid（来自认证身份）查询该 aiclaw 自己的全部配置行。
		// 只返回已落库的行，不补默认值；空结果返回空 List。无群成员/owner 校验——
		// 因为查询范围已被 aiclawUid 限定为「自己」，且 aiclawUid 来自 ContextUtil.getUid()。
		// 软删：AiclawGroupConfig 经基类 SuperEntity 的 @TableLogic(is_del) 全局逻辑删除，
		// MyBatis-Plus 自动为 selectList 追加 is_del=0、过滤软删行（与 getConfig 行为一致），无需手加条件。
		List<AiclawGroupConfig> configs = aiclawGroupConfigMapper.selectList(
				new LambdaQueryWrapper<AiclawGroupConfig>()
						.eq(AiclawGroupConfig::getAiclawUid, aiclawUid));
		return configs.stream()
				.map(c -> {
					AiclawGroupConfigResp r = BeanUtil.copyProperties(c, AiclawGroupConfigResp.class);
					// REQ-009#82: 按 roomId 回填群号
					r.setAccount(accountOf(c.getRoomId()));
					return r;
				})
				.toList();
	}

	@Override
	public boolean isApproved(Long aiclawUid, Long roomId) {
		// 优先读 Redis 缓存
		String cacheKey = buildConfigCacheKey(aiclawUid, roomId);
		String cached = stringRedisTemplate.opsForValue().get(cacheKey);
		if (cached != null) {
			AiclawGroupConfigResp resp = JSONUtil.toBean(cached, AiclawGroupConfigResp.class);
			// 合同（#82）：approved == 1 为已批准；null/0 为未批准
			return Integer.valueOf(1).equals(resp.getApproved());
		}

		// 缓存未命中：回落 DB
		AiclawGroupConfig config = aiclawGroupConfigMapper.selectOne(
				new LambdaQueryWrapper<AiclawGroupConfig>()
						.eq(AiclawGroupConfig::getAiclawUid, aiclawUid)
						.eq(AiclawGroupConfig::getRoomId, roomId));

		AiclawGroupConfigResp resp;
		if (config == null) {
			// 无记录：默认未批准（approved=0），与 getConfig 默认值路径保持一致
			resp = AiclawGroupConfigResp.builder()
					.aiclawUid(aiclawUid)
					.roomId(roomId)
					.rateLimitPerMinute(10)
					.mentionRequired(1)
					.dailyLimit(1000)
					.respondToAi(1)
					.approved(0)
					.build();
		} else {
			resp = BeanUtil.copyProperties(config, AiclawGroupConfigResp.class);
		}
		resp.setAccount(accountOf(roomId));

		// 预热缓存（避免穿透），与 getConfig 行为一致
		stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(resp), CONFIG_CACHE_TTL);
		return Integer.valueOf(1).equals(resp.getApproved());
	}

	@Override
	public List<Long> filterUnapprovedAiclawRecipients(List<Long> memberUids, Long roomId) {
		if (memberUids == null || memberUids.isEmpty()) {
			return memberUids;
		}
		// 找出收件人中属于 aiclaw 的 uid
		List<Aiclaw> aiclaws = aiclawDao.listByUids(memberUids);
		if (aiclaws == null || aiclaws.isEmpty()) {
			// 没有 aiclaw 成员 → 无需过滤
			return memberUids;
		}
		Set<Long> aiclawUids = aiclaws.stream()
				.map(Aiclaw::getUid)
				.collect(Collectors.toSet());

		List<Long> result = new ArrayList<>(memberUids.size());
		for (Long uid : memberUids) {
			// 非 aiclaw 永远保留；aiclaw 仅在已批准时保留
			if (aiclawUids.contains(uid) && !isApproved(uid, roomId)) {
				continue;
			}
			result.add(uid);
		}
		return result;
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

		// REQ-009#82: 字段级权限——approved / workspaceDir 仅群主可设置（拒绝 aiclaw 本人自我批准，
		// 这是 gate 的核心意义）。旧字段（rate/mention/daily/respondToAi）保持「群主或本人」皆可。
		if ((request.getApproved() != null || request.getWorkspaceDir() != null) && !uid.equals(ownerUid)) {
			throw new BizException("只有AI助理主人可以批准或配置工作目录");
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
			// BL-027 (#55): selectOne 读回的实体带旧 updateTime，而 LuohuoMetaObjectHandler 仅在 updateTime==null 时填充；置空以让其在 updateById 时刷新 update_time
			config.setUpdateTime(null);
			aiclawGroupConfigMapper.updateById(config);
			log.info("aiclaw group config updated: aiclawUid={}, roomId={}", aiclawUid, roomId);
		}

		// 更新 Redis 缓存
		// REQ-009#82: BeanUtil 已携带 approved/workspaceDir；再补 account 后入缓存，
		// 使下游 gate 从缓存读到的 Resp 含完整批准态与群号。
		AiclawGroupConfigResp cachedResp = BeanUtil.copyProperties(config, AiclawGroupConfigResp.class);
		cachedResp.setAccount(accountOf(roomId));
		stringRedisTemplate.opsForValue().set(
				buildConfigCacheKey(aiclawUid, roomId), JSONUtil.toJsonStr(cachedResp), CONFIG_CACHE_TTL);

		// aichatoverview#3: WS 广播配置变更到群内所有成员
		WSGroupConfigChange.ConfigDTO configDTO = WSGroupConfigChange.ConfigDTO.builder()
				.rateLimitPerMinute(config.getRateLimitPerMinute())
				.mentionRequired(config.getMentionRequired())
				.dailyLimit(config.getDailyLimit())
				.respondToAi(config.getRespondToAi())
				.approved(config.getApproved())
				.workspaceDir(config.getWorkspaceDir())
				.build();
		WSGroupConfigChange change = WSGroupConfigChange.builder()
				.aiclawUid(String.valueOf(aiclawUid))
				.roomId(String.valueOf(roomId))
				.config(configDTO)
				.account(accountOf(roomId))
				.build();
		pushService.sendPushMsg(WsAdapter.buildGroupConfigChange(change), memberUids, uid);
		log.debug("group config change broadcast: aiclawUid={}, roomId={}, members={}", aiclawUid, roomId, memberUids.size());
	}

	private String buildConfigCacheKey(Long aiclawUid, Long roomId) {
		return REDIS_CONFIG_KEY_PREFIX + aiclawUid + ":" + roomId;
	}

	/**
	 * REQ-009#82: 取群的可读群号（account），null-safe。
	 */
	private String accountOf(Long roomId) {
		RoomGroup rg = roomGroupCache.get(roomId);
		return rg != null ? rg.getAccount() : null;
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
		// REQ-009#82: approved / workspaceDir 仅在请求显式携带时更新。
		// 撤销天然成立：群主发 approved=0 且不带 workspaceDir → approved 置 0、workspaceDir 原值保留。
		if (request.getApproved() != null) {
			config.setApproved(request.getApproved());
		}
		if (request.getWorkspaceDir() != null) {
			config.setWorkspaceDir(request.getWorkspaceDir());
		}
		// aichatoverview#3: shortReplyThreshold / shortReplyLookback 已从 UpdateReq 移除，不再处理
	}
}
