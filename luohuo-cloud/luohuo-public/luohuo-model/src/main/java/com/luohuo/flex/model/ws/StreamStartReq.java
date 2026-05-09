package com.luohuo.flex.model.ws;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式消息开始请求（plugins → server）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamStartReq implements Serializable {
	private Long fromUid;
	private Long toUid;
	private Long roomId;
}
