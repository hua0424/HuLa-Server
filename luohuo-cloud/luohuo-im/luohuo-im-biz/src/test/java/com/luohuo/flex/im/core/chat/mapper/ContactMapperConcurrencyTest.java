package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luohuo.flex.im.domain.entity.Contact;
import com.luohuo.flex.im.test.AbstractMapperIT;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISS-003/004: 并发场景下 IF 单调保护的正确性回归。
 *
 * <p>模拟:写入路径(sendMsg 同事务)与 MQ 异步路径(MsgSendConsumer)同时调用 refreshLastMsgId,
 * 顺序未知,断言 IF 保护下最终 last_msg_id 永远 = max(两个 msgId),不会被乱序覆盖回退。
 */
class ContactMapperConcurrencyTest extends AbstractMapperIT {

    @Resource
    private ContactMapper contactMapper;
    @Resource
    private JdbcTemplate jdbc;

    private static final Long ROOM_ID = 7001L;
    private static final Long UID = 701L;

    @RepeatedTest(50)
    @DisplayName("ISS-003 IF 单调:两线程并发写新旧 msgId,最终 = max(两者)")
    void concurrentRefreshLastMsgId_neverRollback() throws InterruptedException, ExecutionException {
        jdbc.update("DELETE FROM im_contact WHERE room_id = ? AND uid = ?", ROOM_ID, UID);

        long oldMsgId = 100L;
        long newMsgId = 200L;
        long max = Math.max(oldMsgId, newMsgId);

        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitStart(startGate);
                contactMapper.refreshLastMsgId(ROOM_ID, oldMsgId, List.of(UID));
            });
            Future<?> f2 = pool.submit(() -> {
                awaitStart(startGate);
                contactMapper.refreshLastMsgId(ROOM_ID, newMsgId, List.of(UID));
            });
            f1.get();
            f2.get();
        } finally {
            pool.shutdownNow();
        }

        Contact c = contactMapper.selectOne(new QueryWrapper<Contact>()
                .eq("room_id", ROOM_ID)
                .eq("uid", UID));
        assertThat(c.getLastMsgId()).isEqualTo(max);
    }

    @RepeatedTest(20)
    @DisplayName("ISS-004 IF 单调:并发 refreshOrCreateActiveTime,last_msg_id / active_time 各自独立不回退")
    void concurrentRefreshActiveTime_eachFieldMonotonic() throws InterruptedException, ExecutionException {
        jdbc.update("DELETE FROM im_contact WHERE room_id = ? AND uid = ?", ROOM_ID, UID);

        long oldMsgId = 100L;
        long newMsgId = 300L;
        LocalDateTime oldTime = LocalDateTime.of(2026, 5, 12, 10, 0, 0);
        LocalDateTime newTime = oldTime.plusMinutes(10);

        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitStart(startGate);
                contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID), oldMsgId, oldTime);
            });
            Future<?> f2 = pool.submit(() -> {
                awaitStart(startGate);
                contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID), newMsgId, newTime);
            });
            f1.get();
            f2.get();
        } finally {
            pool.shutdownNow();
        }

        Contact c = contactMapper.selectOne(new QueryWrapper<Contact>()
                .eq("room_id", ROOM_ID)
                .eq("uid", UID));
        assertThat(c.getLastMsgId()).isEqualTo(newMsgId);
        assertThat(c.getActiveTime()).isEqualTo(newTime);
    }

    private static void awaitStart(CyclicBarrier gate) {
        try {
            gate.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
