package com.luohuo.flex.im.controller.chat;

import cn.hutool.core.util.StrUtil;
import com.luohuo.flex.im.core.chat.service.ai.approval.AiApprovalGrantRecord;
import com.luohuo.flex.im.core.chat.service.ai.approval.AiApprovalService;
import com.luohuo.flex.im.core.user.service.FriendService;
import com.luohuo.flex.im.domain.entity.User;
import com.luohuo.flex.im.service.PushService;
import com.luohuo.flex.router.AiNodeCacheKeyBuilder;
import com.luohuo.flex.ws.websocket.ai.AiNodeRegistrationService;
import com.luohuo.flex.ws.websocket.ai.AiNodeRegistrationService.*;
import com.luohuo.basic.cache.redis2.CacheResult;
import com.luohuo.basic.cache.repository.CachePlusOps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * AI 节点注册审批 API
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/ai/node")
@RequiredArgsConstructor
public class AiNodeRegistrationController {

    private final AiNodeRegistrationService registrationService;
    private final FriendService friendService;
    private final PushService pushService;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 查询待审批列表
     */
    @GetMapping("/pending")
    public List<PendingRegistration> listPending(@RequestParam Long ownerId) {
        // 从 Redis 扫描待审批记录
        String pattern = "luohuo:router:ai-node-pending:" + ownerId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        
        List<PendingRegistration> result = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }

        for (String key : keys) {
            try {
                Object value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    Map<String, Object> data = parsePendingData(value);
                    if (data != null) {
                        PendingRegistration reg = new PendingRegistration();
                        reg.setNodeId((String) data.get("nodeId"));
                        reg.setOwnerId(((Number) data.get("ownerId")).longValue());
                        reg.setNodeName((String) data.get("nodeName"));
                        reg.setCreatedAt(((Number) data.get("createdAt")).longValue());
                        result.add(reg);
                    }
                }
            } catch (Exception e) {
                log.warn("[AI-REG] Failed to parse pending key: {}", key);
            }
        }
        
        // 按创建时间排序
        result.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return result;
    }

    /**
     * 审批 AI 节点注册
     */
    @PostMapping("/approve")
    public ApprovalResponse approve(@RequestBody ApprovalRequest req) {
        if (req.getOwnerId() == null || StrUtil.isBlank(req.getNodeId())) {
            return new ApprovalResponse(false, null, "Missing required fields");
        }
        if (req.getApproved() && StrUtil.isBlank(req.getAiUserName())) {
            return new ApprovalResponse(false, null, "aiUserName is required when approved");
        }

        ApprovalResult result = registrationService.approve(
                req.getOwnerId(), 
                req.getNodeId(), 
                req.getApproved(), 
                req.getAiUserName());

        if (!result.isSuccess()) {
            return new ApprovalResponse(false, null, result.getError());
        }

        if (req.getApproved() && result.getAiUserId() != null) {
            // 创建好友关系
            try {
                createFriendRelationship(req.getOwnerId(), result.getAiUserId());
                
                // 给 Owner 发送好友添加通知
                pushService.sendAiNodeApprovedNotification(
                        req.getOwnerId(), 
                        result.getAiUserId(), 
                        req.getAiUserName());
                
                log.info("[AI-REG] Friend relationship created: owner={}, aiUserId={}", 
                        req.getOwnerId(), result.getAiUserId());
            } catch (Exception e) {
                log.error("[AI-REG] Failed to create friend relationship", e);
            }
        }

        return new ApprovalResponse(true, result.getAiUserId(), null);
    }

    /**
     * 检查节点是否已注册
     */
    @GetMapping("/status/{nodeId}")
    public NodeStatus getStatus(@PathVariable String nodeId) {
        boolean registered = registrationService.isRegistered(nodeId);
        Long aiUserId = registrationService.getAiUserId(nodeId);
        
        NodeStatus status = new NodeStatus();
        status.setRegistered(registered);
        status.setAiUserId(aiUserId);
        return status;
    }

    // =================== Private Methods ===================

    private void createFriendRelationship(Long ownerId, Long aiUserId) {
        // 查询 AI 用户的 user_id
        // 这里简化处理，实际应该从 UserDao 查询
        
        // 直接通过 FriendService 创建好友关系
        friendService.addFriend(ownerId, aiUserId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePendingData(Object value) {
        if (value instanceof String) {
            return cn.hutool.json.JSONUtil.toBean((String) value, Map.class);
        } else if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    // =================== DTOs ===================

    public static class ApprovalRequest {
        private Long ownerId;
        private String nodeId;
        private Boolean approved;
        private String aiUserName;

        public Long getOwnerId() { return ownerId; }
        public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public Boolean getApproved() { return approved; }
        public void setApproved(Boolean approved) { this.approved = approved; }
        public String getAiUserName() { return aiUserName; }
        public void setAiUserName(String aiUserName) { this.aiUserName = aiUserName; }
    }

    public static class ApprovalResponse {
        private boolean success;
        private Long aiUserId;
        private String error;

        public ApprovalResponse(boolean success, Long aiUserId, String error) {
            this.success = success;
            this.aiUserId = aiUserId;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Long getAiUserId() { return aiUserId; }
        public void setAiUserId(Long aiUserId) { this.aiUserId = aiUserId; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    public static class NodeStatus {
        private boolean registered;
        private Long aiUserId;

        public boolean isRegistered() { return registered; }
        public void setRegistered(boolean registered) { this.registered = registered; }
        public Long getAiUserId() { return aiUserId; }
        public void setAiUserId(Long aiUserId) { this.aiUserId = aiUserId; }
    }
}
