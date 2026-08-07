package com.luohuo.flex.im.core.user.service;

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawActivateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawAuthConfirmReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCreateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawReportHostInfoReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq;
import com.luohuo.flex.im.domain.vo.req.CursorPageBaseReq;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawActivateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawConversationResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCreateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawFriendResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawListResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawPersonaResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenInfo;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;

import java.util.List;
import java.util.Map;

/**
 * AI助理管理服务
 */
public interface AiclawService {

	/**
	 * 创建AI助理（单事务：im_user + im_aiclaw + 好友关系 + Redis缓存）
	 */
	AiclawCreateResp create(AiclawCreateReq req, Long ownerUid);

	/**
	 * 获取owner的AI助理列表
	 */
	List<AiclawListResp> list(Long ownerUid);

	/**
	 * aiclaw 连接后上报 agent 类型，覆写 im_aiclaw.adapter_type（REQ-009 #83）。
	 *
	 * @param uid       被上报的 aiclaw uid（= caller 自身，防伪造）
	 * @param agentType 上报的类型；为 null/空白时 no-op（保留"最后已知类型"）
	 */
	void reportAgentType(Long uid, String agentType);

	/**
	 * aiclaw 上报主机信息（aichatoverview#193），按字段合并入 im_aiclaw.adapter_config JSON：
	 * blank 字段保留旧值、非 blank 覆写；三字段全 blank 时 no-op。
	 *
	 * @param uid 被上报的 aiclaw uid（= caller 自身，防伪造）；未知 uid 记 warn 优雅返回
	 * @param req hostname / ip / workspaceBase，均可空
	 */
	void reportHostInfo(Long uid, AiclawReportHostInfoReq req);

	/**
	 * 修改AI助理资料（name/avatar/description）
	 */
	void updateProfile(AiclawUpdateReq req, Long ownerUid);

	/**
	 * 停用（触发24h延迟注销）
	 */
	void deactivate(Long aiclawUid, Long ownerUid);

	/**
	 * 恢复（24h内）
	 */
	void restore(Long aiclawUid, Long ownerUid);

	/**
	 * 机器码变更授权确认
	 */
	void authConfirm(Long aiclawUid, AiclawAuthConfirmReq req, Long ownerUid);

	/**
	 * 激活（plugins 调用，无需登录态）
	 */
	AiclawActivateResp activate(AiclawActivateReq req);

	/**
	 * 获取激活 token（未激活时可反复查看）
	 */
	AiclawTokenResp getActivationToken(Long aiclawUid, Long ownerUid);

	/**
	 * 重新生成激活 token（生成新连接 token + 新激活 token，回到未激活状态）
	 */
	AiclawTokenResp refreshActivation(Long aiclawUid, Long ownerUid);

	/**
	 * 设置 aiclaw 对外人设（系统 prompt）
	 */
	void setPersona(Long aiclawUid, String publicPersona, Long ownerUid);

	/**
	 * 获取本人设（aiclaw 自作用域，#188 F2：连接/重连时拉取，是人设正确性的基础路径）
	 *
	 * @param uid caller 自身 uid（来自 aiclaw connectionToken 认证身份）
	 * @return 人设（publicPersona 为 null 时透传 null，表示未设置/已清空）
	 * @throws com.luohuo.basic.exception.BizException aiclaw 不存在时抛出
	 */
	AiclawPersonaResp getSelfPersona(Long uid);

	/**
	 * 获取 AI 助理 system prompt 模板（aiclaw 自作用域，REQ-018 #217）。
	 *
	 * <p>模板文本配置化存于 base_config（type='agent_prompt'，config_key 形如
	 * {@code agent.prompt.reply_contract}），plugins 启动时拉取。
	 * 三个模板 key 全部配置时返回原文（占位符 {@code {reply_command}} / {@code {displayName}} /
	 * {@code {uid}} / {@code {persona}} 原样下发，不做渲染）；任一 key 缺失/为空时抛
	 * {@code BizException}，错误信息指明缺失的 key。</p>
	 *
	 * @param uid caller 自身 uid（来自 aiclaw connectionToken 认证身份）
	 * @return LinkedHashMap：config_key → 模板原文，固定 3 个 key
	 * @throws com.luohuo.basic.exception.BizException aiclaw 不存在 / 任一模板 key 缺失或为空时抛出
	 */
	Map<String, String> getSelfPrompts(Long uid);

	/**
	 * 获取 aiclaw 的对话列表（按好友分组，含最后一条消息）
	 */
	List<AiclawConversationResp> getConversations(Long aiclawUid, Long ownerUid);

	/**
	 * 获取 aiclaw 与某好友的聊天记录（游标翻页）
	 */
	CursorPageBaseResp<ChatMessageResp> getConversationMessages(Long aiclawUid, Long friendUid, CursorPageBaseReq pageReq, Long ownerUid);

	/**
	 * 获取 aiclaw 的好友列表（不含 owner，含 relationDesc）
	 */
	List<AiclawFriendResp> getFriends(Long aiclawUid, Long ownerUid);

	/**
	 * 移除 aiclaw 的某个好友（级联删除）
	 */
	void removeFriend(Long aiclawUid, Long friendUid, Long ownerUid);

	/**
	 * 设置 aiclaw 好友的关系说明
	 */
	void setRelation(Long aiclawUid, Long friendUid, String relationDesc, Long ownerUid);

	/**
	 * 彻底注销已超过 24h 的停用 aiclaw（定时任务调用）
	 */
	void purgeExpiredDeactivated();

	/**
	 * 校验 aiclaw connectionToken 并回源重建 Redis 缓存（gateway 缓存缺失时调用，无需登录态）。
	 *
	 * <p>天然鉴权 = {@code BCrypt.checkpw(connectionToken, tokenHash)}：只有持有效 connectionToken
	 * 的请求才能通过。prefix 索引快速定位记录，bcrypt 抵御暴力破解。
	 *
	 * @param connectionToken 明文连接 token
	 * @return 身份信息；记录不存在 / bcrypt 不匹配 / 已停用 / 已删除(is_del 被 @TableLogic 自动过滤) /
	 *         authStatus≠1 时返回 null（并打 WARN 含 token prefix，绝不打完整 token）
	 */
	AiclawTokenInfo verifyAndCacheToken(String connectionToken);
}
