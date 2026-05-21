package com.luohuo.flex.im.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 消息撤回的推送类
 * @author nyh
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMsgSendDto {

    /**
     * 消息id
     */
    private Long msgId;

    /**
     * 操作人id
     */
    private Long uid;

	/**
	 * REQ-004 M2-2: aiclaw 扩展字段透传（thinkingId、autoReply 等）
	 */
	private Map<String, Object> extra;

}
