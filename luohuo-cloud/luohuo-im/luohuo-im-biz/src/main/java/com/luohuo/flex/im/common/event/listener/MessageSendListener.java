package com.luohuo.flex.im.common.event.listener;

import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.common.event.MessageSendEvent;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.MsgSendMessageDTO;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.enums.HotFlagEnum;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.model.entity.dto.AiNodeRequestDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/**
 * 消息发送监听器
 *
 * @author zhongzb create on 2022/08/26
 */
@Slf4j
@Component
public class MessageSendListener {
    @Resource
    private MessageDao messageDao;
//    @Resource
//    private IChatAIService openAIService;
    @Resource
    private RoomCache roomCache;
	@Resource
	private RoomFriendDao roomFriendDao;
	@Resource
	private UserDao userDao;
    @Resource
    private MQProducer mqProducer;
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, classes = MessageSendEvent.class, fallbackExecution = true)
    public void messageRoute(MessageSendEvent event) {
        Long msgId = event.getChatMsgSendDto().getMsgId();
		Long senderUid = event.getChatMsgSendDto().getUid();
		Long tenantId = ContextUtil.getTenantId();
		if (tenantId == null) {
			Message dbMessage = messageDao.getById(msgId);
			if (dbMessage != null) {
				tenantId = dbMessage.getTenantId();
			}
		}
        mqProducer.sendSecureMsg(MqConstant.MSG_PUSH_OUTPUT_TOPIC, new MsgSendMessageDTO(msgId, senderUid, tenantId), msgId);
		routeToAiNode(msgId, senderUid, tenantId);
    }

    @TransactionalEventListener(classes = MessageSendEvent.class, fallbackExecution = true)
    public void handlerMsg(@NotNull MessageSendEvent event) {
        Message message = messageDao.getById(event.getChatMsgSendDto().getMsgId());
        Room room = roomCache.get(message.getRoomId());
        if (isHotRoom(room)) {
//            openAIService.chat(message);
        }
    }

	private void routeToAiNode(Long msgId, Long senderUid, Long tenantId) {
		Message message = messageDao.getById(msgId);
		if (message == null) {
			return;
		}
		Room room = roomCache.get(message.getRoomId());
		if (room == null || !room.isRoomFriend()) {
			return;
		}
		RoomFriend roomFriend = roomFriendDao.getByRoomId(room.getId());
		if (roomFriend == null) {
			return;
		}
		Long targetUid = resolveTargetUid(roomFriend, senderUid);
		if (targetUid == null) {
			return;
		}
		User targetUser = userDao.getById(targetUid);
		if (targetUser == null || !UserTypeEnum.BOT.getValue().equals(targetUser.getUserType())) {
			return;
		}
		if (StrUtil.isBlank(message.getContent())) {
			return;
		}

		String originalText = message.getContent();
		String finalText = originalText;

		AiNodeRequestDTO dto = new AiNodeRequestDTO();
		dto.setTenantId(tenantId);
		dto.setRequestId("req_" + msgId);
		dto.setMsgId(msgId);
		dto.setRoomId(message.getRoomId());
		dto.setFromUserId(senderUid);
		dto.setAiUserId(targetUid);
		dto.setContent(finalText);
		dto.setOriginalText(originalText);
		dto.setFinalText(finalText);
		dto.setTimestamp(System.currentTimeMillis());

		mqProducer.sendSecureMsg(MqConstant.AI_NODE_REQUEST_TOPIC, dto, msgId);
		log.info("AI请求已投递: requestId={}, msgId={}, fromUid={}, aiUid={}, roomId={}, originalText={}, finalText={}",
				dto.getRequestId(), msgId, senderUid, targetUid, message.getRoomId(), originalText, finalText);
	}

	private Long resolveTargetUid(RoomFriend roomFriend, Long senderUid) {
		if (senderUid.equals(roomFriend.getUid1())) {
			return roomFriend.getUid2();
		}
		if (senderUid.equals(roomFriend.getUid2())) {
			return roomFriend.getUid1();
		}
		return null;
	}

    public boolean isHotRoom(Room room) {
        return Objects.equals(HotFlagEnum.YES.getType(), room.getHotFlag());
    }

    /**
     * 给用户微信推送艾特好友的消息通知
     * （这个没开启，微信不让推）
     */
    @TransactionalEventListener(classes = MessageSendEvent.class, fallbackExecution = true)
    public void publishChatToWechat(@NotNull MessageSendEvent event) {
//        Message message = messageDao.getById(event.getChatMsgSendDto().getMsgId());
//        if (Objects.nonNull(message.getExtra().getAtUidList())) {
//            weChatMsgOperationService.publishChatMsgToWeChatUser(message.getFromUid(), message.getExtra().getAtUidList(),
//                    message.getContent());
//        }
    }
}
