package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;

import java.util.List;

/**
 * aiclaw 群聊配置服务
 */
public interface AiclawGroupConfigService {

	/**
	 * 查询 aiclaw 在指定群的配置
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 * @param uid       当前登录用户 uid（用于权限校验）
	 * @return 群配置
	 */
	AiclawGroupConfigResp getConfig(Long aiclawUid, Long roomId, Long uid);

	/**
	 * 列出「该 aiclaw 自己」已存在的全部群配置（用于插件启动/重连预热）。
	 *
	 * <p>aichatoverview#26：aiclawUid 必须来自认证身份（{@code ContextUtil.getUid()}），
	 * 调用方不得传入任意 aiclawUid——否则会构成 IDOR（一个 aiclaw 拉取另一个 aiclaw 的配置）。
	 * 仅返回已落库的配置行，不为未配置的群补默认值（预热只需已有配置，未配置的群由插件端自有默认逻辑兜底）。
	 * 无配置时返回空 List，不抛异常。
	 *
	 * @param aiclawUid 认证身份的 aiclaw uid
	 * @return 该 aiclaw 全部已存在的群配置（可能为空）
	 */
	List<AiclawGroupConfigResp> listSelfConfigs(Long aiclawUid);

	/**
	 * 更新 aiclaw 群配置（仅 aiclaw 主人可更新）
	 *
	 * @param request 更新请求
	 * @param uid     当前登录用户 uid
	 */
	void updateConfig(AiclawGroupConfigUpdateReq request, Long uid);

	/**
	 * REQ-009#84: 判断某 aiclaw 是否已在指定群被批准响应。
	 *
	 * <p>合同（#82）：approved == 1 为已批准；null 或 0（无记录/默认）为未批准。
	 * 优先读 Redis 缓存（{@code buildConfigCacheKey}），缓存未命中回落 DB；
	 * 无记录视为未批准（沉默）。
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 * @return 已批准返回 true，否则 false
	 */
	boolean isApproved(Long aiclawUid, Long roomId);

	/**
	 * REQ-009#84: 从群消息收件人列表中剔除「未批准」的 aiclaw 成员。
	 *
	 * <p>ADR-0002 gate 落点：非 aiclaw 成员永不过滤；aiclaw 成员仅当未批准时剔除，
	 * 已批准 aiclaw 保留。空/Null 列表原样返回。
	 *
	 * @param memberUids 群消息收件人 uid 列表
	 * @param roomId     群聊 room_id
	 * @return 剔除未批准 aiclaw 后的新列表
	 */
	List<Long> filterUnapprovedAiclawRecipients(List<Long> memberUids, Long roomId);

	/**
	 * #153: 标记「已给主人发过该 (aiclaw, room) 的入群待批准通知」；返回 true=首次标记（应发通知），
	 * false=已有未决通知（应跳过）。Redis SETNX + TTL。
	 *
	 * <p>SETNX 是原子操作 → 天然处理并发双邀请竞态：并发 N 个邀请只有一个能取到 true，
	 * 其余取 false，从而每个 (aiclaw, room) 未决期内只给主人发一条待批准通知。
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 * @return 首次标记返回 true（应发通知），已有未决标记返回 false（应跳过）
	 */
	boolean tryMarkApproveNotified(Long aiclawUid, Long roomId);

	/**
	 * #153: 清除入群待批准去重标记；主人做出批准/拒绝决定后调用，使后续再次邀请可再通知。
	 *
	 * @param aiclawUid aiclaw 的 uid
	 * @param roomId    群聊 room_id
	 */
	void clearApproveNotified(Long aiclawUid, Long roomId);
}
