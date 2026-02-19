package com.luohuo.flex.router;

import com.luohuo.basic.model.cache.CacheKey;
import com.luohuo.basic.model.cache.CacheKeyBuilder;

import java.time.Duration;

/**
 * AI 节点缓存键
 */
public class AiNodeCacheKeyBuilder {

	private static final Duration ONLINE_EXPIRE = Duration.ofSeconds(120);

	/**
	 * 节点在线信息
	 */
	public static CacheKey buildAiNodeOnline(String nodeId) {
		return new AiNodeOnline().key(nodeId);
	}

	/**
	 * AI 用户到节点映射
	 */
	public static CacheKey buildAiUserNode(Long aiUserId) {
		return new AiUserNode().key(aiUserId);
	}

	/**
	 * Owner 到节点集合
	 */
	public static CacheKey buildOwnerNodes(Long ownerId) {
		return new OwnerNodes().key(ownerId);
	}

	/**
	 * AI 用户到 owner 映射
	 */
	public static CacheKey buildAiUserOwner(Long aiUserId) {
		return new AiUserOwner().key(aiUserId);
	}

	/**
	 * AI 访问授权（aiUserId + requesterUid）
	 */
	public static CacheKey buildAiApprovalGrant(Long aiUserId, Long requesterUid) {
		return new AiApprovalGrant().key(aiUserId, requesterUid);
	}

	/**
	 * AI 访问审批中的请求（aiUserId + requesterUid -> requestId）
	 */
	public static CacheKey buildAiApprovalPending(Long aiUserId, Long requesterUid) {
		return new AiApprovalPending().key(aiUserId, requesterUid);
	}

	/**
	 * AI 审批请求详情（requestId -> record）
	 */
	public static CacheKey buildAiApprovalRequest(String requestId) {
		return new AiApprovalRequest().key(requestId);
	}

	/**
	 * owner 维度待审批请求集合
	 */
	public static CacheKey buildAiApprovalOwnerPending(Long ownerId) {
		return new AiApprovalOwnerPending().key(ownerId);
	}

	/**
	 * owner 审批改写文本（仅下一次请求生效）
	 */
	public static CacheKey buildAiApprovalRewriteNext(Long aiUserId, Long requesterUid) {
		return new AiApprovalRewriteNext().key(aiUserId, requesterUid);
	}

	/**
	 * AI 回复去重（requestId）
	 */
	public static CacheKey buildAiReplyDedup(String requestId) {
		return new AiReplyDedup().key(requestId);
	}

	/**
	 * AI 节点待审批注册（ownerId + nodeId）
	 */
	public static CacheKey buildAiNodePending(Long ownerId, String nodeId) {
		return new AiNodePending().key(ownerId, nodeId);
	}

	/**
	 * AI 节点待审批注册 pattern（ownerId 开头的所有 key）
	 */
	public static String buildAiNodePendingPattern(Long ownerId) {
		return "luohuo:router:ai-node-pending:" + ownerId + ":*";
	}

	// ==================== 限流相关 Keys ====================

	/**
	 * 限流：requester -> node 每分钟请求数 (滑动窗口)
	 * Redis key: ai:rate:{requesterUid}:{nodeId}:min
	 */
	public static CacheKey buildAiRateLimitMin(Long requesterUid, String nodeId) {
		return new AiRateLimitMin().key(requesterUid, nodeId);
	}

	/**
	 * 限流：requester 并发 in-flight 请求数
	 * Redis key: ai:inflight:{requesterUid}
	 */
	public static CacheKey buildAiInflightRequester(Long requesterUid) {
		return new AiInflightRequester().key(requesterUid);
	}

	/**
	 * 限流：node 全局并发 in-flight 请求数
	 * Redis key: ai:inflight:node:{nodeId}
	 */
	public static CacheKey buildAiInflightNode(String nodeId) {
		return new AiInflightNode().key(nodeId);
	}

	// ==================== 限流 Key 实现 ====================

	/**
	 * 每分钟限流计数器（使用 Redis String + INCR + EXPIRE）
	 */
	public static class AiRateLimitMin implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "ai";
		}

		@Override
		public String getModular() {
			return "rate";
		}

		@Override
		public String getTable() {
			return "min";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.number;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofSeconds(60);
		}
	}

	/**
	 * requester 并发 in-flight 计数器
	 */
	public static class AiInflightRequester implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "ai";
		}

		@Override
		public String getModular() {
			return "inflight";
		}

		@Override
		public String getTable() {
			return "requester";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.number;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofSeconds(300);
		}
	}

	/**
	 * node 全局并发 in-flight 计数器
	 */
	public static class AiInflightNode implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "ai";
		}

		@Override
		public String getModular() {
			return "inflight";
		}

		@Override
		public String getTable() {
			return "node";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.number;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofSeconds(300);
		}
	}

	public static class AiNodeOnline implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-node";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.obj;
		}

		@Override
		public Duration getExpire() {
			return ONLINE_EXPIRE;
		}
	}

	public static class AiUserNode implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-user-node";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return ONLINE_EXPIRE;
		}
	}

	public static class OwnerNodes implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-owner-nodes";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofSeconds(-1);
		}
	}

	public static class AiUserOwner implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-user-owner";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.number;
		}

		@Override
		public Duration getExpire() {
			return ONLINE_EXPIRE;
		}
	}

	public static class AiApprovalGrant implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-approval-grant";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.obj;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofDays(180);
		}
	}

	public static class AiApprovalPending implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-approval-pending";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofMinutes(5);
		}
	}

	public static class AiApprovalRequest implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-approval-request";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.obj;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofDays(7);
		}
	}

	public static class AiApprovalOwnerPending implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-approval-owner-pending";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofDays(7);
		}
	}

	public static class AiApprovalRewriteNext implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-approval-rewrite-next";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofDays(1);
		}
	}

	public static class AiReplyDedup implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-reply-dedup";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.string;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofDays(1);
		}
	}

	/**
	 * AI 节点待审批注册
	 */
	public static class AiNodePending implements CacheKeyBuilder {
		@Override
		public String getPrefix() {
			return "luohuo";
		}

		@Override
		public String getModular() {
			return "router";
		}

		@Override
		public String getTable() {
			return "ai-node-pending";
		}

		@Override
		public ValueType getValueType() {
			return ValueType.obj;
		}

		@Override
		public Duration getExpire() {
			return Duration.ofMinutes(10);
		}
	}
}
