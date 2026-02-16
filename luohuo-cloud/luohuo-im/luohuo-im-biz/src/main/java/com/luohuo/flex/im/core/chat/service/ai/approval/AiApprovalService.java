package com.luohuo.flex.im.core.chat.service.ai.approval;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.basic.exception.BizException;
import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@RequiredArgsConstructor
public class AiApprovalService {

	private final CachePlusOps cachePlusOps;

	@Value("${luohuo.ai-node.approval-timeout-seconds:300}")
	private long approvalTimeoutSeconds;

	public void ensureAccess(Long aiUserId, Long ownerUid, Long requesterUid) {
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

	public AiApprovalRequestRecord approve(Long ownerUid, String requestId, String role) {
		AiApprovalRoleEnum finalRole = AiApprovalRoleEnum.parseOrDefault(role);
		return decide(ownerUid, requestId, AiApprovalStatusEnum.APPROVED, finalRole.getCode(), null);
	}

	public AiApprovalRequestRecord reject(Long ownerUid, String requestId, String reason) {
		String finalReason = StrUtil.blankToDefault(reason, "rejected_by_owner");
		return decide(ownerUid, requestId, AiApprovalStatusEnum.REJECTED, AiApprovalRoleEnum.VIEWER.getCode(), finalReason);
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

	private AiApprovalRequestRecord decide(Long ownerUid, String requestId, AiApprovalStatusEnum status, String role, String reason) {
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
		} else {
			cachePlusOps.del(AiNodeCacheKeyBuilder.buildAiApprovalGrant(record.getAiUserId(), record.getRequesterUid()));
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
