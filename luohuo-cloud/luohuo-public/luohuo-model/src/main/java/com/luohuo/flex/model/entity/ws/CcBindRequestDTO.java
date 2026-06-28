package com.luohuo.flex.model.entity.ws;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * REQ-010 S9: server→node 的 CC 绑定请求载荷。
 *
 * <p>ws-server 向目标 CC（claude-code）aiclaw 的 node 推送，请其生成 owner 启动命令。
 * node 收到后按 {@code requestId} 回执一个 {@code CC_BIND_RESULT}（{@link CcBindResultDTO}）。</p>
 *
 * @author developer
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CcBindRequestDTO implements Serializable {

	private static final long serialVersionUID = 1L;

	@Schema(description = "目标房间ID")
	private Long roomId;

	@Schema(description = "房间类型 1=群聊 2=单聊")
	private Integer roomType;

	@Schema(description = "单聊场景下的对端真人 uid；群聊为 null")
	private Long counterpartUid;

	@Schema(description = "关联ID（UUID），node 回执时原样带回用于 in-process 关联")
	private String requestId;
}
