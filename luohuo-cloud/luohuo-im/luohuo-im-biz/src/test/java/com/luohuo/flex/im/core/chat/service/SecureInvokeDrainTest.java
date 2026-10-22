package com.luohuo.flex.im.core.chat.service;

import cn.hutool.extra.spring.SpringUtil;
import com.luohuo.basic.dao.SecureInvokeRecordDao;
import com.luohuo.basic.domain.dto.SecureInvokeDTO;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.service.SecureInvokeService;
import com.luohuo.basic.utils.ShutdownDrainClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #461 @SecureInvoke 重放纳入统一排空截止：截止后不新启重放（未执行），
 * 在途重放允许跑完（结果不确定由计数如实呈现）；记录保留待下次启动重放。
 */
class SecureInvokeDrainTest {

	/** 测试重放目标：记录执行时在途计数与调用次数。 */
	public static class TrackingPublisher {
		int attempts;
		long activeWhenRunning;

		public void publish() {
			attempts++;
			activeWhenRunning = ShutdownDrainClock.activeReplays();
		}
	}

	private SecureInvokeRecordDao dao;
	private final AtomicInteger dispatched = new AtomicInteger();
	private SecureInvokeService service;

	@BeforeEach
	void setUp() {
		ShutdownDrainClock.reset();
		dao = mock(SecureInvokeRecordDao.class);
		dispatched.set(0);
		// 仅计数不执行：用于断言"是否派发"，避免重放体真实执行
		service = new SecureInvokeService(dao, r -> dispatched.incrementAndGet());
	}

	@AfterEach
	void tearDown() {
		ShutdownDrainClock.reset();
	}

	private SecureInvokeRecord record(long id) {
		SecureInvokeDTO invocation = SecureInvokeDTO.builder()
				.className(TrackingPublisher.class.getName())
				.methodName("publish")
				.parameterTypes("[]")
				.args("[]")
				.build();
		SecureInvokeRecord record = SecureInvokeRecord.builder()
				.secureInvokeDTO(invocation)
				.maxRetryTimes(200)
				.build();
		record.setId(id);
		return record;
	}

	/** 排空期 retry() 定时重放不派发：跳过计数+1，记录保留（成功/失败更新均不触达）。 */
	@Test
	void retrySkipsNewReplaysDuringDrain() {
		when(dao.getWaitRetryRecords()).thenReturn(List.of(record(1L)));
		ShutdownDrainClock.beginDrain(30);

		service.retry();

		assertEquals(0, dispatched.get(), "排空期不应派发重放");
		assertEquals(1, ShutdownDrainClock.skippedReplays());
		verify(dao, never()).updateById(any(SecureInvokeRecord.class));
		verify(dao, never()).removeById(anyLong());
	}

	/** 排空期事务提交回调不派发重放：跳过计数+1。 */
	@Test
	void afterCommitSkipsReplayDuringDrain() {
		when(dao.save(any(SecureInvokeRecord.class))).thenReturn(true);
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		try {
			service.invoke(record(2L), true);
			assertFalse(TransactionSynchronizationManager.getSynchronizations().isEmpty(),
					"事务内应已注册 afterCommit 回调");

			ShutdownDrainClock.beginDrain(30);
			TransactionSynchronizationManager.getSynchronizations()
					.forEach(TransactionSynchronization::afterCommit);

			assertEquals(0, dispatched.get(), "排空期 afterCommit 不应派发重放");
			assertEquals(1, ShutdownDrainClock.skippedReplays());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

	/** 非排空期对照：afterCommit 正常派发执行，执行期在途计数可见，结束归零。 */
	@Test
	void afterCommitDispatchesWhenNotDraining() {
		when(dao.save(any(SecureInvokeRecord.class))).thenReturn(true);
		when(dao.removeById(2L)).thenReturn(true);
		AtomicInteger executed = new AtomicInteger();
		SecureInvokeService inlineService = new SecureInvokeService(dao, r -> {
			dispatched.incrementAndGet();
			r.run();
		});
		TrackingPublisher publisher = new TrackingPublisher();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		try (MockedStatic<SpringUtil> beans = mockStatic(SpringUtil.class)) {
			beans.when(() -> SpringUtil.getBean(TrackingPublisher.class)).thenReturn(publisher);
			inlineService.invoke(record(2L), true);
			TransactionSynchronizationManager.getSynchronizations()
					.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
		assertEquals(1, dispatched.get(), "非排空期应派发重放");
		assertEquals(1, publisher.attempts);
		assertEquals(1, publisher.activeWhenRunning, "在途重放计数不区分排空态，执行期应可见");
		assertEquals(0, ShutdownDrainClock.activeReplays(), "执行结束后在途应归零");
		assertEquals(0, ShutdownDrainClock.skippedReplays());
		verify(dao).removeById(2L);
	}

	/** 排空期在途重放允许跑完：doInvoke 不设执行门，执行中 activeReplays=1，结束归零。 */
	@Test
	void inFlightReplayRunsToCompletionDuringDrain() {
		when(dao.removeById(3L)).thenReturn(true);
		SecureInvokeService inlineService = new SecureInvokeService(dao, r -> {
			dispatched.incrementAndGet();
			r.run();
		});
		TrackingPublisher publisher = new TrackingPublisher();
		ShutdownDrainClock.beginDrain(30);
		try (MockedStatic<SpringUtil> beans = mockStatic(SpringUtil.class)) {
			beans.when(() -> SpringUtil.getBean(TrackingPublisher.class)).thenReturn(publisher);
			inlineService.doInvoke(record(3L));
		}
		assertEquals(1, publisher.attempts, "在途重放应允许执行完毕");
		assertEquals(1, publisher.activeWhenRunning, "执行中应计为在途（结果不确定）");
		assertEquals(0, ShutdownDrainClock.activeReplays(), "执行结束后在途应归零");
		assertEquals(0, ShutdownDrainClock.skippedReplays(), "doInvoke 直调不属跳过");
		verify(dao).removeById(3L);
	}

	/** 排空期 doAsyncInvoke 不派发新重放：跳过计数+1。 */
	@Test
	void doAsyncInvokeSkipsDuringDrain() {
		ShutdownDrainClock.beginDrain(30);

		service.doAsyncInvoke(record(4L));

		assertEquals(0, dispatched.get(), "排空期不应派发重放");
		assertEquals(1, ShutdownDrainClock.skippedReplays());
		assertTrue(ShutdownDrainClock.isDraining());
	}
}
