package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCommandPolicyUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCommandPolicyResp;

import java.util.Collection;
import java.util.Map;

/**
 * aichatoverview#504 主人命令权限（默认规则 + 群覆盖 + 指定名单）。
 *
 * <p>判定点固定在 server 公共层：submit() 调用前门控 + complete() 目录过滤/标注。
 * node/前端只做菜单隐藏与标注，不做放行判定。
 */
public interface AiclawCommandPolicyService {

	/**
	 * #504 P1-2：门控拒绝码（下划线风格）。
	 *
	 * <p>唯一字面量源：前端 {@code frontend/src/hooks/useAgentCommands.ts} 以
	 * {@code includes('command_policy_denied_approval'/'command_policy_denied_closed')} 匹配，
	 * 后缀 {@code :capabilityId} 不影响匹配；改动本常量必须同步前端与两侧测试桩。
	 */
	String DENIED_CLOSED = "command_policy_denied_closed";
	String DENIED_APPROVAL = "command_policy_denied_approval";

	/** #504 判定终态：放行 / 需主人审批 / 拒绝。 */
	enum Verdict {
		OPEN,
		APPROVAL_REQUIRED,
		CLOSED
	}

	/**
	 * #504 单能力判定结果（公开结果 + 审计原因码）。
	 */
	class Decision {
		private final Verdict verdict;
		private final String reason;

		private Decision(Verdict verdict, String reason) {
			this.verdict = verdict;
			this.reason = reason;
		}

		public static Decision of(Verdict verdict, String reason) {
			return new Decision(verdict, reason);
		}

		public Verdict getVerdict() {
			return verdict;
		}

		public String getReason() {
			return reason;
		}

		public boolean isOpen() {
			return verdict == Verdict.OPEN;
		}
	}

	/**
	 * #504: 判定单能力调用。
	 *
	 * @param aiclawUid    Agent uid
	 * @param roomScope    0=默认规则/私聊；&gt;0=群 room_id（覆盖语义）
	 * @param capabilityId 能力精确 id
	 * @param callerUid    调用人类 uid
	 * @param isOwner      调用人是否为该 Agent 主人（名单先行含主人；名单内主人调 approval=自批放行）
	 * @return 判定（open / approval_required / closed + 原因码）
	 */
	Decision decide(Long aiclawUid, long roomScope, String capabilityId, Long callerUid, boolean isOwner);

	/**
	 * #504: 批量判定（目录过滤/标注用）。返回 capabilityId -&gt; permission（open/approval_required）；
	 * closed 项不在 map 中（调用方移除）。
	 */
	Map<String, String> permissionsFor(Long aiclawUid, long roomScope, Long callerUid, boolean isOwner,
			Collection<String> capabilityIds);

	/**
	 * #504: 主人阅读视图（默认规则 + 覆盖行 + 版本）。
	 */
	AiclawCommandPolicyResp readForOwner(Long operatorUid, Long aiclawUid, Long roomId);

	/**
	 * #504: 主人更新（仅主人；显式行 insert 或版本 CAS update；默认规则为代码常量不可写）。
	 */
	void updateForOwner(Long operatorUid, AiclawCommandPolicyUpdateReq req);
}
