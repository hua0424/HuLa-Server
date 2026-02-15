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
}
