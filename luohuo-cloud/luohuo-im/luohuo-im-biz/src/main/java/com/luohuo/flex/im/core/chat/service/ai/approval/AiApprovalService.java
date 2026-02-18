package com.luohuo.flex.im.core.chat.service.ai.approval;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.im.core.chat.service.ai.audit.AiAuditLogService;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * AI owner 授权审批服务
 */
@Slf4j
@Service
public class AiApprovalService {

	private final CachePlusOps cachePlusOps;

	@Autowired(required = false)
	private AiAuditLogService aiAuditLogService;

	@Value("${luohuo.ai-node.approval-timeout-seconds:300}")
	private long approvalTimeoutSeconds;

	public AiApprovalService(CachePlusOps cachePlusOps) {
		this.cachePlusOps = cachePlusOps;
	}

	public void ensureAccess(Long aiUserId, Long ownerUid, Long requesterUid, String originalText) {
		if (aiUserId == null || ownerUid == null || requesterUid == null) {
			return;
		}
		if (ownerUid.equals(requesterUid)) {
			return;
		}

		CacheKey grantKey = AiNodeCacheKeyBuilder.buildAiApprovalGrant(aiUserId, requesterUid);
		if (Boolean.TRUE.equals(cachePlusOps.exists(grantKey))) {
			// 授权存在时，顺手清理可能残留的 pending key
			cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalPending(aiUserId, requesterUid));
			return;
		}

		CacheKey pendingKey = AiNodeCacheKeyBuilder.buildAiApprovalPending(aiUserId, requesterUid);
		CacheResult<String> pendingResult = cachePlusOps.get(pendingKey);
		String pendingRequestId = pendingResult == null || pendingResult.getValue() == null
				? null
				: String.valueOf(pendingResult.getValue());
		if (StrUtil.isNotBlank(pendingRequestId) && !"null".equalsIgnoreCase(pendingRequestId)) {
			throw BizException.wrap(403, "AI_APPROVAL_PENDING:" + pendingRequestId);
		}
		if (StrUtil.equalsIgnoreCase(pendingRequestId, "null")) {
			cachePlusOps.del(pendingKey);
		}

		long now = System.currentTimeMillis();
		long ttlMillis = Duration.ofSeconds(Math.max(30, approvalTimeoutSeconds)).toMillis();
		String requestId = "apr_" + IdUtil.fastSimpleUUID();

		AiApprovalRequestRecord requestRecord = AiApprovalRequestRecord.builder()
				.requestId(requestId)
				.aiUserId(aiUserId)
				.ownerUid(ownerUid)
				.requesterUid(requesterUid)
				.status(AiApprovalStatusEnum.PENDING.getCode())
				.role(AiApprovalRoleEnum.VIEWER.getCode())
				.createdAt(now)
				.expireAt(now + ttlMillis)
				.originalText(originalText)
				.finalText(originalText)
				.build();

		CacheKey requestKey = AiNodeCacheKeyBuilder.buildAiApprovalRequest(requestId);
		requestKey.setExpire(Duration.ofMillis(ttlMillis));
		pendingKey.setExpire(Duration.ofMillis(ttlMillis));

		cachePlusOps.set(requestKey, requestRecord);
		cachePlusOps.set(pendingKey, requestId);
		cachePlusOps.sAdd(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid), requestId);

		log.info("AI访问进入审批: requestId={}, aiUserId={}, ownerUid={}, requesterUid={}, ttlSeconds={}",
				requestId, aiUserId, ownerUid, requesterUid, approvalTimeoutSeconds);
		throw BizException.wrap(403, "AI_APPROVAL_REQUIRED:" + requestId);
	}

	public AiApprovalRequestRecord approve(Long ownerUid, String requestId, String role, String rewrittenText) {
		AiApprovalRoleEnum finalRole = AiApprovalRoleEnum.parseOrDefault(role);
		AiApprovalRequestRecord record = decide(ownerUid, requestId, AiApprovalStatusEnum.APPROVED, finalRole.getCode(), null, rewrittenText);
		if (aiAuditLogService != null && record != null) {
			aiAuditLogService.logApproval(record);
		}
		return record;
	}

	public AiApprovalRequestRecord reject(Long ownerUid, String requestId, String reason) {
		String finalReason = StrUtil.blankToDefault(reason, "rejected_by_owner");
		AiApprovalRequestRecord record = decide(ownerUid, requestId, AiApprovalStatusEnum.REJECTED, AiApprovalRoleEnum.VIEWER.getCode(), finalReason, null);
		if (aiAuditLogService != null && record != null) {
			aiAuditLogService.logApproval(record);
		}
		return record;
	}

	/**
	 * 定时扫描并处理超时的审批请求（每分钟执行一次）
	 */
	@Scheduled(fixedRate = 60000)
	public void processTimeoutRequests() {
		Set<String> ownerKeys = cachePlusOps.keys(new AiNodeCacheKeyBuilder.AiApprovalOwnerPending().getPattern());
		if (ownerKeys == null || ownerKeys.isEmpty()) {
			return;
		}
		for (String rawKey : ownerKeys) {
			try {
				String ownerIdStr = rawKey.substring(rawKey.lastIndexOf(":") + 1);
				Long ownerUid = Long.parseLong(ownerIdStr);
				processOwnerTimeoutRequests(ownerUid);
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * 处理单个 owner 的超时请求
	 */
	private void processOwnerTimeoutRequests(Long ownerUid) {
		Set<Object> requestIds = cachePlusOps.sMembers(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid));
		if (requestIds == null || requestIds.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		for (Object obj : requestIds) {
			if (obj == null) {
				continue;
			}
			String requestId = String.valueOf(obj);
			AiApprovalRequestRecord record = getRequestRecord(requestId);
			if (record == null) {
				cachePlusOps.sRem(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid), requestId);
				continue;
			}
			if (!AiApprovalStatusEnum.PENDING.getCode().equals(record.getStatus())) {
				cachePlusOps.sRem(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid), requestId);
				continue;
			}
			// 检查是否超时
			if (record.getExpireAt() != null && record.getExpireAt() < now) {
				log.info("审批请求超时，自动拒绝: requestId={}, aiUserId={}, ownerUid={}, requesterUid={}",
						requestId, record.getAiUserId(), ownerUid, record.getRequesterUid());
				// 执行超时拒绝
				AiApprovalRequestRecord timeoutRecord = timeoutReject(requestId, record);
				// 写入审计日志
				if (aiAuditLogService != null && timeoutRecord != null) {
					aiAuditLogService.logApproval(timeoutRecord);
				}
			}
		}
	}

	/**
	 * 超时自动拒绝
	 */
	private AiApprovalRequestRecord timeoutReject(String requestId, AiApprovalRequestRecord record) {
		record.setStatus(AiApprovalStatusEnum.REJECTED.getCode());
		record.setReason("timeout");
		record.setDecidedAt(System.currentTimeMillis());
		record.setDecidedBy(record.getOwnerUid());

		// 清理 Redis 缓存
		cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalPending(record.getAiUserId(), record.getRequesterUid()));
		cachePlusOps.sRem(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(record.getOwnerUid()), requestId);
		cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalRewriteNext(record.getAiUserId(), record.getRequesterUid()));
		cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiApprovalRequest(requestId), record);

		return record;
	}

	public List<AiApprovalRequestRecord> listPending(Long ownerUid, Long aiUserId, Integer limit) {
		Set<Object> requestIds = cachePlusOps.sMembers(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid));
		if (requestIds == null || requestIds.isEmpty()) {
			return List.of();
		}

		List<AiApprovalRequestRecord> result = new ArrayList<>();
		for (Object obj : requestIds) {
			if (obj == null) {
				continue;
			}
			String requestId = String.valueOf(obj);
			AiApprovalRequestRecord record = getRequestRecord(requestId);
			if (record == null || !AiApprovalStatusEnum.PENDING.getCode().equals(record.getStatus())) {
				cachePlusOps.sRem(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid), requestId);
				continue;
			}
			if (aiUserId != null && !aiUserId.equals(record.getAiUserId())) {
				continue;
			}
			result.add(record);
		}

		result.sort(Comparator.comparing(AiApprovalRequestRecord::getCreatedAt, Comparator.nullsLast(Long::compareTo)).reversed());
		if (limit != null && limit > 0 && result.size() > limit) {
			return result.subList(0, limit);
		}
		return result;
	}

	/**
	 * 消费一次性的 owner 改写文本（用于下一次请求投递）
	 */
	public String consumeRewriteText(Long aiUserId, Long requesterUid) {
		CacheKey rewriteKey = AiNodeCacheKeyBuilder.buildAiApprovalRewriteNext(aiUserId, requesterUid);
		CacheResult<String> rewriteResult = cachePlusOps.get(rewriteKey);
		String rewrittenText = rewriteResult == null ? null : rewriteResult.asString();
		if (StrUtil.isBlank(rewrittenText) || StrUtil.equalsIgnoreCase(rewrittenText, "null")) {
			cachePlusOps.del(rewriteKey);
			return null;
		}
		cachePlusOps.del(rewriteKey);
		return rewrittenText;
	}

	private AiApprovalRequestRecord decide(Long ownerUid, String requestId, AiApprovalStatusEnum status, String role, String reason, String rewrittenText) {
		AiApprovalRequestRecord record = getRequestRecord(requestId);
		if (record == null) {
			throw BizException.wrap(404, "AI_APPROVAL_REQUEST_NOT_FOUND");
		}
		if (!ownerUid.equals(record.getOwnerUid())) {
			throw BizException.wrap(403, "AI_APPROVAL_FORBIDDEN");
		}
		if (!AiApprovalStatusEnum.PENDING.getCode().equals(record.getStatus())) {
			return record;
		}

		record.setStatus(status.getCode());
		record.setRole(role);
		record.setReason(reason);
		record.setDecidedAt(System.currentTimeMillis());
		record.setDecidedBy(ownerUid);

		// 处理改写文本
		if (status == AiApprovalStatusEnum.APPROVED && StrUtil.isNotBlank(rewrittenText)) {
			record.setFinalText(rewrittenText);
		}

		if (status == AiApprovalStatusEnum.APPROVED) {
			AiApprovalGrantRecord grant = AiApprovalGrantRecord.builder()
					.aiUserId(record.getAiUserId())
					.ownerUid(record.getOwnerUid())
					.requesterUid(record.getRequesterUid())
					.role(role)
					.approvedAt(record.getDecidedAt())
					.approvedBy(ownerUid)
					.build();
			cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiApprovalGrant(record.getAiUserId(), record.getRequesterUid()), grant);

			// owner 改写文本仅作用于“下一次”请求投递，消费后删除
			if (StrUtil.isNotBlank(record.getFinalText()) && !StrUtil.equals(record.getFinalText(), record.getOriginalText())) {
				cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiApprovalRewriteNext(record.getAiUserId(), record.getRequesterUid()), record.getFinalText());
			} else {
				cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalRewriteNext(record.getAiUserId(), record.getRequesterUid()));
			}
		} else {
			cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalGrant(record.getAiUserId(), record.getRequesterUid()));
			cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalRewriteNext(record.getAiUserId(), record.getRequesterUid()));
		}

		cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiApprovalRequest(requestId), record);
		cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalPending(record.getAiUserId(), record.getRequesterUid()));
		cachePlusOps.sRem(AiNodeCacheKeyBuilder.buildAiApprovalOwnerPending(ownerUid), requestId);
		return record;
	}

	private AiApprovalRequestRecord getRequestRecord(String requestId) {
		if (StrUtil.isBlank(requestId)) {
			return null;
		}
		CacheResult<AiApprovalRequestRecord> result = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiApprovalRequest(requestId));
		return result == null ? null : result.getValue();
	}
}
