package com.luohuo.flex.im.core.chat.service;

import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.utils.TimeUtils;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.enums.MessageStatusEnum;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * aichatoverview#350：窗口请求的纯边界/裁剪逻辑（无 Spring、无 DB，可单测）。
 *
 * <p>时间按秒取整：im_message.create_time 为 DATETIME 精度，毫秒级流式回填
 * （stream_start 回灌，见 MessageAdapter#buildMsgSave）靠 id 次键保序；
 * 客户端 send_time(ms) 与服务端呈现时间的映射与既有同步路径同源
 * （TimeUtils 互转，要求服务端系统时区为东八区，与既有路径同一假设）。
 */
public final class MsgWindowSupport {

    public static final String MODE_TAIL = "tail";
    public static final String MODE_RANGE = "range";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_KNOWN_IDS = 100;

    private MsgWindowSupport() {
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return MODE_TAIL;
        }
        String m = mode.trim().toLowerCase();
        if (MODE_TAIL.equals(m) || MODE_RANGE.equals(m)) {
            return m;
        }
        throw new BizException("未知窗口模式: " + mode);
    }

    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
    }

    /**
     * 十进制字符串 id；blank 视为无此界；非法 fail fast，不猜。
     */
    public static Long parseId(String id, String field) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw new BizException(field + " 须为十进制消息 id");
        }
    }

    public static LocalDateTime toDateTime(Long epochMilli) {
        if (epochMilli == null) {
            return null;
        }
        return TimeUtils.getDateTimeOfTimestamp(epochMilli);
    }

    /**
     * 按秒向下取整，与 DATETIME 列精度对齐；同秒内顺序由 id 次键保证。
     */
    public static LocalDateTime floorToSecond(LocalDateTime t) {
        if (t == null) {
            return null;
        }
        return t.withNano(0);
    }

    public static long toTimeMs(LocalDateTime t) {
        return TimeUtils.getTime(t);
    }

    /**
     * 下界（含）：ct &gt; ft，或 ct == ft 且 id &gt;= fid；单边缺省时只比存在的一边。
     */
    public static boolean aboveLower(Message m, LocalDateTime ft, Long fid) {
        if (ft != null && m.getCreateTime() != null) {
            int c = m.getCreateTime().compareTo(ft);
            if (c < 0) {
                return false;
            }
            if (c == 0 && fid != null && m.getId() < fid) {
                return false;
            }
            return true;
        }
        if (fid != null && m.getId() < fid) {
            return false;
        }
        return true;
    }

    /**
     * 上界（含）：复合语义 ct &lt; tt，或 ct == tt 且 id &lt;= toId；
     * lastMsgId 是合法历史硬上限（被踢出者），与时间无关始终生效；
     * tt 缺省时 toId 退化为纯 id 上界（tail 快照上界）。
     */
    public static boolean belowUpper(Message m, LocalDateTime tt, Long toId, Long lastMsgId) {
        if (lastMsgId != null && m.getId() > lastMsgId) {
            return false;
        }
        if (tt == null) {
            if (toId != null && m.getId() > toId) {
                return false;
            }
            return true;
        }
        if (m.getCreateTime() == null) {
            return true;
        }
        int c = m.getCreateTime().compareTo(tt);
        if (c > 0) {
            return false;
        }
        if (c == 0 && toId != null && m.getId() > toId) {
            return false;
        }
        return true;
    }

    public static final class RangedMessages {
        public final List<Message> items;
        public final boolean complete;

        public RangedMessages(List<Message> items, boolean complete) {
            this.items = items;
            this.complete = complete;
        }
    }

    /**
     * ASC 有序输入按复合上下界裁剪；超限取前 pageSize 并 complete=false，不静默截断。
     */
    public static RangedMessages clip(List<Message> asc, LocalDateTime ft, Long fid,
                                      LocalDateTime tt, Long toId, Long lastMsgId, int pageSize) {
        List<Message> kept = new ArrayList<>(Math.min(asc.size(), pageSize + 1));
        for (Message m : asc) {
            if (aboveLower(m, ft, fid) && belowUpper(m, tt, toId, lastMsgId)) {
                kept.add(m);
            }
        }
        if (kept.size() > pageSize) {
            return new RangedMessages(new ArrayList<>(kept.subList(0, pageSize)), false);
        }
        return new RangedMessages(kept, true);
    }

    /**
     * 已知项可见性：同房间 + 状态正常 + 历史上限内 + 作者未被调用方屏蔽。
     * 不满足统一视为不可展示，调用方不得区分原因。
     */
    public static boolean isVisible(Message m, Long roomId, Long lastMsgId, Set<String> blackUidSet) {
        if (m == null || !roomId.equals(m.getRoomId())) {
            return false;
        }
        if (!MessageStatusEnum.NORMAL.getStatus().equals(m.getStatus())) {
            return false;
        }
        if (lastMsgId != null && m.getId() > lastMsgId) {
            return false;
        }
        return m.getFromUid() == null || !blackUidSet.contains(m.getFromUid().toString());
    }
}
