package com.luohuo.flex.im.core.user.service;

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawActivateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawAuthConfirmReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawCreateReq;
import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawActivateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawCreateResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawListResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawTokenResp;

import java.util.List;

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
}
