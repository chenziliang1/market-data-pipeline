package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body of the Anthropic Messages API. The answer is a list of content blocks;
 * only text blocks are used here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeMessageResponse(
        List<ContentBlock> content,

        @JsonProperty("stop_reason")
        String stopReason) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
            String type,
            String text) {
    }

    /** The first non-blank text block, or null when there is none. */
    public String firstText() {
        if (content == null) {
            return null;
        }

        return content.stream()
                .filter(block -> "text".equals(block.type())
                        && block.text() != null
                        && !block.text().isBlank())
                .map(ContentBlock::text)
                .findFirst()
                .orElse(null);
    }
}
