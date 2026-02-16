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
	 * AI 回复去重（requestId）
	 */
	public static CacheKey buildAiReplyDedup(String requestId) {
		return new AiReplyDedup().key(requestId);
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
}
