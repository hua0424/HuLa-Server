package com.luohuo.flex.im.core.chat.service;

import cn.hutool.core.util.IdUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

	@Resource
	private RoomCache roomCache;

	@Resource
	private GroupMemberCache groupMemberCache;

	@Resource
	private RoomFriendDao roomFriendDao;

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
	 * REQ-004 [S7]: 客户端按需回看 thinking 全文。
	 *
	 * <p><b>IDOR 防护是本方法的核心</b>：授权的对象是<b>当前登录用户</b>（caller），
	 * 而不是产生 thinking 的 aiclaw。必须校验 caller 是该 thinking 所属房间的成员，
	 * 否则任何登录用户都能通过猜 thinkingId 读取任意房间的思考内容。
	 * 因此这里<b>不能</b>复用 {@code AiclawRoomMembershipService.checkMembership(aiclawUid, roomId)}
	 * —— 那校验的是 aiclaw 的成员身份。</p>
	 *
	 * @param thinkingId thinking ID
	 * @param currentUid 当前登录用户 uid（caller，来自 SA-Token 上下文）
	 * @return content / status / durationMs
	 * @throws BizException 记录不存在 或 当前用户非房间成员（拒绝，且不返回内容）
	 */
	public AiclawThinkingDetailResp reviewThinking(Long thinkingId, Long currentUid) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			throw new BizException("思考记录不存在");
		}

		// IDOR 防护：校验 caller（当前登录用户）是否为该房间成员
		checkCurrentUserMembership(currentUid, thinking.getRoomId());

		return AiclawThinkingDetailResp.builder()
				.content(thinking.getContent())
				.status(thinking.getStatus())
				.durationMs(thinking.getDurationMs())
				.build();
	}

	/**
	 * 校验当前登录用户是否为指定房间成员（群聊看成员列表，私聊看 uid1/uid2）。
	 * 非成员 / 房间数据异常一律以 BizException 拒绝，绝不泄露内容。
	 */
	private void checkCurrentUserMembership(Long currentUid, Long roomId) {
		Room room = roomCache.get(roomId);
		if (room == null) {
			throw new BizException("房间不存在，无法校验成员身份");
		}

		if (room.isRoomGroup()) {
			List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
			if (memberUids == null || !memberUids.contains(currentUid)) {
				throw new BizException("非房间成员，无法查看思考内容");
			}
		} else if (room.isRoomFriend()) {
			RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
			if (roomFriend == null
					|| !(currentUid.equals(roomFriend.getUid1()) || currentUid.equals(roomFriend.getUid2()))) {
				throw new BizException("非房间成员，无法查看思考内容");
			}
		} else {
			// 白名单思维：未知房间类型一律拒绝
			log.warn("reviewThinking: unsupported room type, currentUid={}, roomId={}, roomType={}",
					currentUid, roomId, room.getType());
			throw new BizException("不支持的房间类型，无法查看思考内容");
		}
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
