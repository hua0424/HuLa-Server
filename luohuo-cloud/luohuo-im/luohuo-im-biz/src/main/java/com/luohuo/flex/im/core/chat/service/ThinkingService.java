package com.luohuo.flex.im.core.chat.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.res.CursorPageBaseResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

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

	/**
	 * REQ-004 [S7] 安全：reviewThinking 的<b>统一拒绝</b>消息。
	 *
	 * <p>所有拒绝分支（thinkingId 不存在 / 房间不存在 / 当前用户非成员 / 不支持的房间类型）
	 * 必须抛出<b>完全相同</b>的异常（同 message + 同 code，code 由 {@link BizException#BizException(String)}
	 * 统一固定为 {@code SYSTEM_BUSY}），使调用方无法区分"记录不存在"与"存在但无权查看"，
	 * 从而消除 thinkingId 枚举预言机（enumeration oracle）信息泄露。真实拒绝原因仅记录在服务端日志中。</p>
	 */
	private static final String REVIEW_REJECTED_MESSAGE = "思考记录不存在或无权查看";

	/**
	 * 房间 thinking 归档列表分页默认条数与硬上限。
	 */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 50;

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
	 * @throws BizException 任何拒绝（不存在 / 非成员 / 房间异常 / 类型不支持）均抛出<b>统一</b>异常，
	 *                      调用方无法区分原因（见 {@link #REVIEW_REJECTED_MESSAGE}）；真实原因仅记日志。
	 */
	public AiclawThinkingDetailResp reviewThinking(Long thinkingId, Long currentUid) {
		AiclawThinking thinking = thinkingMapper.selectById(thinkingId);
		if (thinking == null) {
			// 真实原因仅记日志，对外抛统一异常以消除枚举预言机
			log.warn("reviewThinking rejected: thinking not found, thinkingId={}, currentUid={}",
					thinkingId, currentUid);
			throw new BizException(REVIEW_REJECTED_MESSAGE);
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
	 * 按房间查询 thinking 归档列表（元数据 only，最近优先，游标翻页）。
	 *
	 * <p>供客户端 thinking 抽屉懒加载历史。要点：</p>
	 * <ul>
	 *   <li><b>成员闸门</b>：复用 {@link #checkCurrentUserMembership(Long, Long)}，非成员抛统一
	 *       {@code BizException}，与 {@code reviewThinking} 同一 IDOR 防护；授权在查询之前。</li>
	 *   <li><b>元数据 only</b>：不返回全文 content（单行可达 200KB），只回 id/aiclawUid/triggerMsgId/
	 *       status/durationMs/hasResponse/createTime；全文由 {@link #reviewThinking} 按需拉取。</li>
	 *   <li><b>keyset 倒序</b>：按 id DESC（最近优先），游标为上一页最后一条 id；排除 is_del。</li>
	 *   <li><b>status 全区间</b>：进行中/成功/错误/超时/超长截断均列出。</li>
	 * </ul>
	 *
	 * @param roomId     房间 ID
	 * @param callerUid  当前登录用户 uid（授权主体）
	 * @param cursor     游标（上一页最后一条 id 的字符串），首页传 null/空；非法游标宽松降级为首页
	 * @param pageSize   每页条数，null/&lt;=0 取默认 {@value #DEFAULT_PAGE_SIZE}，硬上限 {@value #MAX_PAGE_SIZE}
	 * @return 游标翻页结果（元数据列表 + 下一页游标 + 是否最后一页；total 不计算，留 null）
	 * @throws BizException caller 非该房间成员时抛统一拒绝异常
	 */
	public CursorPageBaseResp<AiclawThinkingListItemResp> listThinkingByRoom(
			Long roomId, Long callerUid, String cursor, Integer pageSize) {
		int size = (pageSize == null || pageSize <= 0) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

		// 成员闸门：授权在查询之前（与 reviewThinking 同一 IDOR 防护）
		checkCurrentUserMembership(callerUid, roomId);

		Long cursorId = null;
		if (StrUtil.isNotBlank(cursor)) {
			try {
				cursorId = Long.parseLong(cursor.trim());
			} catch (NumberFormatException e) {
				// 宽松处理：游标非法 → 当作首页
				cursorId = null;
			}
		}

		// 多取一行以判定是否最后一页
		List<AiclawThinking> rows = thinkingMapper.selectThinkingListByRoom(roomId, cursorId, size + 1);
		boolean isLast = rows.size() <= size;
		List<AiclawThinking> pageRows = isLast ? rows : rows.subList(0, size);

		List<AiclawThinkingListItemResp> list = pageRows.stream()
				.map(t -> AiclawThinkingListItemResp.builder()
						.id(t.getId())
						.aiclawUid(t.getAiclawUid())
						.triggerMsgId(t.getTriggerMsgId())
						.status(t.getStatus())
						.durationMs(t.getDurationMs())
						.hasResponse(t.getHasResponse())
						.createTime(t.getCreateTime())
						.build())
				.collect(Collectors.toList());

		String nextCursor = pageRows.isEmpty() ? null : String.valueOf(pageRows.get(pageRows.size() - 1).getId());

		CursorPageBaseResp<AiclawThinkingListItemResp> resp = new CursorPageBaseResp<>();
		resp.setList(list);
		resp.setCursor(nextCursor);
		resp.setIsLast(isLast);
		// total 不计算，留 null
		return resp;
	}

	/**
	 * 校验当前登录用户是否为指定房间成员（群聊看成员列表，私聊看 uid1/uid2）。
	 *
	 * <p>非成员 / 房间数据异常一律拒绝，绝不泄露内容。所有拒绝分支抛出<b>统一</b>异常
	 * （{@link #REVIEW_REJECTED_MESSAGE}），与"记录不存在"不可区分；每个分支的真实原因仅
	 * 通过 {@code log.warn} 记录在服务端，供调试排查，不会到达调用方。</p>
	 */
	private void checkCurrentUserMembership(Long currentUid, Long roomId) {
		Room room = roomCache.get(roomId);
		if (room == null) {
			log.warn("reviewThinking rejected: room not found, roomId={}, currentUid={}", roomId, currentUid);
			throw new BizException(REVIEW_REJECTED_MESSAGE);
		}

		if (room.isRoomGroup()) {
			List<Long> memberUids = groupMemberCache.getMemberUidList(roomId);
			if (!memberUids.contains(currentUid)) {
				log.warn("reviewThinking rejected: not a group member, roomId={}, currentUid={}", roomId, currentUid);
				throw new BizException(REVIEW_REJECTED_MESSAGE);
			}
		} else if (room.isRoomFriend()) {
			RoomFriend roomFriend = roomFriendDao.getByRoomId(roomId);
			if (roomFriend == null
					|| !(currentUid.equals(roomFriend.getUid1()) || currentUid.equals(roomFriend.getUid2()))) {
				log.warn("reviewThinking rejected: not a friend-room participant, roomId={}, currentUid={}",
						roomId, currentUid);
				throw new BizException(REVIEW_REJECTED_MESSAGE);
			}
		} else {
			// 白名单思维：未知房间类型一律拒绝
			log.warn("reviewThinking rejected: unsupported room type, currentUid={}, roomId={}, roomType={}",
					currentUid, roomId, room.getType());
			throw new BizException(REVIEW_REJECTED_MESSAGE);
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
