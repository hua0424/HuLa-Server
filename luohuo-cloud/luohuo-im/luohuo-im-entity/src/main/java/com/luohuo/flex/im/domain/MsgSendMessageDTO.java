package com.luohuo.flex.im.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.Map;

/**
 * @author nyh
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MsgSendMessageDTO implements Serializable {
    /**
     * 消息id
     */
    private Long msgId;
    /**
     * 操作人uid
     */
    private Long uid;
	/**
	 * 租户id
	 */
	private Long tenantId;
	/**
	 * REQ-004 M2-2: aiclaw 扩展字段透传（thinkingId、autoReply 等）
	 */
	private Map<String, Object> extra;
}
