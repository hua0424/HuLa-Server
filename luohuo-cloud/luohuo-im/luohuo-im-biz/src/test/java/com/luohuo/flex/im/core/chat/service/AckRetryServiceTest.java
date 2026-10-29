package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.jackson.JsonUtil;
import com.luohuo.basic.service.MQProducer;
import com.luohuo.basic.service.SecureInvokeService;
import com.luohuo.flex.im.core.chat.dao.ContactDao;
import com.luohuo.flex.im.core.chat.service.dto.AckRetryIntent;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.dto.NodePushDTO;
import com.luohuo.flex.router.NacosRouterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #482 ACK 未回执设备的持久化重放：逐设备落记录、租户不猜、重放只认原设备。
 *
 * <p>锁定的失败面：无租户即失败（不猜默认租户）、保存失败必须抛出（上层整批回滚、零发送）、
 * 重放前重检 ACK/在线、节点只按原 uid+deviceId 查、绝不扩展到同 uid 的其他设备、租户用完还原。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AckRetryServiceTest {

	private static final Long TENANT = 9L;
	private static final Long ACTOR = 100L;
	private static final Long RECIPIENT = 200L;
	private static final Long HASH_ID = 3000L;

	@Mock private SecureInvokeService secureInvokeService;
	@Mock private CachePlusOps cachePlusOps;
	@Mock private NacosRouterService routerService;
	@Mock private MQProducer mqProducer;
	@Mock private ContactDao contactDao;
	@Mock private PushService pushService;

	private AckRetryService service;

	@BeforeEach
	void setUp() {
		service = new AckRetryService(secureInvokeService, cachePlusOps, routerService, mqProducer, contactDao, pushService);
		ReflectionTestUtils.setField(service, "maxRetryCount", 3);
		ContextUtil.clearTenantContext();
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.initSynchronization();
		}
	}

	@AfterEach
	void tearDown() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		ContextUtil.clearTenantContext();
	}

	private NodePushDTO delivery(Long tenantId, Map<String, Long> devices) {
		WsBaseResp<Object> payload = new WsBaseResp<>();
		payload.setType("msg_send");
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("roomId", 10L);
		message.put("id", 1L);
		message.put("sendTime", 0L);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("message", message);
		payload.setData(data);
		NodePushDTO dto = new NodePushDTO(payload, devices, HASH_ID, ACTOR, tenantId);
		dto.setTenantId(tenantId);
		return dto;
	}

	private void stillInFlight() {
		when(cachePlusOps.zIsMember(anyString(), anyLong())).thenReturn(true);
		when(cachePlusOps.sIsMember(any(), eq(HASH_ID))).thenReturn(true);
	}

	@Test
	@DisplayName("无可信租户：拒绝登记，绝不在工作线程猜默认租户")
	void requiresTrustedTenant() {
		stillInFlight();
		NodePushDTO dto = delivery(null, Map.of("client-1", RECIPIENT));
		assertThrows(IllegalStateException.class, () -> service.acceptAckRetry(dto));
		verify(secureInvokeService, never()).save(any());
	}

	@Test
	@DisplayName("每个仍未回执的设备单独落一条记录，记录里是可直接反射重放的完整元组")
	void persistsPerDeviceRecordWithReflectionTuple() {
		stillInFlight();
		Map<String, Long> devices = new LinkedHashMap<>();
		devices.put("client-1", RECIPIENT);
		devices.put("client-2", RECIPIENT);

		service.acceptAckRetry(delivery(TENANT, devices));

		ArgumentCaptor<SecureInvokeRecord> captor = ArgumentCaptor.forClass(SecureInvokeRecord.class);
		verify(secureInvokeService, org.mockito.Mockito.times(2)).save(captor.capture());
		SecureInvokeRecord record = captor.getAllValues().get(0);
		assertEquals(AckRetryService.class.getName(), record.getSecureInvokeDTO().getClassName());
		assertEquals("replayAckTarget", record.getSecureInvokeDTO().getMethodName());
		assertEquals(JsonUtil.toJson(List.of(AckRetryIntent.class.getName())),
				record.getSecureInvokeDTO().getParameterTypes());
		// 单个 uid 的会话刷新在事务内去重
		verify(contactDao, org.mockito.Mockito.times(1)).refreshOrCreateActive(any(), any(), any(), any());
	}

	@Test
	@DisplayName("记录保存失败：异常向上抛出，让原消费整批回滚（零发送），不吞掉当成功")
	void saveFailurePropagates() {
		stillInFlight();
		doThrow(new IllegalStateException("SecureInvoke intent was not persisted"))
				.when(secureInvokeService).save(any());

		assertThrows(IllegalStateException.class,
				() -> service.acceptAckRetry(delivery(TENANT, Map.of("client-1", RECIPIENT))));
		verify(secureInvokeService, never()).doAsyncInvoke(any());
	}

	@Test
	@DisplayName("提交后调度被拒绝：记录保留、不抛回原始消费（不让整批重投）")
	void schedulingRejectionKeepsRecord() {
		stillInFlight();
		doThrow(new IllegalStateException("push-retry-scheduler already shutdown"))
				.when(secureInvokeService).doAsyncInvoke(any());

		service.acceptAckRetry(delivery(TENANT, Map.of("client-1", RECIPIENT)));

		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
		// 没有异常逃逸即证明不会触发 broker 整批重投
		verify(secureInvokeService).doAsyncInvoke(any());
	}

	@Test
	@DisplayName("重放：恢复租户、只按原 uid+设备查节点、单设备发送，用完还原租户")
	void replayUsesOriginalTenantAndSingleDevice() {
		stillInFlight();
		when(routerService.getDeviceNode(RECIPIENT, "client-2")).thenReturn("node-2");

		AckRetryIntent intent = new AckRetryIntent(TENANT, ACTOR, HASH_ID, RECIPIENT, "client-2");
		WsBaseResp<Object> payload = new WsBaseResp<>();
		payload.setType("msg_send");
		intent.setPayload(payload);

		service.replayAckTarget(intent);

		ArgumentCaptor<NodePushDTO> captor = ArgumentCaptor.forClass(NodePushDTO.class);
		verify(mqProducer).sendMsg(eq("websocket_pushnode-2"), captor.capture());
		NodePushDTO sent = captor.getValue();
		assertEquals(1, sent.getDeviceUserMap().size(), "只重放原设备，不得扩展到同 uid 的其他设备");
		assertEquals(RECIPIENT, sent.getDeviceUserMap().get("client-2"));
		assertEquals(TENANT, sent.getTenantId(), "重发必须与首次发送同租户");
		assertNull(ContextUtil.getTenantId(), "重放结束必须还原线程租户，不能污染复用线程");
	}

	@Test
	@DisplayName("重放：ACK 已回执或已离线时正常结束，不再发送")
	void replayStopsWhenAlreadyAcknowledged() {
		when(cachePlusOps.zIsMember(anyString(), anyLong())).thenReturn(true);
		when(cachePlusOps.sIsMember(any(), eq(HASH_ID))).thenReturn(false);

		AckRetryIntent intent = new AckRetryIntent(TENANT, ACTOR, HASH_ID, RECIPIENT, "client-1");
		intent.setPayload(new WsBaseResp<>());
		service.replayAckTarget(intent);

		verify(mqProducer, never()).sendMsg(anyString(), any());
	}

	@Test
	@DisplayName("重放：在线但查不到有效节点时抛出，交给既有记录退避重试（不新建续期记录）")
	void replayFailsWhenNodeMissing() {
		stillInFlight();
		when(routerService.getDeviceNode(RECIPIENT, "client-1")).thenReturn(null);

		AckRetryIntent intent = new AckRetryIntent(TENANT, ACTOR, HASH_ID, RECIPIENT, "client-1");
		intent.setPayload(new WsBaseResp<>());

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.replayAckTarget(intent));
		assertTrue(error.getMessage().contains("no valid node"));
		verify(mqProducer, never()).sendMsg(anyString(), any());
		verify(secureInvokeService, never()).save(any());
	}
}
