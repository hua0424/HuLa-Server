package com.luohuo.flex.im.core.chat.service;

import cn.hutool.core.util.IdUtil;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thinking 记录管理服务
 */
@Slf4j
@Service
public class ThinkingService {

	@Resource
	private AiclawThinkingMapper thinkingMapper;

	/**
	 * 创建 thinking 记录
	 *
	 * @param aiclawUid    产生 thinking 的 aiclaw uid
	 * @param roomId       所属群聊 room_id
	 * @param triggerMsgId 触发消息 ID
	 * @return 生成的 thinkingId（雪花ID）
	 */
	public Long create(Long aiclawUid, Long roomId, Long triggerMsgId) {
		Long thinkingId = IdUtil.getSnowflakeNextId();

		AiclawThinking thinking = AiclawThinking.builder()
				.id(thinkingId)
				.aiclawUid(aiclawUid)
				.roomId(roomId)
				.triggerMsgId(triggerMsgId)
				.content("")
				.hasResponse(0)
				.build();

		thinkingMapper.insert(thinking);
		log.debug("thinking created: id={}, aiclawUid={}, roomId={}", thinkingId, aiclawUid, roomId);
		return thinkingId;
	}

	/**
	 * 追加 delta 内容到 thinking 记录
	 *
	 * @param thinkingId thinking ID
	 * @param chunk      增量文本
	 * @param seq        序列号
	 */
	public void appendDelta(Long thinkingId, String chunk, Integer seq) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			log.warn("appendDelta: thinking not found, id={}", thinkingId);
			return;
		}

		// 追加内容（简单拼接，M2 阶段先不处理并发冲突）
		String newContent = thinking.getContent() + chunk;
		thinking.setContent(newContent);
		// seq 不持久化到 DB，仅用于 WS 顺序校验
		thinkingMapper.updateById(thinking);
		log.debug("thinking delta appended: id={}, seq={}, contentLen={}", thinkingId, seq, newContent.length());
	}

	/**
	 * 结束 thinking 记录，回填耗时
	 *
	 * @param thinkingId thinking ID
	 * @param durationMs 处理耗时（毫秒）
	 */
	public void finalize(Long thinkingId, Integer durationMs) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			log.warn("finalize: thinking not found, id={}", thinkingId);
			return;
		}

		thinking.setDurationMs(durationMs);
		thinkingMapper.updateById(thinking);
		log.debug("thinking finalized: id={}, durationMs={}", thinkingId, durationMs);
	}

	/**
	 * 按 ID 查询 thinking 记录
	 */
	public AiclawThinking getById(Long thinkingId) {
		return thinkingMapper.selectById(thinkingId);
	}
}
