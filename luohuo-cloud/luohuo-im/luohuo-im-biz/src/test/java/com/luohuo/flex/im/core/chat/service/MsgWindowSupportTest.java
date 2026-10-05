package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.enums.MessageStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aichatoverview#350：窗口边界/裁剪纯逻辑测试（无 Spring、无 DB）。
 */
class MsgWindowSupportTest {

    private static Message msg(long id, LocalDateTime t, Long roomId, Long fromUid, int status) {
        Message m = new Message();
        m.setId(id);
        m.setCreateTime(t);
        m.setRoomId(roomId);
        m.setFromUid(fromUid);
        m.setStatus(status);
        return m;
    }

    private static Message msg(long id, LocalDateTime t) {
        return msg(id, t, 10L, 200L, MessageStatusEnum.NORMAL.getStatus());
    }

    @Test
    @DisplayName("mode 缺省 tail，非法抛 BizException")
    void normalizeMode() {
        assertEquals("tail", MsgWindowSupport.normalizeMode(null));
        assertEquals("tail", MsgWindowSupport.normalizeMode("  "));
        assertEquals("range", MsgWindowSupport.normalizeMode("range"));
        assertThrows(BizException.class, () -> MsgWindowSupport.normalizeMode("cursor"));
    }

    @Test
    @DisplayName("pageSize 缺省 20，钳制 1..100")
    void normalizePageSize() {
        assertEquals(20, MsgWindowSupport.normalizePageSize(null));
        assertEquals(1, MsgWindowSupport.normalizePageSize(0));
        assertEquals(100, MsgWindowSupport.normalizePageSize(500));
        assertEquals(20, MsgWindowSupport.normalizePageSize(20));
    }

    @Test
    @DisplayName("parseId：blank 为无界，非法 fail fast")
    void parseId() {
        assertNull(MsgWindowSupport.parseId(null, "fromId"));
        assertNull(MsgWindowSupport.parseId("  ", "fromId"));
        assertEquals(42L, MsgWindowSupport.parseId("42", "fromId"));
        assertThrows(BizException.class, () -> MsgWindowSupport.parseId("T123", "fromId"));
        assertThrows(BizException.class, () -> MsgWindowSupport.parseId("abc", "knownMsgIds"));
    }

    @Test
    @DisplayName("复合下界：同时间靠 id 次键，跨时间只看时间")
    void aboveLower() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
        assertTrue(MsgWindowSupport.aboveLower(msg(9, t), t, 9L));
        assertFalse(MsgWindowSupport.aboveLower(msg(8, t), t, 9L));
        assertTrue(MsgWindowSupport.aboveLower(msg(1, t.plusSeconds(1)), t, 9L));
        assertFalse(MsgWindowSupport.aboveLower(msg(99, t.minusSeconds(1)), t, 9L));
        assertTrue(MsgWindowSupport.aboveLower(msg(5, t), null, 5L));
        assertFalse(MsgWindowSupport.aboveLower(msg(4, t), null, 5L));
    }

    @Test
    @DisplayName("复合上界：历史上限始终生效，同秒靠 id，tail 快照为纯 id 上界")
    void belowUpper() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
        assertTrue(MsgWindowSupport.belowUpper(msg(9, t), t, 9L, null));
        assertFalse(MsgWindowSupport.belowUpper(msg(10, t), t, 9L, null));
        assertTrue(MsgWindowSupport.belowUpper(msg(999, t.minusSeconds(1)), t, 9L, null));
        assertFalse(MsgWindowSupport.belowUpper(msg(1, t.plusSeconds(1)), t, 9L, null));
        // lastMsgId 硬上限与时间无关
        assertFalse(MsgWindowSupport.belowUpper(msg(101, t.minusDays(1)), null, null, 100L));
        assertTrue(MsgWindowSupport.belowUpper(msg(100, t.minusDays(1)), null, null, 100L));
        // tail 快照纯 id 上界
        assertTrue(MsgWindowSupport.belowUpper(msg(50, t), null, 50L, null));
        assertFalse(MsgWindowSupport.belowUpper(msg(51, t), null, 50L, null));
    }

    @Test
    @DisplayName("clip 超限不静默截断：取前 pageSize 且 complete=false")
    void clipOverflow() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
        List<Message> asc = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            asc.add(msg(i, t.plusSeconds(i)));
        }
        MsgWindowSupport.RangedMessages r = MsgWindowSupport.clip(asc, null, null, null, null, null, 20);
        assertFalse(r.complete);
        assertEquals(20, r.items.size());
        assertEquals(1L, r.items.get(0).getId());
        assertEquals(20L, r.items.get(19).getId());

        MsgWindowSupport.RangedMessages ok = MsgWindowSupport.clip(asc.subList(0, 20), null, null, null, null, null, 20);
        assertTrue(ok.complete);
        assertEquals(20, ok.items.size());
    }

    @Test
    @DisplayName("clip 上下界同时裁剪，tail 挤出不产删除")
    void clipBounds() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
        List<Message> asc = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            asc.add(msg(i, t.plusSeconds(i)));
        }
        MsgWindowSupport.RangedMessages r = MsgWindowSupport.clip(
                asc, t.plusSeconds(3), 3L, t.plusSeconds(7), 7L, null, 20);
        assertTrue(r.complete);
        assertEquals(5, r.items.size());
        assertEquals(3L, r.items.get(0).getId());
        assertEquals(7L, r.items.get(4).getId());
    }

    @Test
    @DisplayName("isVisible：跨房间/删除/超上限/屏蔽作者一律不可展示且不区分原因")
    void isVisible() {
        LocalDateTime t = LocalDateTime.now().withNano(0);
        assertTrue(MsgWindowSupport.isVisible(msg(5, t), 10L, 9L, Set.of()));
        assertFalse(MsgWindowSupport.isVisible(msg(5, t), 11L, null, Set.of()));
        assertFalse(MsgWindowSupport.isVisible(
                msg(5, t, 10L, 200L, MessageStatusEnum.DELETE.getStatus()), 10L, null, Set.of()));
        assertFalse(MsgWindowSupport.isVisible(msg(10, t), 10L, 9L, Set.of()));
        assertFalse(MsgWindowSupport.isVisible(msg(5, t, 10L, 200L,
                MessageStatusEnum.NORMAL.getStatus()), 10L, null, Set.of("200")));
        assertFalse(MsgWindowSupport.isVisible(null, 10L, null, Set.of()));
    }

    @Test
    @DisplayName("#351 capTriggers：空集完整、百内全查、溢出封顶 100 且 complete=false")
    void capTriggers() {
        MsgWindowSupport.CappedTriggers empty = MsgWindowSupport.capTriggers(new ArrayList<>());
        assertTrue(empty.complete);
        assertTrue(empty.queried.isEmpty());

        List<Long> under = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            under.add(i);
        }
        MsgWindowSupport.CappedTriggers full = MsgWindowSupport.capTriggers(under);
        assertTrue(full.complete);
        assertEquals(20, full.queried.size());

        List<Long> over = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            over.add(i);
        }
        MsgWindowSupport.CappedTriggers capped = MsgWindowSupport.capTriggers(over);
        assertFalse(capped.complete);
        assertEquals(100, capped.queried.size());
        assertEquals(1L, capped.queried.get(0));
        assertEquals(100L, capped.queried.get(99));
    }
}
