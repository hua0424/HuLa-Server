package com.luohuo.flex.im.domain.vo.resp.aiclaw;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-010 S9: im 侧解析「房间的 CC（claude-code）aiclaw + owner 鉴权」结果。
 *
 * <p>供 ws-server 在调起 CC 绑定前调用。成功时 {@link #ok}=true 并带回
 * {@code aiclawUid / roomType / counterpartUid}；失败时 {@link #ok}=false 并带
 * {@link #errorCode}（ws 据此映射出对客户端的 R 失败 msg）。</p>
 *
 * <p>errorCode 取值：
 * <ul>
 *   <li>{@code NO_CC} —— 该房间没有 CC 助理</li>
 *   <li>{@code AMBIGUOUS} —— 房间有多个 CC 助理，需指定 uid</li>
 *   <li>{@code UID_NOT_CC} —— 指定 uid 不是该房间的 CC 助理</li>
 *   <li>{@code NOT_OWNER} —— 请求者非该 CC 助理 owner（无权限）</li>
 * </ul></p>
 *
 * @author developer
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CcAiclawResolveResp implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "是否解析成功")
	private Boolean ok;

	@Schema(description = "失败错误码：NO_CC / AMBIGUOUS / UID_NOT_CC / NOT_OWNER")
	private String errorCode;

	@Schema(description = "解析到的 CC aiclaw uid（成功时非空）")
	private Long aiclawUid;

	@Schema(description = "房间类型 1=群聊 2=单聊（成功时非空）")
	private Integer roomType;

	@Schema(description = "单聊场景的对端真人 uid；群聊为 null")
	private Long counterpartUid;
}
