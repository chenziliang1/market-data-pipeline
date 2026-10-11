package com.example.demo.dto;

import java.util.List;

/**
 * The model's analysis plus the problems the deterministic checks found in it. An empty
 * problem list means the text only cites numbers the application supplied, names the date,
 * the UTC timezone and the data source, carries the disclaimer, and respects the length.
 */
public record AnalysisResult(
        String analysis,
        List<String> problems) {

    public boolean passed() {
        return problems.isEmpty();
    }
}
