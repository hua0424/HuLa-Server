package com.luohuo.flex.im.core.chat.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.common.constant.AiclawRedisKeys;
import com.luohuo.flex.im.core.chat.mapper.AiclawCommandPolicyMapper;
import com.luohuo.flex.im.core.chat.service.AiclawCommandPolicyService;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.core.user.service.cache.AiclawOwnerCache;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.im.domain.entity.AiclawCommandPolicy;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCommandPolicyUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCommandPolicyResp;
import com.luohuo.flex.model.entity.ws.WSCommandPolicyChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * aichatoverview#504 主人命令权限实现。
 *
 * <p>默认规则为代码常量（DEFAULT_STATE），只存主人的显式覆盖行：
 * 未配置（无行）= 继承默认；明确不开放 = state=closed 的行。读路径必须区分两者。
 *
 * <p>冻结语义（#504 issuecomment-6677394062）：
 * 群覆盖 mode=list 时名单外用户在该群内=不可调用，不回落继承默认；私聊忽略名单；
 * 主人恒过名单，主人调 approval_required = 自批放行（仍受 closed 约束）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiclawCommandPolicyServiceImpl implements AiclawCommandPolicyService {

	/** #504: 默认开放的固定能力（精确 id）。 */
	private static final Set<String> DEFAULT_OPEN_COMMANDS =
			Set.of("model.list", "model.set", "session.reset", "session.compact");

	private final AiclawCommandPolicyMapper mapper;
	private final AiclawOwnerCache ownerCache;
	private final GroupMemberCache memberCache;
	private final StringRedisTemplate stringRedisTemplate;
	private final PushService pushService;
	private final AiclawProperties aiclawProperties;

	@Override
	public Decision decide(Long aiclawUid, long roomScope, String capabilityId, Long callerUid, boolean isOwner) {
		return resolve(loadRows(aiclawUid), roomScope, capabilityId, callerUid, isOwner);
	}

	@Override
	public Map<String, String> permissionsFor(Long aiclawUid, long roomScope, Long callerUid, boolean isOwner,
			Collection<String> capabilityIds) {
		List<AiclawCommandPolicy> rows = loadRows(aiclawUid);
		Map<String, String> result = new LinkedHashMap<>();
		if (capabilityIds == null) return result;
		for (String capabilityId : capabilityIds) {
			Decision decision = resolve(rows, roomScope, capabilityId, callerUid, isOwner);
			if (decision.getVerdict() == Verdict.OPEN) result.put(capabilityId, AiclawCommandPolicy.STATE_OPEN);
			else if (decision.getVerdict() == Verdict.APPROVAL_REQUIRED)
				result.put(capabilityId, AiclawCommandPolicy.STATE_APPROVAL);
			// CLOSED: 调用方移除条目（目录过滤），菜单不可见。
		}
		return result;
	}

	/**
	 * #504 判定核心：群覆盖行（存在即进入覆盖语义）→ 默认规则行 → 代码默认。
	 */
	private static Decision resolve(List<AiclawCommandPolicy> rows, long roomScope, String capabilityId,
			Long callerUid, boolean isOwner) {
		if (capabilityId == null || capabilityId.isBlank()) return Decision.of(Verdict.CLOSED, "command_policy_denied:blank");
		AiclawCommandPolicy groupRow = null, defaultRow = null;
		for (AiclawCommandPolicy row : rows) {
			if (!capabilityId.equals(row.getCapabilityId())) continue;
			if (roomScope > 0 && Objects.equals(row.getRoomId(), roomScope)) groupRow = row;
			else if (Objects.equals(row.getRoomId(), AiclawCommandPolicy.SCOPE_DEFAULT)) defaultRow = row;
		}
		if (groupRow != null) return applyRow(groupRow, true, callerUid, isOwner);
		if (defaultRow != null) return applyRow(defaultRow, false, callerUid, isOwner);
		if (isOpenByDefault(capabilityId)) return Decision.of(Verdict.OPEN, "command_policy_default_open");
		return Decision.of(Verdict.CLOSED, "command_policy_denied:default_closed:" + capabilityId);
	}

	/**
	 * #504 行语义：closed 直接拒绝；approval 主人自批、他人挂起；open 走名单（仅群覆盖行 + 非主人）。
	 */
	private static Decision applyRow(AiclawCommandPolicy row, boolean groupScope, Long callerUid, boolean isOwner) {
		String capabilityId = row.getCapabilityId();
		switch (String.valueOf(row.getState())) {
			case AiclawCommandPolicy.STATE_CLOSED:
				return Decision.of(Verdict.CLOSED, "command_policy_denied:closed:" + capabilityId);
			case AiclawCommandPolicy.STATE_APPROVAL:
				// 主人是审批权持有者，自调即自批；他人调用本票拒绝终态（不执行、不降级放行）。
				if (isOwner) return Decision.of(Verdict.OPEN, "command_policy_owner_self_approved:" + capabilityId);
				return Decision.of(Verdict.APPROVAL_REQUIRED, "command_policy_denied:approval_required:" + capabilityId);
			case AiclawCommandPolicy.STATE_OPEN:
				if (groupScope && AiclawCommandPolicy.MODE_LIST.equals(row.getMode()) && !isOwner
						&& !allowedUids(row).contains(callerUid))
					return Decision.of(Verdict.CLOSED, "command_policy_denied:offlist:" + capabilityId);
				return Decision.of(Verdict.OPEN, "command_policy_open:" + capabilityId);
			default:
				return Decision.of(Verdict.CLOSED, "command_policy_denied:unknown_state:" + capabilityId);
		}
	}

	/**
	 * #504 默认规则（代码常量，不落库）：skill:* 与四个原生命令开放，其余 command 关闭。
	 */
	static boolean isOpenByDefault(String capabilityId) {
		if (capabilityId == null) return false;
		if (capabilityId.startsWith("skill:") && capabilityId.length() > 6) return true;
		return DEFAULT_OPEN_COMMANDS.contains(capabilityId);
	}

	private static Set<Long> allowedUids(AiclawCommandPolicy row) {
		try {
			if (row.getAllowedUids() == null || row.getAllowedUids().isBlank()) return Set.of();
			return new HashSet<>(JSONUtil.parseArray(row.getAllowedUids()).toList(Long.class));
		} catch (Exception e) {
			log.warn("Command policy allowed_uids unparsable id={}", row.getId());
			return Set.of();
		}
	}

	@Override
	public AiclawCommandPolicyResp readForOwner(Long operatorUid, Long aiclawUid, Long roomId) {
		requireOwner(operatorUid, aiclawUid);
		List<AiclawCommandPolicy> rows = loadRows(aiclawUid);
		List<AiclawCommandPolicyResp.Row> defaults = new ArrayList<>();
		for (String capabilityId : DEFAULT_OPEN_COMMANDS)
			defaults.add(row(AiclawCommandPolicy.SCOPE_DEFAULT, capabilityId, AiclawCommandPolicy.STATE_OPEN, null, null, null));
		List<AiclawCommandPolicyResp.Row> view = new ArrayList<>();
		for (AiclawCommandPolicy row : rows) view.add(toRow(row));
		Map<String, String> effective = new LinkedHashMap<>();
		long scope = roomId == null ? AiclawCommandPolicy.SCOPE_DEFAULT : roomId;
		Set<String> capabilities = new HashSet<>(DEFAULT_OPEN_COMMANDS);
		for (AiclawCommandPolicy row : rows) capabilities.add(row.getCapabilityId());
		// 主人视角：自批放行，effective 展示主人可调用终态。
		for (String capabilityId : capabilities) {
			Decision decision = resolve(rows, scope, capabilityId, operatorUid, true);
			effective.put(capabilityId, decision.getVerdict() == Verdict.OPEN
					? AiclawCommandPolicy.STATE_OPEN : AiclawCommandPolicy.STATE_CLOSED);
		}
		return AiclawCommandPolicyResp.builder().defaults(defaults).rows(view).effective(effective).build();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateForOwner(Long operatorUid, AiclawCommandPolicyUpdateReq req) {
		if (req == null || req.getAiclawUid() == null || req.getAiclawUid() <= 0
				|| req.getItems() == null || req.getItems().isEmpty() || req.getItems().size() > 128)
			throw new BizException("invalid_command_policy");
		requireOwner(operatorUid, req.getAiclawUid());
		long scope = req.getRoomId() == null ? AiclawCommandPolicy.SCOPE_DEFAULT : req.getRoomId();
		if (scope < 0) throw new BizException("invalid_command_policy");
		Set<String> seen = new HashSet<>();
		for (AiclawCommandPolicyUpdateReq.Item item : req.getItems()) {
			validateItem(item, scope, seen);
			upsert(req.getAiclawUid(), scope, item);
		}
		evict(req.getAiclawUid());
		pushChange(req.getAiclawUid(), scope, req.getItems(), operatorUid);
	}

	private void validateItem(AiclawCommandPolicyUpdateReq.Item item, long scope, Set<String> seen) {
		if (item == null || item.getCapabilityId() == null || item.getCapabilityId().isBlank()
				|| item.getCapabilityId().length() > 128 || !seen.add(item.getCapabilityId()))
			throw new BizException("invalid_command_policy");
		if (!Set.of(AiclawCommandPolicy.STATE_OPEN, AiclawCommandPolicy.STATE_CLOSED, AiclawCommandPolicy.STATE_APPROVAL)
				.contains(item.getState()))
			throw new BizException("invalid_command_policy");
		if (scope == AiclawCommandPolicy.SCOPE_DEFAULT) {
			// 默认规则行忽略名单。
			item.setMode(null);
			item.setAllowedUids(null);
		} else {
			if (item.getMode() == null) item.setMode(AiclawCommandPolicy.MODE_ALL);
			if (!Set.of(AiclawCommandPolicy.MODE_ALL, AiclawCommandPolicy.MODE_LIST).contains(item.getMode()))
				throw new BizException("invalid_command_policy");
			if (AiclawCommandPolicy.MODE_LIST.equals(item.getMode())
					&& (item.getAllowedUids() == null || item.getAllowedUids().isEmpty()))
				throw new BizException("invalid_command_policy");
			if (!AiclawCommandPolicy.MODE_LIST.equals(item.getMode())) item.setAllowedUids(null);
		}
	}

	private void upsert(Long aiclawUid, long scope, AiclawCommandPolicyUpdateReq.Item item) {
		AiclawCommandPolicy existing = mapper.selectOne(new LambdaQueryWrapper<AiclawCommandPolicy>()
				.eq(AiclawCommandPolicy::getAiclawUid, aiclawUid)
				.eq(AiclawCommandPolicy::getRoomId, scope)
				.eq(AiclawCommandPolicy::getCapabilityId, item.getCapabilityId()));
		String allowedJson = item.getAllowedUids() == null ? null : JSONUtil.toJsonStr(item.getAllowedUids());
		if (existing == null) {
			AiclawCommandPolicy row = new AiclawCommandPolicy();
			row.setAiclawUid(aiclawUid);
			row.setRoomId(scope);
			row.setCapabilityId(item.getCapabilityId());
			row.setState(item.getState());
			row.setMode(item.getMode());
			row.setAllowedUids(allowedJson);
			row.setVersion(0);
			try {
				mapper.insert(row);
			} catch (DuplicateKeyException duplicate) {
				// 并发双建：按版本冲突处理，调用方刷新后重试。
				throw new BizException("command_policy_version_conflict");
			}
			return;
		}
		if (item.getVersion() == null || !item.getVersion().equals(existing.getVersion()))
			throw new BizException("command_policy_version_conflict");
		int affected = mapper.updateStateCas(existing.getId(), existing.getVersion(),
				item.getState(), item.getMode(), allowedJson);
		if (affected != 1) throw new BizException("command_policy_version_conflict");
	}

	private void requireOwner(Long operatorUid, Long aiclawUid) {
		if (operatorUid == null || aiclawUid == null) throw new BizException("command_policy_forbidden");
		Long ownerUid = ownerCache.getOwnerUid(aiclawUid);
		if (!operatorUid.equals(ownerUid)) throw new BizException("command_policy_forbidden");
	}

	private List<AiclawCommandPolicy> loadRows(Long aiclawUid) {
		if (aiclawUid == null || aiclawUid <= 0) return List.of();
		String key = AiclawRedisKeys.COMMAND_POLICY_PREFIX + aiclawUid;
		String cached = stringRedisTemplate.opsForValue().get(key);
		if (cached != null) {
			try {
				return JSONUtil.parseArray(cached).toList(AiclawCommandPolicy.class);
			} catch (Exception e) {
				log.warn("Command policy cache unparsable aiclawUid={}", aiclawUid);
			}
		}
		List<AiclawCommandPolicy> rows = mapper.selectList(new LambdaQueryWrapper<AiclawCommandPolicy>()
				.eq(AiclawCommandPolicy::getAiclawUid, aiclawUid));
		try {
			stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(rows),
					Duration.ofMinutes(aiclawProperties.getConfig().getCacheTtlMinutes()));
		} catch (RuntimeException e) {
			log.warn("Command policy cache write failed aiclawUid={}", aiclawUid);
		}
		return rows;
	}

	private void evict(Long aiclawUid) {
		try {
			stringRedisTemplate.delete(AiclawRedisKeys.COMMAND_POLICY_PREFIX + aiclawUid);
		} catch (RuntimeException e) {
			log.warn("Command policy cache evict failed aiclawUid={}", aiclawUid);
		}
	}

	/**
	 * #504 WS 变更推送（尽力 hint，失败不影响更新；真正门控在 invoke 服务端）。
	 * 群覆盖→该群成员；默认规则→主人 + 该 Agent 各会话成员。
	 */
	private void pushChange(Long aiclawUid, long scope, List<AiclawCommandPolicyUpdateReq.Item> items, Long operatorUid) {
		try {
			Set<Long> audience = new HashSet<>();
			if (scope > 0) {
				audience.addAll(memberCache.getMemberUidList(scope));
			} else {
				if (operatorUid != null) audience.add(operatorUid);
				for (Long roomId : memberCache.getJoinedRoomIds(aiclawUid)) {
					try {
						audience.addAll(memberCache.getMemberUidList(roomId));
					} catch (RuntimeException ignored) {
					}
				}
			}
			audience.removeIf(id -> id == null || id <= 0);
			if (audience.isEmpty()) return;
			List<String> capabilityIds = new ArrayList<>();
			for (AiclawCommandPolicyUpdateReq.Item item : items) capabilityIds.add(item.getCapabilityId());
			WSCommandPolicyChange change = WSCommandPolicyChange.builder()
					.aiclawUid(String.valueOf(aiclawUid)).roomId(String.valueOf(scope))
					.capabilityIds(capabilityIds).build();
			pushService.submitAfterCommit(null, WsAdapter.buildCommandPolicyChange(change),
					new ArrayList<>(audience), operatorUid);
		} catch (RuntimeException e) {
			log.warn("Command policy push hint failed aiclawUid={} scope={}", aiclawUid, scope);
		}
	}

	private static AiclawCommandPolicyResp.Row row(Long roomId, String capabilityId, String state,
			String mode, List<Long> allowedUids, Integer version) {
		return AiclawCommandPolicyResp.Row.builder().roomId(roomId).capabilityId(capabilityId)
				.state(state).mode(mode).allowedUids(allowedUids).version(version).build();
	}

	private static AiclawCommandPolicyResp.Row toRow(AiclawCommandPolicy policy) {
		List<Long> allowed = null;
		if (policy.getAllowedUids() != null && !policy.getAllowedUids().isBlank()) {
			try {
				allowed = JSONUtil.parseArray(policy.getAllowedUids()).toList(Long.class);
			} catch (Exception e) {
				log.warn("Command policy allowed_uids unparsable id={}", policy.getId());
			}
		}
		return row(policy.getRoomId(), policy.getCapabilityId(), policy.getState(),
				policy.getMode(), allowed, policy.getVersion());
	}
}
