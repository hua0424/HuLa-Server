package com.luohuo.flex.im.core.chat.consumer;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.common.cache.PassageMsgCacheKeyBuilder;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息推送延迟二次推送专属消费者 [系统自己发起]
 * 收到重试消息之后判断路由的uid是否传递的消息是否还在途中，再途的话再次发送
 * @author 乾乾
 */
@Slf4j
@RocketMQMessageListener(
		topic = MqConstant.PUSH_DELAY_TOPIC,
		consumerGroup = MqConstant.PUSH_DELAY_GROUP,
		messageModel = MessageModel.CLUSTERING,
		maxReconsumeTimes = 3
)
@Component
@RequiredArgsConstructor
public class RetryPushConsumer implements RocketMQListener<NodePushDTO> {
	private final CachePlusOps cachePlusOps;
	private final PushService pushService;
	private final ContactDao contactDao;

    @Override
	public void onMessage(NodePushDTO message) {
		Map<String, Long> deviceUserMap = message.getDeviceUserMap();
		String onlineUsersKey = PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey();

		deviceUserMap.values().forEach(uid -> {
			// M4-2: 死会话保护 — 用户已下线则直接清理 in-flight，不再浪费重试
			Boolean isOnline = cachePlusOps.zIsMember(onlineUsersKey, uid);
			if (!Boolean.TRUE.equals(isOnline)) {
				log.info("用户已下线，跳过重试并清理 in-flight: uid={}, hashId={}", uid, message.getHashId());
				cachePlusOps.sRem(PassageMsgCacheKeyBuilder.build(uid), message.getHashId());
				return;
			}

			Boolean exist = cachePlusOps.sIsMember(PassageMsgCacheKeyBuilder.build(uid), message.getHashId());

			if (exist) {
				log.info("ack失败重新发送消息: {}", message);
				pushService.sendPushMsg(message.getWsBaseMsg(), Arrays.asList(uid), message.getUid());

				// 直接更新 receiver(未 ACK 需重推的目标用户)的会话最后一条消息
				LinkedHashMap<String, Object> dataMap = (LinkedHashMap<String, Object>) message.getWsBaseMsg().getData();
				LinkedHashMap<String, Object> msg =  (LinkedHashMap<String, Object>) dataMap.get("message");
				// ISS-007: 取 receiver uid,而非 sender(fromUser.uid)
				contactDao.refreshOrCreateActive(msg.get("roomId"), Arrays.asList(uid), msg.get("id"), msg.get("sendTime"));
			}
		});
	}
}
