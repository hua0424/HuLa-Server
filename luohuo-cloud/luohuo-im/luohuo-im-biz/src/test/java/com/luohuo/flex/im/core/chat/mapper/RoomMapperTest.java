package com.luohuo.flex.im.core.chat.mapper;

import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.test.AbstractMapperIT;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISS-005: 验证 RoomMapper.refreshActiveTime 的 IF 单调保护语义。
 * 与 ContactMapper.refreshOrCreateActiveTime(ISS-004)同构,但 Room 单行 UPDATE 不带 INSERT 兜底。
 */
class RoomMapperTest extends AbstractMapperIT {

    @Resource
    private RoomMapper roomMapper;
    @Resource
    private JdbcTemplate jdbc;

    private static final Long ROOM_ID = 9001L;
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 5, 12, 10, 0, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(5);
    private static final LocalDateTime T0 = T1.minusMinutes(5);

    @BeforeEach
    void resetRoom() {
        jdbc.update("DELETE FROM im_room WHERE id = ?", ROOM_ID);
        jdbc.update(
                "INSERT INTO im_room(id, type, hot_flag, active_time, last_msg_id, tenant_id, create_by) " +
                        "VALUES (?, 2, 0, ?, NULL, 1, 1)",
                ROOM_ID, T0);
    }

    @Test
    @DisplayName("ISS-005: last_msg_id IS NULL 时首次推进")
    void firstAdvance_fromNull() {
        roomMapper.refreshActiveTime(ROOM_ID, 100L, T1);

        Room r = roomMapper.selectById(ROOM_ID);
        assertThat(r.getLastMsgId()).isEqualTo(100L);
        assertThat(r.getActiveTime()).isEqualTo(T1);
    }

    @Test
    @DisplayName("ISS-005: 新 msgId / 新 sendTime 双向推进")
    void monotonicForward() {
        roomMapper.refreshActiveTime(ROOM_ID, 100L, T1);
        roomMapper.refreshActiveTime(ROOM_ID, 200L, T2);

        Room r = roomMapper.selectById(ROOM_ID);
        assertThat(r.getLastMsgId()).isEqualTo(200L);
        assertThat(r.getActiveTime()).isEqualTo(T2);
    }

    @Test
    @DisplayName("ISS-005: 旧 msgId 不回退 last_msg_id")
    void staleMsgId_noRollback() {
        roomMapper.refreshActiveTime(ROOM_ID, 200L, T1);
        roomMapper.refreshActiveTime(ROOM_ID, 100L, T1);

        Room r = roomMapper.selectById(ROOM_ID);
        assertThat(r.getLastMsgId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("ISS-005: 旧 sendTime 不回退 active_time")
    void staleTime_noRollback() {
        roomMapper.refreshActiveTime(ROOM_ID, 100L, T2);
        roomMapper.refreshActiveTime(ROOM_ID, 200L, T1);

        Room r = roomMapper.selectById(ROOM_ID);
        // msgId 应推进(200>100),但 active_time 不应回退到 T1
        assertThat(r.getLastMsgId()).isEqualTo(200L);
        assertThat(r.getActiveTime()).isEqualTo(T2);
    }
}
