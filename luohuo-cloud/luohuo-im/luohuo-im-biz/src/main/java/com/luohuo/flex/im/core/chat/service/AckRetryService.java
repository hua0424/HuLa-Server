package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.domain.dto.SecureInvokeDTO;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.service.SecureInvokeService;
import com.luohuo.flex.common.cache.PassageMsgCacheKeyBuilder;
import com.luohuo.flex.common.cache.PresenceCacheKeyBuilder;
import com.luohuo.flex.common.constant.MqConstant;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.service.dto.AckRetryIntent;
import com.luohuo.flex.im.core.user.service.dto.PushSubmission;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.router.NacosRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #482 ACK 未回执设备的持久化重放。
 * <p>
 * 与普通推送执行器契约无关：这里不接受「任务已提交」当作送达，也不把首次发送的 Future 升级成最终投递证明。
 * 每个仍未 ACK 的 <b>设备</b> 单独落一条既有 {@code secure_invoke_record}，事务提交后独立异步重放；
 * 重放前重新校验 ACK 与在线状态，节点只按「原 uid + 原 deviceId」查询，绝不扩展到同一 uid 的其他设备。
 * <p>
 * 持久化契约：记录里保存的是 {@code className + methodName + parameterTypes + args}，
 * 因此 {@link AckRetryIntent} 的类名/包名/字段类型与 {@link #replayAckTarget(AckRetryIntent)} 的签名
 * 都属于兼容边界，改名前必须先保证旧记录仍可反序列化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AckRetryService {

	/** 反射重放入口名，写入 secure_invoke_record 后不可随意改名（见类注释）。 */
	public static final String REPLAY_METHOD = "replayAckTarget";

	private final SecureInvokeService secureInvokeService;
	private final CachePlusOps cachePlusOps;
	private final NacosRouterService routerService;
	private final MQProducer mqProducer;
	private final ContactDao contactDao;
	private final PushService pushService;

	/** 与 ACK/本地重试同一上限，绝不借用可靠首发的 200 次。 */
	@Value("${luohuo.thread.retry.max-count:3}")
	private Integer maxRetryCount;

	/**
	 * 在消费者同步事务入口登记逐设备重放：保存失败即抛出让整批回滚（零发送），
	 * 提交后再各自独立异步调度，单条调度被拒绝不抛回原始消费（记录已持久化，由既有扫描恢复）。
	 */
	public void acceptAckRetry(NodePushDTO delivery) {
		if (delivery == null || delivery.getHashId() == null) {
			log.warn("ACK 重放登记缺少原消息标识，跳过: {}", delivery);
			return;
		}
		if (delivery.getDeviceUserMap() == null || delivery.getDeviceUserMap().isEmpty()) {
			log.warn("ACK 重放登记缺少设备目标，跳过: hashId={}", delivery.getHashId());
			return;
		}
		WsBaseResp<?> payload = delivery.getWsBaseMsg();
		Long tenantId = delivery.getTenantId() != null ? delivery.getTenantId() : ContextUtil.getTenantId();
		if (tenantId == null) {
			// 旧延迟消息不带租户：绝不在工作线程猜默认租户，交给调用方保持过渡消费路径。
			throw new IllegalStateException("ACK retry requires a trusted tenant: hashId=" + delivery.getHashId());
		}

		// 同一 uid 的会话刷新在事务内去重，避免一用户多设备重复刷新。
		Set<Long> refreshedUids = new LinkedHashSet<>();
		for (Map.Entry<String, Long> device : delivery.getDeviceUserMap().entrySet()) {
			String deviceId = device.getKey();
			Long uid = device.getValue();
			if (uid == null || deviceId == null || deviceId.isBlank()) {
				log.warn("ACK 重放登记目标非法，跳过: hashId={}, deviceId={}, uid={}", delivery.getHashId(), deviceId, uid);
				continue;
			}
			if (!stillNeedsReplay(uid, delivery.getHashId())) {
				continue;
			}
			AckRetryIntent intent = new AckRetryIntent(tenantId, delivery.getUid(), delivery.getHashId(), uid, deviceId);
			intent.setPayload(payload);
			persist(intent);
			if (refreshedUids.add(uid)) {
				refreshContact(payload, uid);
			}
		}
	}

	/**
	 * 反射重放入口：恢复租户 → 复查 ACK/在线 → 按原 uid+设备查询当前节点 → 单设备重发。
	 * <p>
	 * 任一条件不再满足即正常结束（记录可删）；查不到节点等失败抛出，交给既有记录退避重试与状态统计，
	 * 不新建续期记录、不回落普通 sendAsync。
	 */
	public void replayAckTarget(AckRetryIntent intent) {
		if (intent == null || intent.getRecipientUid() == null || intent.getHashId() == null
				|| intent.getDeviceId() == null || intent.getDeviceId().isBlank()) {
			log.warn("ACK 重放意图不完整，放弃: {}", intent);
			return;
		}
		Long previousTenant = ContextUtil.getTenantId();
		try {
			if (intent.getTenantId() != null) {
				ContextUtil.setTenantId(intent.getTenantId());
			}
			if (!stillNeedsReplay(intent.getRecipientUid(), intent.getHashId())) {
				log.info("ACK 已回执或已离线，重放结束: uid={}, deviceId={}, hashId={}",
						intent.getRecipientUid(), intent.getDeviceId(), intent.getHashId());
				return;
			}
			String nodeId = routerService.getDeviceNode(intent.getRecipientUid(), intent.getDeviceId());
			if (nodeId == null || nodeId.isBlank()) {
				// 在线但没有有效节点：保留有界重试，等设备重新上线或路由恢复。
				throw new IllegalStateException("ACK replay has no valid node for device: uid="
						+ intent.getRecipientUid() + ", deviceId=" + intent.getDeviceId());
			}
			Map<String, Long> singleDevice = new LinkedHashMap<>();
			singleDevice.put(intent.getDeviceId(), intent.getRecipientUid());
			NodePushDTO dto = new NodePushDTO(intent.getPayload(), singleDevice, intent.getHashId(),
					intent.getActorUid(), intent.getTenantId());
			mqProducer.sendMsg(MqConstant.PUSH_TOPIC + nodeId, dto);
			log.info("ACK 重放已提交: uid={}, deviceId={}, hashId={}, nodeId={}",
					intent.getRecipientUid(), intent.getDeviceId(), intent.getHashId(), nodeId);
		} finally {
			// 必须还原本入口设置的租户上下文，避免污染复用线程。
			if (previousTenant == null) {
				ContextUtil.clearTenantContext();
			} else {
				ContextUtil.setTenantId(previousTenant);
			}
		}
	}

	/**
	 * 过渡消费路径：旧延迟消息体没有可信租户，无法为逐设备意图恢复租户，因此保持原有
	 * uid 级重发语义，但不再把结果丢掉——未全部接收按事实记录，等旧积压排空后再切严格消费路径。
	 * <p>
	 * 这条路径只做「旧消息还能被消费」的兼容，不代表新语义已生效。
	 */
	public void acceptLegacyRetry(NodePushDTO delivery) {
		if (delivery == null || delivery.getDeviceUserMap() == null || delivery.getDeviceUserMap().isEmpty()) {
			return;
		}
		Set<Long> refreshedUids = new LinkedHashSet<>();
		for (Map.Entry<String, Long> device : delivery.getDeviceUserMap().entrySet()) {
			Long uid = device.getValue();
			if (uid == null) {
				continue;
			}
			if (!stillNeedsReplay(uid, delivery.getHashId())) {
				continue;
			}
			PushSubmission submission = pushService.submitPushMsg(delivery.getWsBaseMsg(), List.of(uid), delivery.getUid());
			if (submission != null && !submission.isFullyAdmitted()) {
				log.info("旧延迟消息重发未全部接收: uid={}, hashId={}, admission={}, routeStatus={}, 未接收={}",
						uid, delivery.getHashId(), submission.getAdmission(), submission.getRouteStatus(),
						submission.getUnreceivedUids());
			}
			if (refreshedUids.add(uid)) {
				refreshContact(delivery.getWsBaseMsg(), uid);
			}
		}
	}

	/** 仍有未回执在途记录且用户在线，才值得重放。 */
	private boolean stillNeedsReplay(Long uid, Long hashId) {
		Boolean online = cachePlusOps.zIsMember(PresenceCacheKeyBuilder.globalOnlineUsersKey().getKey(), uid);
		if (!Boolean.TRUE.equals(online)) {
			log.info("用户已下线，ACK 重放结束并清理在途: uid={}, hashId={}", uid, hashId);
			cachePlusOps.sRem(PassageMsgCacheKeyBuilder.build(uid), hashId);
			return false;
		}
		return Boolean.TRUE.equals(cachePlusOps.sIsMember(PassageMsgCacheKeyBuilder.build(uid), hashId));
	}

	private void persist(AckRetryIntent intent) {
		SecureInvokeDTO dto = SecureInvokeDTO.builder()
				.className(AckRetryService.class.getName())
				.methodName(REPLAY_METHOD)
				.parameterTypes(JsonUtil.toJson(List.of(AckRetryIntent.class.getName())))
				.args(JsonUtil.toJson(List.of(intent)))
				.build();
		SecureInvokeRecord record = SecureInvokeRecord.builder()
				.secureInvokeDTO(dto)
				.maxRetryTimes(maxRetryCount)
				.build();
		// 保存失败（false 或异常）直接抛出 → 整个消费事务回滚，零发送。
		secureInvokeService.save(record);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					secureInvokeService.doAsyncInvoke(record);
				} catch (RuntimeException e) {
					// 单条调度被拒绝：记录已持久化，交给既有扫描重放，绝不抛回原始消费造成整批重投。
					log.warn("ACK 重放调度被拒绝，记录保留待扫描重放: id={}, reason={}", record.getId(), e.getMessage());
				}
			}
		});
	}

	/** 未 ACK 目标的会话刷新，保持原语义（取 receiver uid，而非 sender），失败不影响业务。 */
	private void refreshContact(WsBaseResp<?> payload, Long uid) {
		try {
			if (payload == null || !(payload.getData() instanceof Map<?, ?> dataMap)) {
				return;
			}
			Object message = dataMap.get("message");
			if (!(message instanceof Map<?, ?> msg)) {
				return;
			}
			contactDao.refreshOrCreateActive(msg.get("roomId"), List.of(uid), msg.get("id"), msg.get("sendTime"));
		} catch (RuntimeException e) {
			log.warn("ACK 重放会话刷新失败（不影响重放）: uid={}, reason={}", uid, e.getMessage());
		}
	}
}
