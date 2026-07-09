package com.luohuo.flex.im.core.chat.service;

import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomGroup;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * aiclaw 参与者接缝（aichatoverview#169）。
 *
 * <p>把散落在通用 IM 路径（{@code MsgSendConsumer} / {@code RoomAppServiceImpl}）里的 aiclaw 特判
 * 收拢成一个「深模块」：调用方只对本接口发问，通用 IM 代码里不再出现
 * {@code userType==AICLAW} / {@code aiclawOwnerCache} / {@code aiclawGroupConfigService} /
 * {@code AiclawExt} 等 aiclaw 专有引用。审批门控、owner 缓存、SETNX 去重等既有封装保持不变，
 * 本接缝仅委派、不重写。
 */
public interface AiclawParticipant {

	/**
	 * 该 uid 是否为 aiclaw（userType == {@code UserTypeEnum.AICLAW}(4)）。
	 * 语义等价于原 {@code MsgSendConsumer.findAiclawUid} 的单个判定：
	 * 读 {@code UserSummaryCache.get(uid).getUserType()}，null summary 视为非 aiclaw。
	 */
	boolean isAiclaw(Long uid);

	/**
	 * 群消息收件人过滤：委派既有 {@code AiclawGroupConfigService.filterUnapprovedAiclawRecipients}，
	 * 剔除「未批准」的 aiclaw 成员（ADR-0002 gate）。非 aiclaw 永远保留；空/Null 原样返回。
	 */
	List<Long> filterGroupRecipients(List<Long> memberUids, Long roomId);

	/**
	 * 单聊定向投递解析：仅 FRIEND 房间且对端是 aiclaw 时返回一份「定向投递」
	 * （目标 aiclaw uid + 带 {@code AiclawExt} 的富化 payload）；否则空。
	 * 富化方式与原 {@code MsgSendConsumer} 一致：全新 {@code getMsgResp(message,null)} +
	 * {@code fillRoomType} + extra 透传 + {@code AiclawExt}。
	 */
	Optional<DirectChatDelivery> resolveDirectChatDelivery(Message message, Room room, MsgSendMessageDTO dto);

	/**
	 * 被邀请人中的 aiclaw（userType==4）自动入群（pending，不走 UserApply），并按需给主人发待批准通知。
	 * 吸收原 {@code RoomAppServiceImpl.batchAddAiclawMembers}（含 #153 SETNX 去重 + #153 P0-1 补偿回滚）。
	 *
	 * @return 被自动入群的 aiclaw uid 集合（调用方据此把它们从普通邀请流程中剔除）。
	 */
	Set<Long> autoJoinInvitedAiclaws(RoomGroup roomGroup, List<User> resolvedInvitees, Long inviterUid);

	/**
	 * 成员被移出房间（踢人 / 退群）后的 aiclaw 收尾：对每个「是 aiclaw」的被移除 uid，
	 * 清入群待批准去重标记（{@code clearApproveNotified}），使其之后再被拉回可重新通知主人。
	 * 与原 {@code delMember}/{@code exitGroup} 一致，「是 aiclaw」的判定沿用
	 * {@code aiclawOwnerCache.getOwnerUid(uid) != null}。
	 */
	void onMembersRemoved(Long roomId, Collection<Long> removedUids);

	/**
	 * 单聊定向投递描述：{@code targetUid} = 目标 aiclaw 的 uid；{@code payload} = 带 {@code AiclawExt} 的富化响应。
	 */
	@Getter
	@AllArgsConstructor
	class DirectChatDelivery {
		private final Long targetUid;
		private final ChatMessageResp payload;
	}
}
