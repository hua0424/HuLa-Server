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
}
