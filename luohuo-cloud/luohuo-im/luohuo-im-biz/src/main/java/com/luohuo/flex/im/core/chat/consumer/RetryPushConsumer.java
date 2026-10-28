package com.luohuo.flex.im.core.chat.consumer;

import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.chat.service.AckRetryService;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 消息推送延迟二次推送专属消费者 [系统自己发起]
 * <p>
 * #482：不再按 uid 调普通 sendPushMsg 后当成功。未 ACK 的重发改为逐设备持久化意图：
 * 每个仍未回执的 (uid, deviceId) 独立落一条 secure_invoke_record，事务提交后独立异步重放；
 * 重放本身复查 ACK/在线/节点，失败进既有退避重试，不新建续期记录、不整批重投。
 *
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
	private final PushService pushService;
	private final AckRetryService ackRetryService;

	@Override
	public void onMessage(NodePushDTO message) {
		if (message == null) {
			return;
		}
		if (message.getTenantId() == null) {
			// 过渡消费路径：旧延迟消息不含可信租户，保持原 uid 级重发语义，绝不在工作线程猜租户。
			ackRetryService.acceptLegacyRetry(message);
			return;
		}
		// 复用消费者事务入口：意图保存与提交后调度都在真实事务内完成，保存失败整批回滚。
		pushService.withMessageTransaction(() -> ackRetryService.acceptAckRetry(message));
	}
}
