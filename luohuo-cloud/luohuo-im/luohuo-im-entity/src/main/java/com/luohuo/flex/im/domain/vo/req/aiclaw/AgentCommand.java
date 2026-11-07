package com.luohuo.flex.im.domain.vo.req.aiclaw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.Map;

/** Control receipts are independent of turns; identity and native bindings are never client supplied. */
public final class AgentCommand {
    private AgentCommand() { }

    @Data
    public static class Request {
        private String requestId;
        private String roomId;
        private String aiclawUid;
        private String operation;
        private String capabilityId;
        private Map<String, Object> parameters;
        private String catalogVersion;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Callback {
        private String requestId;
        private String roomId;
        private String status;
        private JsonNode catalog;
        private String text;
        private String error;
        private String phase;
        private String generation;
        private NativeRef nativeRef;
        private Effect effect;
        private Boolean reconciled;
        // receiptRevision and nativeResult intentionally not accepted from the Adapter.
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        private String requestId;
        private String roomId;
        private String aiclawUid;
        private String operation;
        private String status;
        private JsonNode catalog;
        private String text;
        private String error;
        private String phase;
        private String generation;
        private NativeRef nativeRef;
        private Effect effect;
        private Integer receiptRevision;
        private Boolean reconciled;
        // aichatoverview#505: approval state is independent of execution state.
        private String approvalStatus;
        private Long expiresAt;
    }

    /** aichatoverview#505: owner-scoped pending approval view. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Approval {
        private String requestId;
        private String roomId;
        private String aiclawUid;
        private String capabilityId;
        private Map<String, Object> parameters;
        private String callerUid;
        private Long expiresAt;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NativeRef {
        private String sessionId;
        private String inputId;
        private String messageId;
        private String parentId;
        private String turnId;
    }

    /** Only fields belonging to kind are projected by the service. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Effect {
        private String kind;
        private String sessionId;
        private String generation;
        private String controlId;
        private String summaryId;
        private Boolean summary;
        private String providerId;
        private String modelId;
        private String effective;
    }
}
