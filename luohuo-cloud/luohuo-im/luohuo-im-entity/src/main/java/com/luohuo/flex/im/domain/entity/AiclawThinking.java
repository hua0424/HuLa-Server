package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luohuo.basic.base.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * aiclaw thinking 记录表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("im_aiclaw_thinking")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "aiclaw thinking 记录表")
public class AiclawThinking extends Entity<Long> {

	private static final long serialVersionUID = 1L;

	/**
	 * 产生 thinking 的 aiclaw uid
	 */
	@TableField("aiclaw_uid")
	private Long aiclawUid;

	/**
	 * 所属群聊 room_id
	 */
	@TableField("room_id")
	private Long roomId;

	/**
	 * 触发本次 thinking 的消息 ID（im_message.id）
	 */
	@TableField("trigger_msg_id")
	private Long triggerMsgId;

	/**
	 * 完整思考文本
	 */
	@TableField("content")
	private String content;

	/**
	 * 处理耗时（毫秒），THINKING_END 时回填
	 */
	@TableField("duration_ms")
	private Integer durationMs;

	/**
	 * 是否产生了回复消息：0=否，1=是
	 */
	@TableField("has_response")
	private Integer hasResponse;
}
