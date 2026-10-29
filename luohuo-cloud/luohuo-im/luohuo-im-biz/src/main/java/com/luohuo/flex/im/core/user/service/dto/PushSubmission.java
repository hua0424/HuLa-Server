package com.luohuo.flex.im.core.user.service.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #482 推送提交结果：业务请求返回前可用的“实际准入快照”。
 * <p>
 * 三类事实严格分离，不允许互相冒充：
 * <ul>
 *   <li>{@link RouteStatus} + {@link Target} 的准入结论：路由查询与执行器准入已定稿，返回后不再变化；</li>
 *   <li>{@link FirstAttempt}：每个已准入目标的一次 MQ 提交结果，由节点任务异步回填，不含重试；</li>
 *   <li>客户端 ACK / 最终送达：本对象完全不表达，ACK 有独立契约（见 #482 §4）。</li>
 * </ul>
 * 聚合 {@link Admission} 只对“真实提交的目标集合”计算；{@link RouteStatus#NO_TARGET}、
 * {@link RouteStatus#FAILED} 单独表达，绝不被折叠成“全部送达”。
 */
public final class PushSubmission {

	/** 路由与准入状态。RESOLVED 才存在可讨论的目标集合。 */
	public enum RouteStatus {
		/** 已解析出目标并逐个给出准入结论。 */
		RESOLVED,
		/** 查询成功但没有匹配到任何在线目标设备（≠ 已送达）。 */
		NO_TARGET,
		/** 路由查询失败/查询结果不可用，目标范围未知（不伪造目标）。 */
		FAILED
	}

	/** 单个目标（节点 + 用户 + 设备）的准入结论。 */
	public enum TargetState {
		ACCEPTED,
		REJECTED
	}

	/** 已准入目标的首次 MQ 提交结果。NOT_ATTEMPTED 包含“已接收但排空期未开始执行”。 */
	public enum FirstAttempt {
		NOT_ATTEMPTED,
		SUCCESS,
		DEFINITE_FAIL,
		UNCERTAIN
	}

	/** 聚合结论，仅对真实提交集合计算。 */
	public enum Admission {
		ALL,
		PARTIAL,
		NONE
	}

	/** 一个目标设备及其准入/首投事实。 */
	public static final class Target {
		private final String nodeId;
		private final Long uid;
		private final String deviceId;
		private volatile TargetState state = TargetState.ACCEPTED;
		private volatile String reason;
		private volatile FirstAttempt firstAttempt = FirstAttempt.NOT_ATTEMPTED;
		private volatile String firstAttemptReason;

		Target(String nodeId, Long uid, String deviceId) {
			this.nodeId = nodeId;
			this.uid = uid;
			this.deviceId = deviceId;
		}

		public String getNodeId() {
			return nodeId;
		}

		public Long getUid() {
			return uid;
		}

		public String getDeviceId() {
			return deviceId;
		}

		public TargetState getState() {
			return state;
		}

		public String getReason() {
			return reason;
		}

		public FirstAttempt getFirstAttempt() {
			return firstAttempt;
		}

		public String getFirstAttemptReason() {
			return firstAttemptReason;
		}

		/** 准入结论由提交流程在返回前定稿。 */
		public void reject(String rejectReason) {
			this.state = TargetState.REJECTED;
			this.reason = rejectReason;
		}

		/** 首投结果只允许异步回填一次最终态，不回退、不覆盖已完成结果。 */
		public synchronized void markFirstAttempt(FirstAttempt attempt, String attemptReason) {
			if (firstAttempt != FirstAttempt.NOT_ATTEMPTED) {
				return;
			}
			this.firstAttempt = attempt;
			this.firstAttemptReason = attemptReason;
		}
	}

	private final RouteStatus routeStatus;
	private final String routeFailure;
	private final List<Long> unresolvedUids;
	private final List<Target> targets;
	/** 非空表示整次提交在准入阶段被明确拒绝（如停止期），未被折叠进路由状态。 */
	private final String admissionRejection;

	private PushSubmission(RouteStatus routeStatus, String routeFailure, List<Long> unresolvedUids,
			List<Target> targets, String admissionRejection) {
		this.routeStatus = routeStatus;
		this.routeFailure = routeFailure;
		this.unresolvedUids = unresolvedUids;
		this.targets = targets;
		this.admissionRejection = admissionRejection;
	}

	public static PushSubmission resolved(List<Target> targets) {
		return new PushSubmission(RouteStatus.RESOLVED, null, List.of(), List.copyOf(targets), null);
	}

	public static PushSubmission noTarget() {
		return new PushSubmission(RouteStatus.NO_TARGET, null, List.of(), List.of(), null);
	}

	public static PushSubmission routeFailed(List<Long> uids, String reason) {
		return new PushSubmission(RouteStatus.FAILED, reason, distinct(uids), List.of(), null);
	}

	/** 整次提交被准入拒绝：目标集合按已解析结果保留（可能为空），不伪造设备。 */
	public static PushSubmission refused(List<Long> uids, List<Target> targets, String reason) {
		return new PushSubmission(targets.isEmpty() ? RouteStatus.NO_TARGET : RouteStatus.RESOLVED,
				null, distinct(uids), List.copyOf(targets), reason);
	}

	/** 合并多批（如 pushRoom 按 200 分批）结果，事实各自保留，不互相掩盖。 */
	public static PushSubmission combine(List<PushSubmission> parts) {
		List<Target> allTargets = new ArrayList<>();
		List<Long> unresolved = new ArrayList<>();
		String failure = null;
		String rejection = null;
		boolean anyResolved = false;
		for (PushSubmission part : parts) {
			allTargets.addAll(part.targets);
			unresolved.addAll(part.unresolvedUids);
			if (part.routeStatus == RouteStatus.FAILED) {
				anyResolved = true;
				failure = failure == null ? part.routeFailure : failure + "; " + part.routeFailure;
			}
			if (part.routeStatus == RouteStatus.RESOLVED) {
				anyResolved = true;
			}
			if (part.admissionRejection != null) {
				rejection = part.admissionRejection;
			}
		}
		RouteStatus status = failure != null ? RouteStatus.FAILED
				: (anyResolved ? RouteStatus.RESOLVED : RouteStatus.NO_TARGET);
		return new PushSubmission(status, failure, List.copyOf(unresolved), List.copyOf(allTargets), rejection);
	}

	/** 为一条节点路由创建目标快照；deviceId 在前端为 clientId。 */
	public static List<Target> targetsOf(String nodeId, Map<String, Long> deviceUserMap) {
		List<Target> result = new ArrayList<>();
		if (deviceUserMap == null) {
			return result;
		}
		deviceUserMap.forEach((deviceId, uid) -> result.add(new Target(nodeId, uid, deviceId)));
		return result;
	}

	public RouteStatus getRouteStatus() {
		return routeStatus;
	}

	public String getRouteFailure() {
		return routeFailure;
	}

	/** 整次提交被准入拒绝的原因（如停止期拒绝）；正常提交为 null。 */
	public String getAdmissionRejection() {
		return admissionRejection;
	}

	/** 路由失败时未知的目标 uid 范围；不伪造设备目标。 */
	public List<Long> getUnresolvedUids() {
		return unresolvedUids;
	}

	/** 已解析目标集合（含准入结论与异步首投结果）。 */
	public List<Target> getTargets() {
		return targets;
	}

	/** 仅 RESOLVED 时有聚合结论；NO_TARGET/FAILED 返回 null，避免被读成“全部成功”。 */
	public Admission getAdmission() {
		if (routeStatus != RouteStatus.RESOLVED || targets.isEmpty()) {
			return null;
		}
		long accepted = targets.stream().filter(t -> t.getState() == TargetState.ACCEPTED).count();
		if (accepted == targets.size()) {
			return Admission.ALL;
		}
		return accepted == 0 ? Admission.NONE : Admission.PARTIAL;
	}

	/** 只定位未接收目标：准入被拒的设备目标 + 路由失败时未知范围的 uid。 */
	public List<Target> getRejectedTargets() {
		List<Target> rejected = new ArrayList<>();
		for (Target target : targets) {
			if (target.getState() == TargetState.REJECTED) {
				rejected.add(target);
			}
		}
		return rejected;
	}

	public List<Long> getUnreceivedUids() {
		Map<Long, Boolean> uids = new LinkedHashMap<>();
		getRejectedTargets().forEach(t -> uids.put(t.getUid(), Boolean.TRUE));
		unresolvedUids.forEach(uid -> uids.put(uid, Boolean.TRUE));
		return new ArrayList<>(uids.keySet());
	}

	/** 已解析且全部目标被接收——唯一可称“本次提交未被拒”的状态。 */
	public boolean isFullyAdmitted() {
		return routeStatus == RouteStatus.RESOLVED && !targets.isEmpty()
				&& targets.stream().allMatch(t -> t.getState() == TargetState.ACCEPTED);
	}


	/**
	 * 是否需要对已提交业务追加“已保存、即时同步未完成”的用户提示。
	 * 只在真实提交集合里存在被拒目标时为真：无在线目标/路由失败属于单独上报的事实，
	 * 不能据此断言用户其它端同步失败，也不能反过来判定业务失败。
	 */
	public boolean isSyncIncomplete() {
		return getAdmission() == Admission.PARTIAL || getAdmission() == Admission.NONE;
	}

	private static List<Long> distinct(List<Long> uids) {
		if (uids == null || uids.isEmpty()) {
			return List.of();
		}
		Map<Long, Boolean> seen = new LinkedHashMap<>();
		uids.forEach(uid -> seen.put(uid, Boolean.TRUE));
		return List.copyOf(seen.keySet());
	}

	@Override
	public String toString() {
		return "PushSubmission{route=" + routeStatus
				+ (routeFailure == null ? "" : ", routeFailure=" + routeFailure)
				+ ", admission=" + getAdmission()
				+ ", targets=" + targets.size()
				+ ", rejected=" + getRejectedTargets().size()
				+ ", unresolved=" + unresolvedUids
				+ '}';
	}

	/** 只读空快照，便于调用方安全消费，不用 null 表达“无推送”。 */
	public static PushSubmission none() {
		return new PushSubmission(RouteStatus.NO_TARGET, null, Collections.emptyList(), Collections.emptyList(), null);
	}
}
