package com.luohuo.flex.im.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.luohuo.basic.base.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * thinking 与回复消息关联表
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("im_aiclaw_thinking_msg_rel")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "thinking 与回复消息关联表")
public class AiclawThinkingMsgRel extends SuperEntity<Long> {

	private static final long serialVersionUID = 1L;

	/**
	 * thinking 记录 ID（联合主键之一）
	 */
	@TableField("thinking_id")
	private Long thinkingId;

	/**
	 * 关联的 im_message.id（联合主键之一）
	 */
	@TableField("msg_id")
	private Long msgId;

	/**
	 * 关联建立时间
	 */
	@TableField("create_time")
	private LocalDateTime createTime;
}
