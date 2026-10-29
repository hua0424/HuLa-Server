package com.luohuo.flex.im.domain.vo.resp.room;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * aichatoverview#484 (T3)：群成员/权限「当前事实」的窄读响应。
 *
 * <p>合同要点（设计 v1 §3.1）：</p>
 * <ul>
 *   <li>{@code outcome=MEMBER} 时才是可用快照；{@code outcome=NOT_ACCESSIBLE} 与
 *       「群不存在 / 已删除 / 调用者不在群」同形，不额外披露存在性。</li>
 *   <li>本快照只证明「这一次读取」的事实，不是执行授权凭据；服务端每次业务操作仍需自行准入。</li>
 *   <li>{@code requestedMembers} 只覆盖本次请求的 UID，未请求的 UID 是 unknown 而不是「不存在」；
 *       用户摘要缺失（profile 缺字段）不等于成员资格不存在——{@code exists} 只来自 im_group_member。</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupUseStateResp implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String OUTCOME_MEMBER = "MEMBER";
	public static final String OUTCOME_NOT_ACCESSIBLE = "NOT_ACCESSIBLE";

	@Schema(description = "读取结论：MEMBER=可用当前事实；NOT_ACCESSIBLE=不在群/群不存在/已删除（同形）")
	private String outcome;

	@Schema(description = "房间id")
	private Long roomId;

	@Schema(description = "调用者 uid（来自认证上下文）")
	private Long selfUid;

	@Schema(description = "调用者在该群的角色 1-群主 2-管理员 3-普通成员 4-已被移出")
	private Integer selfRoleId;

	@Schema(description = "群成员总数（DB 口径）")
	private Long memberCount;

	@Schema(description = "群名")
	private String groupName;

	@Schema(description = "群号（account）")
	private String account;

	@Schema(description = "调用者在该群的备注/群昵称")
	private String remark;

	@Schema(description = "调用者在该群的群昵称")
	private String myName;

	@Schema(description = "是否允许扫码进群")
	private Boolean allowScanEnter;

	@Schema(description = "本次请求 UID 的成员资格投影（只覆盖请求集合）")
	private List<MemberProjection> requestedMembers;

	@Schema(description = "覆盖信息")
	private Coverage coverage;

	/** 单 UID 的成员资格投影：exists 只来自成员表，展示字段缺失不改写 exists。 */
	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class MemberProjection implements Serializable {

		private static final long serialVersionUID = 1L;

		@Schema(description = "用户uid")
		private Long uid;

		@Schema(description = "是否为该群成员（仅事实表口径）")
		private Boolean exists;

		@Schema(description = "群角色 1-群主 2-管理员 3-普通成员 4-已被移出；非成员为 null")
		private Integer roleId;

		@Schema(description = "展示用昵称，摘要缺失时为 null（不代表成员不存在）")
		private String name;

		@Schema(description = "展示用账号，摘要缺失时为 null")
		private String account;
	}

	/** 覆盖信息：请求集合是否被完整处理，页外 UID 一律 unknown。 */
	@Data
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Coverage implements Serializable {

		private static final long serialVersionUID = 1L;

		@Schema(description = "本次请求的有效 UID 数（去重后）")
		private Integer requested;

		@Schema(description = "本次返回投影的 UID 数（等于 requested 表示完整覆盖）")
		private Integer covered;

		@Schema(description = "单次请求 UID 硬上限")
		private Integer limit;

		@Schema(description = "是否有 UID 因超限被丢弃（true 时调用方不得把缺失当不存在）")
		private Boolean truncated;
	}
}
