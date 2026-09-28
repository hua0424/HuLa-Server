package com.luohuo.flex.im.core.chat.service;

import cn.hutool.core.util.IdUtil;
import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.common.config.AiclawProperties;
import com.luohuo.flex.im.core.user.service.impl.PushService;
import com.luohuo.flex.model.entity.WsBaseResp;
import com.luohuo.flex.model.entity.ws.WSThinkingEnd;
import com.luohuo.basic.exception.BizException;
import com.luohuo.flex.im.core.chat.dao.MessageDao;
import com.luohuo.flex.im.core.chat.dao.RoomFriendDao;
import com.luohuo.flex.im.core.user.dao.AiclawDao;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.im.core.chat.mapper.AiclawThinkingMapper;
import com.luohuo.flex.im.core.chat.service.cache.GroupMemberCache;
import com.luohuo.flex.im.core.chat.service.cache.RoomCache;
import com.luohuo.flex.im.domain.entity.Aiclaw;
import com.luohuo.flex.im.domain.entity.AiclawThinking;
import com.luohuo.flex.im.domain.entity.Message;
import com.luohuo.flex.im.domain.entity.Room;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.domain.entity.RoomFriend;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingDetailResp;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawThinkingListItemResp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DuplicateKeyException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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

	@Resource
	private AiclawThinkingMapper thinkingMapper;

	@Resource
	private AiclawDao aiclawDao;

	@Resource
	private UserDao userDao;

	@Resource
	private AiclawProperties aiclawProperties;

	@Resource
	private PushService pushService;

	private long cleanupCursor;

	@Resource
	private MessageDao messageDao;

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
		Long tenantId = requireTenant();
		requireActiveAgent(aiclawUid, roomId, tenantId);
		if (triggerMsgId != null) {
			Message trigger = messageDao.getById(triggerMsgId);
			if (trigger == null || !roomId.equals(trigger.getRoomId())
					|| !tenantId.equals(trigger.getTenantId())) {
				throw new BizException("触发消息不属于当前房间");
			}
		}
		Long thinkingId = IdUtil.getSnowflakeNextId();
		if (thinkingMapper.insertThinking(thinkingId, tenantId, aiclawUid, roomId, triggerMsgId) != 1) {
			throw new BizException("思考记录创建失败");
		}
		return thinkingId;
	}

	public record StartReceipt(Long thinkingId, boolean replayed, Integer status, String errorCode, boolean ready) {}

	/** Database uniqueness elects a single START across WS/IM nodes and process restarts. */
	public StartReceipt create(Long actor, Long roomId, Long triggerMsgId, String clientRunId) {
		if (clientRunId == null) return new StartReceipt(create(actor, roomId, triggerMsgId), false, 0, null, true); // Legacy START.
		if (clientRunId.isBlank() || clientRunId.length() > 128 || !clientRunId.equals(clientRunId.strip()))
			throw new BizException("thinking_run_invalid");
		Long tenantId = requireTenant();
		requireActiveAgent(actor, roomId, tenantId);
		AiclawThinking previous = thinkingMapper.selectByRun(tenantId, actor, clientRunId);
		if (previous != null) return new StartReceipt(matchingStart(previous, roomId, triggerMsgId, clientRunId), true,
				previous.getStatus(), previous.getErrorCode(), Boolean.TRUE.equals(previous.getStartReady()));
		if (triggerMsgId != null) {
			Message trigger = messageDao.getById(triggerMsgId);
			if (trigger == null || !roomId.equals(trigger.getRoomId()) || !tenantId.equals(trigger.getTenantId())) {
				throw new BizException("触发消息不属于当前房间");
			}
		}
		Long id = IdUtil.getSnowflakeNextId();
		try {
			if (thinkingMapper.insertWithRun(id, tenantId, actor, roomId, triggerMsgId, clientRunId) != 1) {
				throw new BizException("思考记录创建失败");
			}
			return new StartReceipt(id, false, 0, null, false);
		} catch (DuplicateKeyException duplicate) {
			// A concurrent START won the unique (tenant, actor, run) key; unrelated key collisions still fail.
			previous = thinkingMapper.selectByRun(tenantId, actor, clientRunId);
			if (previous == null) throw duplicate;
			return new StartReceipt(matchingStart(previous, roomId, triggerMsgId, clientRunId), true,
				previous.getStatus(), previous.getErrorCode(), Boolean.TRUE.equals(previous.getStartReady()));
		}
	}

	private Long matchingStart(AiclawThinking previous, Long roomId, Long triggerMsgId, String clientRunId) {
		if (Boolean.TRUE.equals(previous.getIsDel()) || !roomId.equals(previous.getRoomId())
				|| !Objects.equals(triggerMsgId, previous.getTriggerMsgId())
				|| !clientRunId.equals(previous.getClientRunId())) {
			throw new BizException("thinking_run_conflict");
		}
		return previous.getId();
	}

	/** Signal START readiness only after the first authorized room push was scheduled. */
	public boolean markStartReady(Long id, Long actor, Long roomId, String clientRunId) {
		Long tenantId = requireTenant();
		requireActiveAgent(actor, roomId, tenantId);
		if (clientRunId == null || clientRunId.isBlank()) throw new BizException("thinking_run_invalid");
		return thinkingMapper.markStartReady(id, tenantId, actor, roomId, clientRunId) == 1;
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
	public boolean finalize(Long thinkingId, Long actor, Long roomId, String content,
			Integer durationMs, String status, String error) {
		return finalize(thinkingId, actor, roomId, content, durationMs, status, error, null);
	}

	public boolean finalize(Long thinkingId, Long actor, Long roomId, String content,
			Integer durationMs, String status, String error, String clientRunId) {
		Long tenantId = requireTenant();
		requireActiveAgent(actor, roomId, tenantId);
		if (!"complete".equals(status) && !"error".equals(status)) {
			throw new BizException("未知 thinking 状态");
		}
		String safe = content == null ? "" : content;
		boolean truncated = safe.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES;
		String stored = truncated ? truncateUtf8(safe, MAX_CONTENT_BYTES) : safe;
		boolean isError = "error".equals(status);
		// Only the explicitly authenticated service-timeout path may set status=3.
		int mappedStatus = isError ? 2 : (truncated ? 4 : 1);
		String storedError = isError ? truncateErrorCode(error) : null;
		return finish(thinkingId, tenantId, actor, roomId, stored, durationMs, mappedStatus, storedError, clientRunId, true);
	}

	/** Return true only for the one successful status=0 -> terminal persistence winner. */
	public boolean markError(Long thinkingId, Long actor, Long roomId, String error, boolean timeout) {
		Long tenantId = requireTenant();
		// timeout is a service-authorized closure; the controller must never expose this flag to an agent.
		if (!timeout) {
			requireActiveAgent(actor, roomId, tenantId);
		}
		return finish(thinkingId, tenantId, actor, roomId, "", null,
				timeout ? 3 : 2, truncateErrorCode(error));
	}

	/** Recover stuck records on every IM node; the tenant/actor/room/status CAS elects exactly one broadcaster. */
	@Scheduled(fixedDelay = 60000)
	public void cleanupActiveThinkings() {
		ContextUtil.remove(); // Scheduled work has no request principal or implicit tenant.
		try {
			long timeoutMs = aiclawProperties.getThinking().getTimeoutMs();
			if (timeoutMs <= 0) {
				log.warn("thinking cleanup skipped: invalid timeout-ms");
				return;
			}
			LocalDateTime cutoff = LocalDateTime.now().minus(java.time.Duration.ofMillis(timeoutMs));
			List<AiclawThinking> candidates = thinkingMapper.selectCleanupCandidates(cutoff, cleanupCursor);
			if (candidates.isEmpty()) {
				cleanupCursor = 0; // Wrap keyset on the next tick; never hold a DB or distributed lock.
				return;
			}
			for (AiclawThinking row : candidates) {
				cleanupCursor = row.getId();
				try {
					if (row.getTenantId() == null || row.getTenantId() <= 0) {
						throw new BizException("思考记录缺少租户");
					}
					// 0 denotes the internal service, never the aiclaw actor (which comes only from the DB row).
					ContextUtil.setUserId(0L);
					ContextUtil.setTenantId(row.getTenantId());
					boolean timeout = row.getCreateTime() != null && !row.getCreateTime().isAfter(cutoff);
					String reason = timeout ? "timeout" : "authorization_revoked";
					if (!finish(row.getId(), row.getTenantId(), row.getAiclawUid(), row.getRoomId(), "", null,
							timeout ? 3 : 2, reason)) continue;
					List<Long> recipients = thinkingMapper.selectCurrentMemberUids(row.getRoomId(), row.getTenantId());
					if (!recipients.isEmpty()) {
						// ponytail: existing MQ retry is in-memory after CAS; a durable outbox is needed
						// only if verified crash-loss of terminal notifications requires recovery.
						WsBaseResp<WSThinkingEnd> push = new WsBaseResp<>();
						push.setType("thinkingEnd");
						push.setData(WSThinkingEnd.builder().thinkingId(String.valueOf(row.getId()))
								.roomId(String.valueOf(row.getRoomId())).status("error").error(reason).build());
						pushService.sendPushMsg(push, recipients, 0L);
					}
				} catch (RuntimeException e) {
					// No message contents, token, or exception payload in logs.
					log.warn("thinking cleanup row failed: id={}, kind={}", row.getId(), e.getClass().getSimpleName());
				} finally {
					ContextUtil.remove();
				}
			}
		} catch (RuntimeException e) {
			log.warn("thinking cleanup scan failed: kind={}", e.getClass().getSimpleName());
		} finally {
			ContextUtil.remove();
		}
	}

	private boolean finish(Long id, Long tenantId, Long actor, Long roomId,
			String content, Integer duration, int status, String error) {
		return finish(id, tenantId, actor, roomId, content, duration, status, error, null, false);
	}

	private boolean finish(Long id, Long tenantId, Long actor, Long roomId,
			String content, Integer duration, int status, String error, String clientRunId, boolean agentEnd) {
		AiclawThinking owned = thinkingMapper.selectOwned(id, tenantId, actor, roomId);
		if (owned == null || (clientRunId != null && !clientRunId.equals(owned.getClientRunId()))) {
			throw new BizException("思考记录不存在或无权修改");
		}
		if (agentEnd && owned.getClientRunId() != null && !Boolean.TRUE.equals(owned.getStartReady())) {
			throw new BizException("thinking_start_pending");
		}
		if (Integer.valueOf(0).equals(owned.getStatus())) {
			if (thinkingMapper.finalizeActive(id, tenantId, actor, roomId, content, duration, status, error) == 1) {
				return true;
			}
			// CAS loser: reread the persisted winner, never report a second broadcast winner.
			owned = thinkingMapper.selectOwned(id, tenantId, actor, roomId);
		}
		if (owned != null && Objects.equals(owned.getStatus(), status)
				&& Objects.equals(owned.getContent(), content)
				&& Objects.equals(owned.getDurationMs(), duration)
				&& Objects.equals(owned.getErrorCode(), error)) {
			return false;
		}
		throw new BizException("思考记录已结束，内容或状态冲突");
	}

	private Long requireTenant() {
		Long tenantId = ContextUtil.getTenantId();
		if (tenantId == null || tenantId <= 0) {
			throw new BizException("缺少可信租户身份");
		}
		return tenantId;
	}

	public void requireActiveAgent(Long actor, Long roomId, Long tenantId) {
		if (actor == null || roomId == null || !Objects.equals(tenantId, requireTenant())) {
			throw new BizException("助理身份或房间无效");
		}
		User user = userDao.getById(actor); // Fresh DB state: a stale cached user cannot keep a disabled agent writable.
		Aiclaw aiclaw = aiclawDao.getByUid(actor);
		if (user == null || !UserTypeEnum.AICLAW.getValue().equals(user.getUserType())
				|| !tenantId.equals(user.getTenantId()) || !Integer.valueOf(0).equals(user.getState())
				|| aiclaw == null || !tenantId.equals(aiclaw.getTenantId())
				|| !Integer.valueOf(1).equals(aiclaw.getAuthStatus()) || aiclaw.getDeactivatedAt() != null) {
			throw new BizException("助理身份未激活或无权写入");
		}
		if (!Integer.valueOf(1).equals(thinkingMapper.isCurrentMember(actor, roomId, tenantId))) {
			throw new BizException("非房间成员，无法写入");
		}
	}

	/**
	 * 按 ID 查询 thinking 记录
	 */
	public AiclawThinking getById(Long thinkingId) {
		return thinkingMapper.selectInTenant(thinkingId, requireTenant());
	}

	/** Reconcile only this persisted actor/room/tenant, never a room-local latest record. */
	public boolean isOwnedTerminal(Long thinkingId, Long actor, Long roomId) {
		AiclawThinking owned = thinkingMapper.selectOwned(thinkingId, requireTenant(), actor, roomId);
		return owned != null && owned.getStatus() != null && owned.getStatus() != 0;
	}

	/** Live DB members, excluding disabled/deleted identities; the caller is authorized separately. */
	public List<Long> currentMemberUids(Long roomId, Long tenantId) {
		if (roomId == null || roomId <= 0 || !Objects.equals(tenantId, requireTenant())) {
			throw new BizException("房间或租户无效");
		}
		return thinkingMapper.selectCurrentMemberUids(roomId, tenantId);
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
	 * 按触发消息 ID 批量反查 thinking 元数据（元数据 only，issue #180）。
	 *
	 * <p>供客户端一次性反查一批已渲染消息各自对应的 thinking 元数据。要点：</p>
	 * <ul>
	 *   <li><b>成员闸门</b>：复用 {@link #checkCurrentUserMembership(Long, Long)}，非成员抛统一
	 *       {@code BizException}，与 {@code reviewThinking} 同一 IDOR 防护；授权在查询之前。</li>
	 *   <li><b>元数据 only</b>：不返回全文 content（单行可达 200KB），只回 id/aiclawUid/triggerMsgId/
	 *       status/durationMs/hasResponse/createTime；全文由 {@link #reviewThinking} 按需拉取。</li>
	 *   <li><b>id 升序</b>：mapper 按 id ASC 返回（雪花 ID 近似时间序）；同一 triggerMsgId 可有多条
	 *       （多 aiclaw），均返回。</li>
	 * </ul>
	 *
	 * @param roomId        房间 ID
	 * @param callerUid     当前登录用户 uid（授权主体）
	 * @param triggerMsgIds 触发消息 ID 列表（DTO 层已限制非空 + 上限 100）
	 * @return 元数据列表（按 id 升序）
	 * @throws BizException caller 非该房间成员时抛统一拒绝异常
	 */
	public List<AiclawThinkingListItemResp> listThinkingByTriggerMsgIds(
			Long roomId, Long callerUid, List<Long> triggerMsgIds) {
		// 成员闸门：授权在查询之前（与 reviewThinking 同一 IDOR 防护）
		checkCurrentUserMembership(callerUid, roomId);
		List<AiclawThinking> rows = thinkingMapper.selectThinkingListByTriggerMsgIds(roomId, triggerMsgIds);
		return rows.stream()
				.map(t -> AiclawThinkingListItemResp.builder()
						.id(t.getId())
						.aiclawUid(t.getAiclawUid())
						.triggerMsgId(t.getTriggerMsgId())
						.status(t.getStatus())
						.durationMs(t.getDurationMs())
						.hasResponse(t.getHasResponse())
						.createTime(t.getCreateTime())
						.build())
				.collect(java.util.stream.Collectors.toList());
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
