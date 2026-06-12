package com.luohuo.flex.im.core.chat.service;

/**
 * aichatoverview#3: aiclaw 房间成员校验服务。
 * 提取为独立 Service，供 REST（ChatServiceImpl）和 WS（ThinkingController）两入口统一调用。
 * 数据源统一为 GroupMemberCache + RoomFriendDao（与全仓一致）。
 */
public interface AiclawRoomMembershipService {

	/**
	 * 校验指定 aiclaw 是否为目标房间的成员。
	 * 仅当发送者为 aiclaw 类型时执行校验；普通用户直接放行。
	 * 群聊通过 GroupMemberCache，私聊通过 RoomFriendDao。
	 * 未知房间类型对 aiclaw 发送者明确拒绝（白名单思维）。
	 *
	 * @param aiclawUid 待校验的 aiclaw uid
	 * @param roomId    目标房间 id
	 * @throws com.luohuo.basic.exception.BizException 非成员 / 数据异常 / 未知房间类型
	 */
	void checkMembership(Long aiclawUid, Long roomId);
}
