package com.luohuo.flex.model.ws;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式消息片段请求（plugins → server）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamDeltaReq implements Serializable {
	private String chunk;
	private Integer seq;
}
