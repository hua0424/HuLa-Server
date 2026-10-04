package com.luohuo.flex.im.domain.vo.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * aichatoverview#350：当前阅读窗口校准请求。
 *
 * <p>range 为呈现时间+ID 复合闭区间；tail 由此次快照固定上界并向下覆盖。
 * ID 均为十进制字符串，调用方不得转 JS Number；actor/tenant 取自认证上下文，
 * 调用方不得指定 UID 或 skip 绕过权限。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MsgWindowReq {

    @NotNull(message = "房间id不能为空")
    @Schema(description = "会话id")
    private Long roomId;

    @Schema(description = "调用方请求标识，仅做关联，不做单调 revision")
    private String requestId;

    @Schema(description = "窗口模式：range 复合闭区间 / tail 快照上界，缺省 tail")
    private String mode;

    @Schema(description = "区间下界呈现时间（毫秒 epoch，可空）")
    private Long fromTimeMs;

    @Schema(description = "区间下界消息 id（十进制字符串，可空）")
    private String fromId;

    @Schema(description = "区间上界呈现时间（毫秒 epoch，可空）")
    private Long toTimeMs;

    @Schema(description = "区间上界消息 id（十进制字符串，可空）")
    private String toId;

    @Size(max = 100, message = "已知消息 id 最多 100 个")
    @Schema(description = "已知消息 id（十进制字符串），每个恰好一条回执")
    private List<String> knownMsgIds;

    @Size(max = 100, message = "已知思考 id 最多 100 个")
    @Schema(description = "已知思考 id（十进制字符串），每个恰好一条思考回执；思考回执与消息范围完整性独立")
    private List<String> knownThinkingIds;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 100, message = "pageSize 最大为 100")
    @Schema(description = "范围返回上限，缺省 20")
    private Integer pageSize;
}
