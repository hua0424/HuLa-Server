package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.vo.req.aiclaw.AiclawGroupConfigUpdateReq;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;

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
	 * 更新 aiclaw 群配置（仅 aiclaw 主人可更新）
	 *
	 * @param request 更新请求
	 * @param uid     当前登录用户 uid
	 */
	void updateConfig(AiclawGroupConfigUpdateReq request, Long uid);
}
