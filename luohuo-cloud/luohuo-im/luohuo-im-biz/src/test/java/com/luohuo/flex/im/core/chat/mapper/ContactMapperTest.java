package com.luohuo.flex.im.core.chat.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luohuo.flex.im.domain.entity.Contact;
import com.luohuo.flex.im.test.AbstractMapperIT;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISS-003 / ISS-004 / ISS-006: ContactMapper 写入与单调保护语义验证。
 *
 * <ul>
 *   <li>refreshLastMsgId(ISS-003):写入路径推进 last_msg_id,IF + INSERT 兜底缺行</li>
 *   <li>refreshOrCreateActiveTime(ISS-004/006):同时推进 last_msg_id / active_time,各自独立单调保护</li>
 * </ul>
 */
class ContactMapperTest extends AbstractMapperIT {

    @Resource
    private ContactMapper contactMapper;
    @Resource
    private JdbcTemplate jdbc;

    private static final Long ROOM_ID = 8001L;
    private static final Long UID_A = 901L;
    private static final Long UID_B = 902L;
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 5, 12, 10, 0, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(5);

    @BeforeEach
    void cleanContact() {
        jdbc.update("DELETE FROM im_contact WHERE room_id = ?", ROOM_ID);
    }

    private Contact loadContact(Long uid) {
        return contactMapper.selectOne(new QueryWrapper<Contact>()
                .eq("room_id", ROOM_ID)
                .eq("uid", uid));
    }

    @Nested
    @DisplayName("ISS-003 refreshLastMsgId")
    class RefreshLastMsgId {

        @Test
        @DisplayName("首次写入:Contact 行缺失 → 插入新行")
        void firstInsert_createsRow() {
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));

            Contact c = loadContact(UID_A);
            assertThat(c).isNotNull();
            assertThat(c.getLastMsgId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常推进:新 msgId 覆盖旧 msgId")
        void monotonicForward() {
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));
            contactMapper.refreshLastMsgId(ROOM_ID, 200L, List.of(UID_A));

            assertThat(loadContact(UID_A).getLastMsgId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("幂等:同 msgId 重复写入不变")
        void idempotent_sameMsgId() {
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));

            assertThat(loadContact(UID_A).getLastMsgId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("乱序:旧 msgId 不回退")
        void staleMsgId_noRollback() {
            contactMapper.refreshLastMsgId(ROOM_ID, 200L, List.of(UID_A));
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));

            assertThat(loadContact(UID_A).getLastMsgId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("多成员:批量推进所有 uid 的 last_msg_id")
        void multiMember_allAdvance() {
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A, UID_B));

            assertThat(loadContact(UID_A).getLastMsgId()).isEqualTo(100L);
            assertThat(loadContact(UID_B).getLastMsgId()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("ISS-004/006 refreshOrCreateActiveTime")
    class RefreshOrCreateActiveTime {

        @Test
        @DisplayName("首次写入:同时填充 last_msg_id 和 active_time")
        void firstInsert_fillsBoth() {
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 100L, T1);

            Contact c = loadContact(UID_A);
            assertThat(c).isNotNull();
            assertThat(c.getLastMsgId()).isEqualTo(100L);
            assertThat(c.getActiveTime()).isEqualTo(T1);
        }

        @Test
        @DisplayName("正常推进:both 向前")
        void monotonicForward() {
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 100L, T1);
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 200L, T2);

            Contact c = loadContact(UID_A);
            assertThat(c.getLastMsgId()).isEqualTo(200L);
            assertThat(c.getActiveTime()).isEqualTo(T2);
        }

        @Test
        @DisplayName("ISS-004: 各字段独立单调 — 旧 msgId 不回退,但新 activeTime 仍能推进")
        void staleMsgId_advancingTime_independentGuards() {
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 200L, T1);
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 100L, T2);

            Contact c = loadContact(UID_A);
            assertThat(c.getLastMsgId()).isEqualTo(200L); // 不回退
            assertThat(c.getActiveTime()).isEqualTo(T2);   // 独立维度仍推进
        }

        @Test
        @DisplayName("ISS-004: 各字段独立单调 — 旧 activeTime 不回退,但新 msgId 仍能推进")
        void advancingMsgId_staleTime_independentGuards() {
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 100L, T2);
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 200L, T1);

            Contact c = loadContact(UID_A);
            assertThat(c.getLastMsgId()).isEqualTo(200L);  // 独立维度推进
            assertThat(c.getActiveTime()).isEqualTo(T2);   // 不回退
        }

        @Test
        @DisplayName("IS NULL 兜底:已存在但 active_time 为 NULL 的行也能初始化")
        void existingNullActiveTime_filledOnFirstHit() {
            // 先通过 refreshLastMsgId 建一个 active_time IS NULL 的行
            contactMapper.refreshLastMsgId(ROOM_ID, 100L, List.of(UID_A));
            assertThat(loadContact(UID_A).getActiveTime()).isNull();

            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A), 200L, T1);

            Contact c = loadContact(UID_A);
            assertThat(c.getLastMsgId()).isEqualTo(200L);
            assertThat(c.getActiveTime()).isEqualTo(T1);
        }

        @Test
        @DisplayName("ISS-006: 多成员场景 — sender 自己也在 list 内时一起推进")
        void multiMember_senderIncluded_allAdvance() {
            contactMapper.refreshOrCreateActiveTime(ROOM_ID, List.of(UID_A, UID_B), 100L, T1);

            assertThat(loadContact(UID_A).getActiveTime()).isEqualTo(T1);
            assertThat(loadContact(UID_B).getActiveTime()).isEqualTo(T1);
        }
    }
}
