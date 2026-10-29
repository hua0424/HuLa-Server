package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.dao.SecureInvokeRecordDao;
import com.luohuo.basic.domain.dto.SecureInvokeDTO;
import com.luohuo.basic.domain.entity.SecureInvokeRecord;
import com.luohuo.basic.service.SecureInvokeService;
import com.luohuo.basic.utils.ShutdownDrainClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #486 T5：重放执行资源拒绝（{@link RejectedExecutionException}）必须作为独立结果表达。
 *
 * <p>边界：调度拒绝既不是一次网络尝试（不推进 retryTimes、不计入重试预算），
 * 也不能正常返回让调用方以为本轮已执行完——记录必须保持待重试并被下次扫描重新拿到。
 * 与 #461 的排空跳过（{@code replaySkipped}）分开计数，避免把调度拒绝混进重试量。
 */
class SecureInvokeAdmissionDeferralTest {

	private SecureInvokeRecordDao dao;
	private AtomicInteger dispatched;

	@BeforeEach
	void setUp() {
		ShutdownDrainClock.reset();
		dao = mock(SecureInvokeRecordDao.class);
		dispatched = new AtomicInteger();
	}

	@AfterEach
	void tearDown() {
		ShutdownDrainClock.reset();
	}

	private SecureInvokeService serviceWith(Executor executor) {
		return new SecureInvokeService(dao, executor);
	}

	private SecureInvokeRecord record(long id, int maxRetryTimes) {
		SecureInvokeRecord record = SecureInvokeRecord.builder()
				.secureInvokeDTO(SecureInvokeDTO.builder()
						.className(SecureInvokeDrainTest.TrackingPublisher.class.getName())
						.methodName("publish")
						.parameterTypes("[]")
						.args("[]")
						.build())
				.maxRetryTimes(maxRetryTimes)
				.build();
		record.setId(id);
		record.setRetryTimes(0);
		return record;
	}

	/** 执行资源已满：记录不被删除、不被改状态、不计一次尝试，只记一次 deferral。 */
	@Test
	void rejectedExecutionKeepsTheRecordAndCountsAsDeferralNotAttempt() {
		SecureInvokeService service = serviceWith(r -> {
			throw new RejectedExecutionException("queue full");
		});

		service.doAsyncInvoke(record(5L, 3));

		assertEquals(0, dispatched.get(), "被拒绝时不得执行重放体");
		assertEquals(0, ShutdownDrainClock.skippedReplays(), "容量拒绝不是排空跳过，不能混计");
		assertEquals(0, ShutdownDrainClock.activeReplays(), "未执行就不得计在途（否则会伪报结果不确定）");
		assertEquals(1, service.admissionDeferredCount());
		verify(dao, never()).removeById(anyLong());
		verify(dao, never()).updateById(any(SecureInvokeRecord.class));
	}

	/** retry() 扫描期间被拒绝：记录留给下一次扫描，扫描本身不抛回调度器。 */
	@Test
	void scanIsNotBrokenByADeferralAndLeavesTheRecordForTheNextScan() {
		when(dao.getWaitRetryRecords()).thenReturn(List.of(record(6L, 3), record(7L, 3)));
		AtomicInteger accepts = new AtomicInteger();
		SecureInvokeService service = serviceWith(r -> {
			if (accepts.getAndIncrement() == 0) {
				throw new RejectedExecutionException("queue full");
			}
			dispatched.incrementAndGet();
		});

		service.retry();

		assertEquals(1, service.admissionDeferredCount(), "只拒了第一条");
		assertEquals(1, dispatched.get(), "后一条仍应被派发，扫描不被首次拒绝中断");
		verify(dao, never()).updateById(any(SecureInvokeRecord.class));
	}

	/** 对照：资源可用时照常派发，deferral 计数保持 0。 */
	@Test
	void acceptedExecutionIsUnaffected() {
		SecureInvokeService service = serviceWith(r -> dispatched.incrementAndGet());

		service.doAsyncInvoke(record(8L, 3));

		assertEquals(1, dispatched.get());
		assertEquals(0, service.admissionDeferredCount());
	}
}
