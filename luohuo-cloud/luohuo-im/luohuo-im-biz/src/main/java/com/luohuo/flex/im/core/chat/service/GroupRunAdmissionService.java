package com.luohuo.flex.im.core.chat.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.luohuo.flex.im.core.chat.mapper.GroupRunAdmissionMapper;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawGroupConfig;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.im.enums.UserTypeEnum;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * aichatoverview#485 T4：群执行的「当前事实」准入。
 *
 * <p>本类只回答一个问题：<b>此刻</b>某个 aiclaw 身份能否在该房间获得一次新的执行准入，或继续执行一次受限操作。
 * 事实来源是数据库当前行（加锁读），不是 Redis 配置缓存、成员缓存或到达过的通知；因此漏通知、旧缓存
 * 都不会让撤权失效。</p>
 *
 * <p>授权代次（authorization epoch）由「当时参与判定的事实行」推导：配置行 id + 配置版本 + approved +
 * workspaceDir、成员行 id、身份行 id + 状态/批准状态/停用时间。软删不释放
 * {@code im_aiclaw_group_config} 的 (aiclaw_uid, room_id) 唯一键，成员与配置行重建会拿到新的雪花 id，
 * 所以撤权→再批准、成员删除→重入、身份停用→恢复都会得到不同的代次；同一代次只在事实未变时重复出现。
 * 这不需要新增列，也不会把 ABA 折叠成一个布尔。</p>
 *
 * <p>并发顺序以本类事务提交为准：准入事务先提交即该次执行合法；撤权事务先提交则后续准入必然读到并拒绝。
 * 加锁读是 MySQL 的当前读（不是快照读），所以即使被调用方事务包着也不会读到旧快照；本类不持锁跨越
 * 任何网络或 native 调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRunAdmissionService {

	/** 已获得该次执行的准入。 */
	public static final String ADMITTED = "ADMITTED";
	/** 终态拒绝：重新批准、迟到通知、重放都不能让该任务复活，必须由新的合法输入重新准入。 */
	public static final String DENIED = "DENIED";

	public static final String REASON_ROOM_GONE = "room_not_accessible";
	public static final String REASON_CONFIG_MISSING = "authorization_missing_config";
	public static final String REASON_NOT_APPROVED = "authorization_not_approved";
	public static final String REASON_MEMBERSHIP_GONE = "authorization_membership_revoked";
	public static final String REASON_IDENTITY_INACTIVE = "authorization_identity_inactive";
	public static final String REASON_EPOCH_STALE = "authorization_stale";
	public static final String REASON_INVALID = "authorization_invalid";

	public static final String SCHEMA = "group-admission-v1";

	private final GroupRunAdmissionMapper admissionMapper;

	@Getter
	@Builder
	public static class Admission {
		private final String outcome;
		private final Long roomId;
		private final Long actorUid;
		private final String authorizationEpoch;
		private final String workspaceDir;
		private final String reason;
		private final String schema;

		public boolean admitted() {
			return ADMITTED.equals(outcome);
		}
	}

	/**
	 * 一次新执行的准入（Seam1：thinking START 创建/重放之前调用）。
	 *
	 * <p>它同时是「已运行任务的后续受限 server 操作」的逐次校验入口：调用方（消息发送、流式落库、
	 * thinking 收尾、成员查询、媒体签名）在每次真正做事之前重新问一遍，不会因为一次准入就变成长期授权。</p>
	 *
	 * @param requestedEpoch 输入入队时可信读取到的授权代次；为 null 表示调用方没有可信来源，此时只做当前事实判定
	 */
	@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
	public Admission admitGroupRun(Long actor, Long roomId, Long tenantId, String requestedEpoch) {
		return resolve(actor, roomId, tenantId, requestedEpoch, true);
	}

	/**
	 * 只读当前代次（供 #484 的配置当前读返回给 plugins 做输入标记）。
	 *
	 * <p>不取行锁：只读事务里 {@code FOR UPDATE} 会被连接的 READ ONLY 语义拒绝，而代次推导用到的列
	 * 与加锁路径完全一致，所以这里用同一条判定的非加锁版本。</p>
	 */
	@Transactional(propagation = Propagation.REQUIRED, readOnly = true)
	public String currentEpoch(Long actor, Long roomId, Long tenantId) {
		return resolve(actor, roomId, tenantId, null, false).getAuthorizationEpoch();
	}

	private Admission resolve(Long actor, Long roomId, Long tenantId, String requestedEpoch, boolean lock) {
		if (actor == null || actor <= 0 || roomId == null || roomId <= 0 || tenantId == null || tenantId <= 0) {
			return deny(roomId, actor, REASON_INVALID);
		}
		Room room = admissionMapper.selectRoom(roomId, tenantId);
		if (room == null || Boolean.TRUE.equals(room.getIsDel())) {
			return deny(roomId, actor, REASON_ROOM_GONE);
		}

		// 固定锁序：配置行 → 成员行 → 身份行。撤权写入方（配置更新、踢人、身份停用）按同一顺序争用。
		AiclawGroupConfig config = null;
		String reason = null;
		if (!Objects.equals(room.getType(), RoomTypeEnum.FRIEND.getType())) {
			config = lock ? admissionMapper.lockConfig(actor, roomId) : admissionMapper.selectConfig(actor, roomId);
			reason = configDenialReason(config);
		}
		Long membershipId = lock ? admissionMapper.lockMembership(actor, roomId, tenantId)
				: admissionMapper.selectMembership(actor, roomId, tenantId);
		User user = lock ? admissionMapper.lockUser(actor) : admissionMapper.selectUser(actor);
		Aiclaw aiclaw = lock ? admissionMapper.lockAiclaw(actor) : admissionMapper.selectAiclaw(actor);

		String identityIncarnation = identityIncarnation(user, aiclaw);
		String epoch = epochOf(config, membershipId, identityIncarnation);

		if (reason != null) {
			return new Admission(DENIED, roomId, actor, epoch, null, reason, SCHEMA);
		}
		if (membershipId == null) {
			return new Admission(DENIED, roomId, actor, epoch, null, REASON_MEMBERSHIP_GONE, SCHEMA);
		}
		if (!identityActive(user, aiclaw, tenantId)) {
			return new Admission(DENIED, roomId, actor, epoch, null, REASON_IDENTITY_INACTIVE, SCHEMA);
		}
		if (requestedEpoch != null && !requestedEpoch.equals(epoch)) {
			// 输入入队时的授权上下文已经变了（撤权后重新批准、成员重入、身份恢复、workspace 变更）：
			// 旧输入不得被新代次「洗新」，只能由新的合法输入重新准入。
			return new Admission(DENIED, roomId, actor, epoch, null, REASON_EPOCH_STALE, SCHEMA);
		}
		return new Admission(ADMITTED, roomId, actor, epoch, config == null ? null : config.getWorkspaceDir(),
				null, SCHEMA);
	}

	private Admission deny(Long roomId, Long actor, String reason) {
		return new Admission(DENIED, roomId, actor, null, null, reason, SCHEMA);
	}

	private String configDenialReason(AiclawGroupConfig config) {
		if (config == null) {
			return REASON_CONFIG_MISSING;
		}
		if (Boolean.TRUE.equals(config.getIsDel())) {
			// 软删行仍占唯一键；它不是有效配置，也不能当成缺行去创建新行。
			return REASON_NOT_APPROVED;
		}
		if (!Integer.valueOf(1).equals(config.getApproved())) {
			return REASON_NOT_APPROVED;
		}
		return null;
	}

	private boolean identityActive(User user, Aiclaw aiclaw, Long tenantId) {
		if (user == null || aiclaw == null) {
			return false;
		}
		return UserTypeEnum.AICLAW.getValue().equals(user.getUserType())
				&& Objects.equals(user.getState(), 0)
				&& Objects.equals(user.getTenantId(), tenantId)
				&& Objects.equals(aiclaw.getTenantId(), tenantId)
				&& Integer.valueOf(1).equals(aiclaw.getAuthStatus())
				&& aiclaw.getDeactivatedAt() == null;
	}

	private String identityIncarnation(User user, Aiclaw aiclaw) {
		if (user == null || aiclaw == null) {
			return "none";
		}
		return user.getId() + ":" + user.getState() + ":" + aiclaw.getId() + ":" + aiclaw.getAuthStatus()
				+ ":" + (aiclaw.getDeactivatedAt() == null ? "-" : aiclaw.getDeactivatedAt());
	}

	private String epochOf(AiclawGroupConfig config, Long membershipId, String identityIncarnation) {
		String configPart = config == null
				? "cfg-none"
				: "cfg-" + config.getId() + "-" + config.getUpdateTime() + "-" + config.getApproved()
						+ "-" + (config.getWorkspaceDir() == null ? "-" : config.getWorkspaceDir());
		return DigestUtil.sha256Hex(configPart + "|mem-" + membershipId + "|id-" + identityIncarnation)
				.substring(0, 32);
	}
}
