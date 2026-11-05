package com.luohuo.flex.im.domain.vo.req.aiclaw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;
import java.util.Map;

/** #502 structured, read-only command lane; no client-supplied actor or workspace. */
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
        // nativeResult intentionally not retained: provider data is not public chat content.
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
    }
}
