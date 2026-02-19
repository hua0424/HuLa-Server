package com.luohuo.flex.ws.websocket.ai;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import com.luohuo.flex.im.core.user.dao.UserDao;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.enums.UserTypeEnum;
import com.luohuo.flex.im.service.PushService;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 节点动态注册服务
 * 处理 AI Node 首次连接时的审批流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNodeRegistrationService {

    private final CachePlusOps cachePlusOps;
    private final UserDao userDao;
    private final AiNodeRegistryService aiNodeRegistryService;
    private final AiNodeSessionManager aiNodeSessionManager;
    private final PushService pushService;

    /**
     * 待审批过期时间: 10 分钟
     */
    private static final Duration PENDING_EXPIRE = Duration.ofMinutes(10);

    /**
     * AI 节点注册请求
     * @param nodeId 节点 ID
     * @param ownerId Owner 用户 ID
     * @param nodeName 节点名称 (可选)
     * @param mode 连接模式
     * @return 注册结果
     */
    public RegistrationResult register(String nodeId, Long ownerId, String nodeName, String mode) {
        // 检查是否已有映射
        String existingAiUserId = findAiUserIdByNodeId(nodeId);
        if (existingAiUserId != null) {
            // 已有映射，可能是重连
            return RegistrationResult.alreadyRegistered(Long.parseLong(existingAiUserId));
        }

        // 检查是否有待审批的申请
        String pendingKey = AiNodeCacheKeyBuilder.buildAiNodePending(ownerId, nodeId);
        CacheResult<String> pendingResult = cachePlusOps.get(pendingKey);
        
        if (pendingResult != null && StrUtil.isNotBlank(pendingResult.asString())) {
            // 已有待审批申请
            return RegistrationResult.pending();
        }

        // 创建新的待审批申请
        Map<String, Object> pendingData = new HashMap<>();
        pendingData.put("nodeId", nodeId);
        pendingData.put("ownerId", ownerId);
        pendingData.put("nodeName", nodeName != null ? nodeName : nodeId);
        pendingData.put("mode", mode);
        pendingData.put("createdAt", System.currentTimeMillis());

        cachePlusOps.set(pendingKey, cn.hutool.json.JSONUtil.toJsonStr(pendingData), 
                PENDING_EXPIRE.toMillis() / 1000);

        log.info("[AI-REG] Created pending registration: nodeId={}, ownerId={}", nodeId, ownerId);
        
        // 推送通知给 Owner
        pushService.sendAiNodePendingNotification(ownerId, nodeId, nodeName);
        
        return RegistrationResult.pending();
    }

    /**
     * 审批 AI 节点注册
     * @param ownerId Owner 用户 ID
     * @param nodeId 节点 ID
     * @param approved 是否批准
     * @param aiUserName AI 用户昵称 (批准时必填)
     * @return 审批结果
     */
    public ApprovalResult approve(Long ownerId, String nodeId, boolean approved, String aiUserName) {
        String pendingKey = AiNodeCacheKeyBuilder.buildAiNodePending(ownerId, nodeId);
        CacheResult<String> pendingResult = cachePlusOps.get(pendingKey);

        if (pendingResult == null || StrUtil.isBlank(pendingResult.asString())) {
            return ApprovalResult.fail("No pending registration found");
        }

        Map<String, Object> pendingData = cn.hutool.json.JSONUtil.toBean(
                pendingResult.asString(), Map.class);

        // 验证 ownerId 匹配
        Long pendingOwnerId = ((Number) pendingData.get("ownerId")).longValue();
        if (!Objects.equals(pendingOwnerId, ownerId)) {
            return ApprovalResult.fail("Owner mismatch");
        }

        if (!approved) {
            // 拒绝申请
            cachePlusOps.del(pendingKey);
            log.info("[AI-REG] Registration rejected: nodeId={}, ownerId={}", nodeId, ownerId);
            
            // 通知 AI Node
            notifyAiNode(nodeId, "rejected", null, null);
            
            return ApprovalResult.success(null);
        }

        // 批准申请：创建 BOT 用户
        try {
            Long aiUserId = createBotUser(ownerId, aiUserName, nodeId);
            
            // 建立映射关系
            String aiUserIdStr = String.valueOf(aiUserId);
            cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiUserNode(aiUserId), nodeId);
            cachePlusOps.set(AiNodeCacheKeyBuilder.buildAiUserOwner(aiUserId), ownerId);
            cachePlusOps.sAdd(AiNodeCacheKeyBuilder.buildOwnerNodes(ownerId), nodeId);

            // 删除待审批记录
            cachePlusOps.del(pendingKey);

            log.info("[AI-REG] Registration approved: nodeId={}, ownerId={}, aiUserId={}, aiUserName={}", 
                    nodeId, ownerId, aiUserId, aiUserName);

            // 通知 AI Node
            notifyAiNode(nodeId, "approved", aiUserId, aiUserName);

            // TODO: 创建好友关系 im_user_friend
            
            return ApprovalResult.success(aiUserId);
            
        } catch (Exception e) {
            log.error("[AI-REG] Failed to create bot user: nodeId={}, ownerId={}", nodeId, ownerId, e);
            return ApprovalResult.fail("Failed to create bot user: " + e.getMessage());
        }
    }

    /**
     * 查询待审批列表
     */
    public List<PendingRegistration> listPending(Long ownerId) {
        String pattern = AiNodeCacheKeyBuilder.buildAiNodePendingPattern(ownerId);
        // 使用 Redis 的 SCAN 命令 (这里简化处理)
        List<PendingRegistration> result = new ArrayList<>();
        
        // 直接查询已知模式
        String pendingKey = AiNodeCacheKeyBuilder.buildAiNodePending(ownerId, "*");
        // 实际实现需要使用 SCAN，这里简化
        
        return result;
    }

    /**
     * 检查节点是否已注册
     */
    public boolean isRegistered(String nodeId) {
        return findAiUserIdByNodeId(nodeId) != null;
    }

    /**
     * 获取节点的 AI User ID
     */
    public Long getAiUserId(String nodeId) {
        String aiUserIdStr = findAiUserIdByNodeId(nodeId);
        return aiUserIdStr != null ? Long.parseLong(aiUserIdStr) : null;
    }

    // =================== Private Methods ===================

    private String findAiUserIdByNodeId(String nodeId) {
        // 需要反向查询：从所有 AI_USER_NODE 中查找
        // 简化：先检查 Redis 中是否有对应的在线节点
        CacheResult<Object> nodeResult = cachePlusOps.get(AiNodeCacheKeyBuilder.buildAiNodeOnline(nodeId));
        if (nodeResult != null && nodeResult.getValue() instanceof Map) {
            Object aiUserId = ((Map<?, ?>) nodeResult.getValue()).get("aiUserId");
            if (aiUserId != null) {
                return String.valueOf(aiUserId);
            }
        }
        return null;
    }

    private Long createBotUser(Long ownerId, String aiUserName, String nodeId) {
        // 生成唯一 user_id
        long aiUserId = IdUtil.getSnowflakeNextId();
        
        User bot = new User();
        bot.setUserId(aiUserId);
        bot.setUserType(UserTypeEnum.BOT.getValue());
        bot.setName(aiUserName != null ? aiUserName : "AI Assistant");
        bot.setAccount("ai_" + nodeId);
        bot.setOpenId("ai_" + nodeId);
        bot.setCreateBy(ownerId);
        
        userDao.save(bot);
        
        log.info("[AI-REG] Created bot user: id={}, user_id={}, name={}", 
                bot.getId(), bot.getUserId(), bot.getName());
        
        return bot.getId();
    }

    private void notifyAiNode(String nodeId, String result, Long aiUserId, String aiUserName) {
        String message = String.format(
                "{\"type\":\"ai_approval_result\",\"nodeId\":\"%s\",\"result\":\"%s\",\"aiUserId\":%s,\"aiUserName\":\"%s\",\"timestamp\":%d}",
                nodeId, result, 
                aiUserId != null ? aiUserId : "null",
                aiUserName != null ? aiUserName : "",
                System.currentTimeMillis());
        
        aiNodeSessionManager.sendToNode(nodeId, message).subscribe();
    }

    // =================== Result Classes ===================

    public static class RegistrationResult {
        public static final String STATUS_PENDING = "pending";
        public static final String STATUS_REGISTERED = "registered";
        public static final String STATUS_ALREADY = "already";

        private String status;
        private Long aiUserId;
        private String message;

        private RegistrationResult(String status, Long aiUserId, String message) {
            this.status = status;
            this.aiUserId = aiUserId;
            this.message = message;
        }

        public static RegistrationResult pending() {
            return new RegistrationResult(STATUS_PENDING, null, "Pending approval");
        }

        public static RegistrationResult alreadyRegistered(Long aiUserId) {
            return new RegistrationResult(STATUS_ALREADY, aiUserId, "Already registered");
        }

        public static RegistrationResult registered(Long aiUserId) {
            return new RegistrationResult(STATUS_REGISTERED, aiUserId, "Registered successfully");
        }

        public boolean isPending() { return STATUS_PENDING.equals(status); }
        public boolean isRegistered() { return STATUS_REGISTERED.equals(status); }
        public boolean isAlready() { return STATUS_ALREADY.equals(status); }
    }

    public static class ApprovalResult {
        private boolean success;
        private Long aiUserId;
        private String error;

        private ApprovalResult(boolean success, Long aiUserId, String error) {
            this.success = success;
            this.aiUserId = aiUserId;
            this.error = error;
        }

        public static ApprovalResult success(Long aiUserId) {
            return new ApprovalResult(true, aiUserId, null);
        }

        public static ApprovalResult fail(String error) {
            return new ApprovalResult(false, null, error);
        }
    }

    public static class PendingRegistration {
        private String nodeId;
        private Long ownerId;
        private String nodeName;
        private Long createdAt;

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public Long getOwnerId() { return ownerId; }
        public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public Long getCreatedAt() { return createdAt; }
        public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    }
}
