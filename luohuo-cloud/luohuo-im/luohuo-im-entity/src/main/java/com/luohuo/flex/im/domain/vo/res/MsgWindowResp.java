package com.luohuo.flex.im.domain.vo.res;

import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * aichatoverview#350：当前阅读窗口校准响应 envelope。
 *
 * <p>只承诺声明范围（coveredLower/coveredUpper + complete），不承诺全房间或多请求同刻快照。
 * knownReceipts 与范围 complete 独立：只有 knownComplete=true 且每个已知 ID 回执齐全，
 * 调用方才能应用不可用事实；普通分页缺项、协议缺字段、网络失败或范围溢出不是删除证据。
 * unavailable 统一表示当前快照下调用者不可展示该项，不透露物理不存在、跨房间或逻辑删除原因。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MsgWindowResp {

    /** 协议版本，客户端据此判定是否可用，不推测旧服务端能力。 */
    public static final String SCHEMA_VERSION = "msg-window-v1";

    /** 本 envelope 实际承载的能力集合。 */
    public static final List<String> CAPABILITIES = List.of("messages", "known-receipts");

    @Schema(description = "协议版本")
    private String schemaVersion;

    @Schema(description = "能力集合")
    private List<String> capabilities;

    @Schema(description = "请求标识回显，仅做关联")
    private String requestId;

    @Schema(description = "会话id")
    private Long roomId;

    @Schema(description = "范围内权威消息（呈现时间升序，次键 id 升序）")
    private List<ChatMessageResp> items;

    @Schema(description = "实际覆盖下界（所返 items 的最小时间+ID，无项时为 null）")
    private WindowBound coveredLower;

    @Schema(description = "实际覆盖上界（所返 items 的最大时间+ID，无项时为 null）")
    private WindowBound coveredUpper;

    @Schema(description = "声明范围内是否完整；false 表示溢出，调用方拆小范围续查，不得把未返项当删除")
    private boolean complete;

    @Schema(description = "逐已知 ID 回执，每个请求 ID 恰好一条")
    private List<KnownReceipt> knownReceipts;

    @Schema(description = "已知项回执是否齐全，独立于范围 complete")
    private boolean knownComplete;

    @Schema(description = "调用方是否通过消息授权（通过才返回本 envelope）")
    private boolean messagesAccess;

    @Schema(description = "合法读取路径已有的 messageMaxId 上限（lastMsgId；热点房无上限时为 null）")
    private Long messageMaxId;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WindowBound {
        @Schema(description = "呈现时间（毫秒 epoch，与客户端 send_time 同源）")
        private Long timeMs;
        @Schema(description = "消息 id（十进制字符串）")
        private String id;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class KnownReceipt {
        @Schema(description = "已知消息 id（十进制字符串）")
        private String id;
        @Schema(description = "当前快照下调用者是否可展示；false 为统一 unavailable，不透露原因")
        private boolean available;
        @Schema(description = "available=true 时的权威内容；available=false 时为 null")
        private ChatMessageResp message;
    }
}
