package com.luohuo.flex.model.ws;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 流式消息结束请求（plugins → server）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamEndReq implements Serializable {
	private String fullContent;
	private String status;
}
