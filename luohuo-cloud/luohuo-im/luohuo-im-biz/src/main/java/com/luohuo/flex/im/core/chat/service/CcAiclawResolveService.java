package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.vo.resp.aiclaw.CcAiclawResolveResp;

/**
 * REQ-010 S9: 解析「某房间的 CC（claude-code）aiclaw 成员 + owner 鉴权」。
 *
 * <p>由 im 侧 {@code CcLaunchController} <b>进程内</b>调用（im 拥有 room/aiclaw/owner 数据），
 * 在转 ws ccBind 调起 node 之前做归属/鉴权判定 —— 各司其职、就近其数据。CC aiclaw 的判定依据
 * {@code im_aiclaw.adapter_type = "cc"}（REQ-009 reportAgentType 落库；node 侧 CcDriver 的 tool 名为 {@code cc}）。</p>
 *
 * @author developer
 */
public interface CcAiclawResolveService {

	/**
	 * 找到目标房间的 CC aiclaw 成员，并校验 requester 为其 owner。
	 *
	 * @param roomId       目标房间
	 * @param requesterUid 当前登录用户（必须是该 CC aiclaw 的 owner）
	 * @param uid          可选：当房间存在多个 CC aiclaw 时，用于消歧（指定要绑定哪一个）
	 * @return 解析结果（ok=true 带 aiclawUid/roomType/counterpartUid；ok=false 带 errorCode）
	 */
	CcAiclawResolveResp resolveCcAiclawForRoom(Long roomId, Long requesterUid, Long uid);
}
