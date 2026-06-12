package com.luohuo.flex.im.core.chat.service;

import cn.hutool.core.util.IdUtil;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Thinking 记录管理服务
 */
@Slf4j
@Service
public class ThinkingService {

	/**
	 * content 落库上限：200KB（按 UTF-8 字节计），超出则截断并标记 status=4
	 */
	private static final int MAX_CONTENT_BYTES = 200 * 1024;

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
				.aiclawUid(aiclawUid)
				.roomId(roomId)
				.triggerMsgId(triggerMsgId)
				.content("")
				.hasResponse(0)
				.build();
		thinking.setId(thinkingId);

		thinkingMapper.insert(thinking);
		log.debug("thinking created: id={}, aiclawUid={}, roomId={}", thinkingId, aiclawUid, roomId);
		return thinkingId;
	}

	/**
	 * 结束 thinking 记录：落全文（200KB UTF-8 安全截断）+ 回填耗时 + 映射状态
	 *
	 * <p>S4 起 THINKING_END 携带全文，content 由此一次性落库（不再走 delta 增量）。</p>
	 *
	 * @param thinkingId thinking ID
	 * @param content    完整思考文本（END 携带全文）
	 * @param durationMs 处理耗时（毫秒）
	 * @param status     上游状态："complete"=正常, "error"=失败（含 timeout）
	 * @param error      错误信息（error 路径写入 error_code）
	 */
	public void finalize(Long thinkingId, String content, Integer durationMs, String status, String error) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			log.warn("finalize: thinking not found, id={}", thinkingId);
			return;
		}

		String safe = content == null ? "" : content;
		int originalBytes = safe.getBytes(StandardCharsets.UTF_8).length;
		boolean truncated = originalBytes > MAX_CONTENT_BYTES;
		String stored = truncated ? truncateUtf8(safe, MAX_CONTENT_BYTES) : safe;
		if (truncated) {
			log.warn("thinking content truncated: id={}, originalBytes={}, maxBytes={}",
					thinkingId, originalBytes, MAX_CONTENT_BYTES);
		}

		boolean isError = "error".equals(status);
		int mappedStatus;
		if (isError) {
			// error 路径优先：即使内容超长也保持 2/3，不降级为 4
			boolean timeout = error != null && error.toLowerCase().contains("timeout");
			mappedStatus = timeout ? 3 : 2;
			thinking.setErrorCode(truncateErrorCode(error));
		} else {
			// 非 error（complete / 未知）视为成功路径：超长则 4，否则 1
			mappedStatus = truncated ? 4 : 1;
		}

		thinking.setContent(stored);
		thinking.setDurationMs(durationMs);
		thinking.setStatus(mappedStatus);
		thinkingMapper.updateById(thinking);
		log.debug("thinking finalized: id={}, durationMs={}, status={}, truncated={}",
				thinkingId, durationMs, mappedStatus, truncated);
	}

	/**
	 * 标记 thinking 记录为错误状态
	 *
	 * @param thinkingId thinking ID
	 * @param errorCode  错误码
	 */
	public void markError(Long thinkingId, String errorCode) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			log.warn("markError: thinking not found, id={}", thinkingId);
			return;
		}

		// timeout 特殊处理为 status=3
		thinking.setStatus("timeout".equals(errorCode) ? 3 : 2);
		thinking.setErrorCode(truncateErrorCode(errorCode));
		thinkingMapper.updateById(thinking);
		log.warn("thinking marked error: id={}, errorCode={}", thinkingId, errorCode);
	}

	/**
	 * 反查指定 aiclaw 在指定房间内最近一条进行中（status=0）的 thinking id
	 *
	 * @param aiclawUid aiclaw uid
	 * @param roomId    房间 ID
	 * @return 最新的进行中 thinking id，无则 null
	 */
	public Long resolveActiveThinking(Long aiclawUid, Long roomId) {
		return thinkingMapper.selectActiveThinkingId(aiclawUid, roomId);
	}

	/**
	 * 按 ID 查询 thinking 记录
	 */
	public AiclawThinking getById(Long thinkingId) {
		return thinkingMapper.selectById(thinkingId);
	}

	/**
	 * error_code 列为 VARCHAR(64)，plugin 上送的异常消息可能超长，直接落库会触发 SQL 截断错误或数据丢失。
	 * 这里按字符长度截断到最多 64 字符（列为 utf8mb4 64 字符）；入参为 null 时返回 null。
	 */
	private String truncateErrorCode(String errorCode) {
		if (errorCode == null) {
			return null;
		}
		return errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode;
	}

	/**
	 * UTF-8 安全截断：保证结果 re-encode 后不超过 maxBytes 字节，且绝不破坏多字节字符。
	 * 仅解码前 maxBytes 字节，忽略末尾被截断的不完整字符。
	 */
	private String truncateUtf8(String s, int maxBytes) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= maxBytes) {
			return s;
		}
		CharBuffer cb = CharBuffer.allocate(s.length());
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE);
		decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes), cb, true);
		decoder.flush(cb);
		cb.flip();
		return cb.toString();
	}
}
