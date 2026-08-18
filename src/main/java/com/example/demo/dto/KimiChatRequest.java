package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KimiChatRequest(
        String model,

        @JsonProperty("reasoning_effort")
        String reasoningEffort,

        @JsonProperty("max_completion_tokens")
        Integer maxCompletionTokens,

        List<Message> messages,

        @JsonProperty("response_format")
        Map<String, Object> responseFormat) {

    public record Message(
            String role,
            String content) {
    }
}
