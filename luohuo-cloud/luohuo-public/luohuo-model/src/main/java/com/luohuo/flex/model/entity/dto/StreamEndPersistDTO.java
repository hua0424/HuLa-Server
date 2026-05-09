package com.luohuo.flex.model.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式消息结束持久化DTO（WS模块 → IM模块 via MQ）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StreamEndPersistDTO implements Serializable {
	private Long msgId;
	private Long fromUid;
	private Long toUid;
	private Long roomId;
	private String content;
	private Long tenantId;
}
