package com.luohuo.flex.im.domain.vo.res;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
import com.luohuo.flex.model.entity.ws.ChatMessageResp;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
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
    public static final List<String> CAPABILITIES = List.of("messages", "known-receipts", "thinking");

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

    /**
     * aichatoverview#351：可选思考 envelope（与消息范围完整性独立）。
     *
     * <p>缺字段或 thinkingComplete=false 时调用方不得视为无思考、不得移除既有
     * 元数据；thinkingAccess=false 时隐藏卡片与正文，不等同于消息失权；
     * 网络/泛化错误保持失败语义（thinkingComplete=false），不伪装成功空。
     */
    @Schema(description = "调用方是否通过思考授权；false 时隐藏卡片与正文")
    private boolean thinkingAccess;

    @Schema(description = "本窗口实际查询的触发消息 id 集合（十进制字符串，固定集合）")
    private List<String> thinkingTriggers;

    @Schema(description = "当前窗可见触发消息的思考元数据集合（id 升序，同触发允许多助理）")
    private List<AiclawThinkingListItemResp> thinkingItems;

    @Schema(description = "思考集合是否完整；false 时不得按缺项移除既有元数据")
    private boolean thinkingComplete;

    @Schema(description = "逐已知思考 ID 回执，每个请求 ID 恰好一条")
    private List<ThinkingKnownReceipt> thinkingKnownReceipts;

    @Schema(description = "已知思考回执是否齐全，独立于思考集合 complete")
    private boolean thinkingKnownComplete;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WindowBound {
        /**
         * aichatoverview#350 wire-compat：全局 LuohuoJacksonModule 把 Long 转字符串
         *（JS 精度保护，ID 保持字符串正确），但 timeMs 是时间（与 send_time 同源，
         *毫秒值远小于 2^53），必须输出数字；客户端 Rust Option&lt;i64&gt; 只认数字。
         *显式按字段覆盖为数字；messageMaxId/roomId 等 ID 保持全局字符串，不动。
         */
        public static class LongAsNumberSerializer extends JsonSerializer<Long> {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value.longValue());
                }
            }
        }

        @Schema(description = "呈现时间（毫秒 epoch，与客户端 send_time 同源，数字）")
        @JsonSerialize(using = LongAsNumberSerializer.class)
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

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ThinkingKnownReceipt {
        @Schema(description = "已知思考 id（十进制字符串）")
        private String id;
        @Schema(description = "当前快照下调用者是否可展示；false 为统一 unavailable，不透露原因")
        private boolean available;
        @Schema(description = "available=true 时的权威元数据（含 bodyETag）；available=false 时为 null")
        private AiclawThinkingListItemResp metadata;
    }
}
