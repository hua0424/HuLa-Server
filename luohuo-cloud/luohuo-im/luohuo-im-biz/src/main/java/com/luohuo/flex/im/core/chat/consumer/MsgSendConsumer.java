package com.luohuo.flex.im.core.chat.consumer;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.OnlineService;
import com.luohuo.flex.common.cache.PassageMsgCacheKeyBuilder;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.response.msg.AudioCallMsgDTO;
import com.luohuo.flex.im.domain.entity.msg.MessageExtra;
import com.luohuo.flex.im.domain.vo.response.msg.VideoCallMsgDTO;
import com.luohuo.flex.im.domain.enums.MessageTypeEnum;
import com.luohuo.flex.im.domain.enums.RoomTypeEnum;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import com.luohuo.flex.im.core.chat.service.AiclawParticipant;
import com.luohuo.flex.im.core.chat.service.ChatService;
import com.luohuo.flex.im.core.chat.service.adapter.MessageAdapter;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.user.service.adapter.WsAdapter;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 发送消息更新房间收信箱，并同步给房间成员信箱
 * 加入 在途消息缓存，三秒没有回执则再次推送
 * @author 乾乾
 */
@Slf4j
@RocketMQMessageListener(consumerGroup = MqConstant.MSG_PUSH_OUTPUT_TOPIC_GROUP, topic = MqConstant.MSG_PUSH_OUTPUT_TOPIC, messageModel = MessageModel.CLUSTERING)
@Component
@AllArgsConstructor
public class MsgSendConsumer implements RocketMQListener<MsgSendMessageDTO> {

    private ChatService chatService;
    private MessageDao messageDao;
    private RoomCache roomCache;
    private RoomDao roomDao;
    private GroupMemberCache groupMemberCache;
    private RoomFriendDao roomFriendDao;
	private OnlineService onlineService;
    private PushService pushService;
	private CachePlusOps cachePlusOps;
	private AiclawParticipant aiclawParticipant;

    @Override
    public void onMessage(MsgSendMessageDTO dto) {
        // MQ worker threads are reused. A missing DTO identity must never inherit a prior tenant.
        com.luohuo.basic.context.ContextUtil.clearTenantContext();
        com.luohuo.basic.context.ContextUtil.remove();
        if (dto == null || dto.getTenantId() == null || dto.getTenantId() <= 0
                || dto.getUid() == null || dto.getUid() <= 0 || dto.getMsgId() == null || dto.getMsgId() <= 0) {
            throw new IllegalArgumentException("message notification lacks trusted tenant, actor or msgId");
        }
        // Restore before the Spring proxy opens its transaction; never trust caller thread state.
        com.luohuo.basic.context.ContextUtil.setTenantId(dto.getTenantId());
        com.luohuo.basic.context.ContextUtil.setUid(dto.getUid());
        try {
            pushService.withMessageTransaction(() -> routeMessage(dto));
        } finally {
            com.luohuo.basic.context.ContextUtil.clearTenantContext();
            com.luohuo.basic.context.ContextUtil.remove();
        }
    }

    private void routeMessage(MsgSendMessageDTO dto) {
        // Register before @SecureInvoke: seed in-flight state before any afterCommit MQ publication.
        List<Runnable> afterCommitTasks = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitTasks.forEach(task -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        // DB intent is committed: never suppress its publication callback.
                        log.error("消息提交后在途缓存/延迟推送失败: msgId={}", dto.getMsgId(), e);
                    }
                });
            }
        });
        Message message = messageDao.getById(dto.getMsgId());
        if (Objects.isNull(message)) {
            throw new IllegalStateException("message not readable before ACK: " + dto.getMsgId());
        }
        Room room = roomCache.get(message.getRoomId());
        // 1. 所有房间更新房间最新消息
        roomDao.refreshActiveTime(room.getId(), message.getId(), message.getCreateTime());
        roomCache.refresh(room.getId());

		List<Long> memberUidList = new ArrayList<>();
		if (Objects.equals(room.getType(), RoomTypeEnum.GROUP.getType())) {
			memberUidList = groupMemberCache.getMemberExceptUidList(room.getId());
			// REQ-009#84 (ADR-0002): 在收件人计算处剔除「未批准」的 aiclaw 成员，
			// 使其完全收不到该群消息（agent 永不触达 → 不会执行代码）。私聊不受此门控约束。
			memberUidList = aiclawParticipant.filterGroupRecipients(memberUidList, room.getId());
		} else if (Objects.equals(room.getType(), RoomTypeEnum.FRIEND.getType())) {
			// 单聊对象, 对单人推送
			RoomFriend roomFriend = roomFriendDao.getByRoomId(room.getId());
			memberUidList.add(roomFriend.getUid1());
			memberUidList.add(roomFriend.getUid2());
		}

		// 2. 过滤出在线的人员
		Set<Long> onlineUsersList = onlineService.getOnlineUsersList(memberUidList);

		// 3. 与在线人员交集并进行路由
		switch (MessageTypeEnum.of(message.getType())) {
			case AUDIO_CALL, VIDEO_CALL -> {
				Long uid = dto.getUid();

				// 3.1 给自己推送原始消息
				ChatMessageResp selfMsgResp = chatService.getMsgResp(message, null);
				// REQ-004 S5: 回填房间类型，供 aiclaw 插件区分群聊/单聊
				MessageAdapter.fillRoomType(selfMsgResp, room.getType());
				WsBaseResp<ChatMessageResp> selfResp = WsAdapter.buildMsgSend(selfMsgResp);
				pushService.sendReliablePushMsg(selfResp, List.of(uid), message.getId(), dto.getUid());
				schedulePassageMsgAfterCommit(message.getId(), selfResp, Set.of(uid), dto.getUid(), afterCommitTasks);

				// 3.2 修改消息发送者为通话创建者（用于其他人接收）
				Long originalFromUid = message.getFromUid();
				MessageExtra extra = message.getExtra();

				// 动态获取通话创建者
				Long creator = Optional.ofNullable(extra.getAudioCallMsgDTO())
						.map(AudioCallMsgDTO::getCreator)
						.orElseGet(() ->
								Optional.ofNullable(extra.getVideoCallMsgDTO())
										.map(VideoCallMsgDTO::getCreator)
										.orElse(originalFromUid)
						);
				message.setFromUid(creator);
                try {
				// 3.3 推送给其他成员
				onlineUsersList.remove(uid);
				List<Long> otherMembers = new ArrayList<>(onlineUsersList);

				ChatMessageResp othersMsgResp = chatService.getMsgResp(message, null);
				// REQ-004 S5: 回填房间类型
				MessageAdapter.fillRoomType(othersMsgResp, room.getType());
				WsBaseResp<ChatMessageResp> othersResp = WsAdapter.buildMsgSend(othersMsgResp);
				// 恢复原始发送者显示
				othersResp.getData().getFromUser().setUid(originalFromUid + "");
				// #24: uid 已改回 originalFromUid，userType 与 name 也需同步纠正回 originalFromUid 的，
				// 否则保留了通话创建者(creator)的 userType/name。复用 swap 前构建的 selfMsgResp
				// （其 fromUser 由 getMsgRespBatch 按 originalFromUid 填好）已算好的值，零额外查询。
				ChatMessageResp.UserInfo originalFromUser = selfMsgResp.getFromUser();
				if (originalFromUser != null) {
					MessageAdapter.fillFromUserType(othersResp.getData(), originalFromUser.getUserType());
					MessageAdapter.fillFromUserName(othersResp.getData(), originalFromUser.getName());
				}
				pushService.sendReliablePushMsg(othersResp, otherMembers, message.getId(), dto.getUid());
				schedulePassageMsgAfterCommit(message.getId(), othersResp, onlineUsersList, dto.getUid(), afterCommitTasks);
                } finally {
                    message.setFromUid(originalFromUid);
                }
			}
			default -> {
				// 常规消息处理
				ChatMessageResp chatMessageResp = chatService.getMsgResp(message, null);
				// REQ-004 S5: 回填房间类型（群聊/单聊），供 aiclaw 插件做 @ 触发判定
				MessageAdapter.fillRoomType(chatMessageResp, room.getType());
				// REQ-004 M2-2: 透传 aiclaw extra（thinkingId、autoReply 等）
				if (dto.getExtra() != null && chatMessageResp.getMessage() != null) {
					chatMessageResp.getMessage().setExtra(dto.getExtra());
				}
				WsBaseResp<ChatMessageResp> wsBaseResp = WsAdapter.buildMsgSend(chatMessageResp);

				// 单聊定向投递：若对端是 aiclaw，接缝返回带扩展字段的 payload → 分别推送
				Optional<AiclawParticipant.DirectChatDelivery> delivery =
						aiclawParticipant.resolveDirectChatDelivery(message, room, dto);
				if (delivery.isPresent()) {
					Long aiclawUid = delivery.get().getTargetUid();
					WsBaseResp<ChatMessageResp> aiclawWsResp = WsAdapter.buildMsgSend(delivery.get().getPayload());

					// 分别推送：aiclaw 收带扩展的，其他人收原版
					List<Long> normalUsers = new ArrayList<>(onlineUsersList);
					normalUsers.remove(aiclawUid);
					if (!normalUsers.isEmpty()) {
						pushService.sendReliablePushMsg(wsBaseResp, normalUsers, message.getId(), dto.getUid());
					}
					if (onlineUsersList.contains(aiclawUid)) {
						pushService.sendReliablePushMsg(aiclawWsResp, List.of(aiclawUid), message.getId(), dto.getUid());
					}
					schedulePassageMsgAfterCommit(message.getId(), wsBaseResp, new java.util.HashSet<>(normalUsers), dto.getUid(), afterCommitTasks);
                    if (onlineUsersList.contains(aiclawUid)) {
                        schedulePassageMsgAfterCommit(message.getId(), aiclawWsResp, Set.of(aiclawUid), dto.getUid(), afterCommitTasks);
                    }
					break;
				}

				// 常规场景：原有逻辑
				pushService.sendReliablePushMsg(wsBaseResp, new ArrayList<>(onlineUsersList), message.getId(), dto.getUid());
				schedulePassageMsgAfterCommit(message.getId(), wsBaseResp, onlineUsersList, dto.getUid(), afterCommitTasks);
			}
		}
    }

    /** Seed the legacy in-flight cache before committed @SecureInvoke intents publish to MQ. */
	private void schedulePassageMsgAfterCommit(Long messageId, WsBaseResp<?> wsBaseResp, Set<Long> memberUidList,
                                   Long cuid, List<Runnable> afterCommitTasks) {
		Set<Long> recipients = Set.copyOf(memberUidList);
		if (recipients.isEmpty()) return;
        afterCommitTasks.add(() -> {
            for (Long memberUid : recipients) {
                try {
                    cachePlusOps.sAdd(PassageMsgCacheKeyBuilder.build(memberUid), messageId);
                } catch (RuntimeException e) {
                    // A transient cache outage must not suppress the delayed retry for other recipients.
                    log.warn("在途缓存写入失败: msgId={}, uid={}", messageId, memberUid, e);
                }
            }
            pushService.sendPushMsgWithRetry(wsBaseResp, new ArrayList<>(recipients), messageId, cuid);
        });
	}

}
