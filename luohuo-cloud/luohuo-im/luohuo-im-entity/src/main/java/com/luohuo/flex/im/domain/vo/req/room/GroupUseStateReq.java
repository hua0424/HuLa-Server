package com.luohuo.flex.im.domain.vo.req.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * aichatoverview#484 (T3)：群成员/权限当前事实的窄读请求。
 *
 * <p>一次只读一个房间；{@code memberUids} 只表达「这次要投影哪些 UID」，
 * 未请求的 UID 不产生任何否定结论。调用者身份与租户一律来自认证上下文，
 * 不接受调用方传入的 uid。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupUseStateReq {

	/** 单次请求 UID 硬上限：超限的尾部 UID 被丢弃并在 coverage.truncated 标记。 */
	public static final int MAX_MEMBER_UIDS = 100;

	@Schema(description = "房间号")
	@NotNull
	private Long roomId;

	@Schema(description = "需要投影成员资格的 UID 集合（可空；上限 " + MAX_MEMBER_UIDS + "）")
	@Size(max = MAX_MEMBER_UIDS)
	private List<Long> memberUids;
}
