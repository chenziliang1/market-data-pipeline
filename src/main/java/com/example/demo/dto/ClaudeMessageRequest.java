package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Request body of the Anthropic Messages API (POST /v1/messages). {@code outputConfig}
 * carries the structured-output format when the answer must match a JSON schema.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeMessageRequest(
        String model,

        @JsonProperty("max_tokens")
        Integer maxTokens,

        String system,

        List<Message> messages,

        @JsonProperty("output_config")
        Map<String, Object> outputConfig) {

    public record Message(
            String role,
            String content) {
    }
}
