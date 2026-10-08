package com.example.demo.dto;

import java.util.List;

/** Views of the dead-letter topic returned by the admin endpoints. */
public final class DeadLetterReport {

    private DeadLetterReport() {
    }

    /**
     * One dead-lettered record that has not been replayed or skipped yet.
     *
     * @param replayable whether a replay would publish it; if not, {@code reason} says why
     * @param value      the record's value as text, cut to 500 characters
     */
    public record DeadLetter(
            int partition,
            long offset,
            String key,
            long timestamp,
            String exceptionClass,
            String exceptionMessage,
            String originalTopic,
            Long originalOffset,
            int replayCount,
            boolean replayable,
            String reason,
            String value) {
    }

    /** Records that were not republished; they are acknowledged and will not be offered again. */
    public record Skipped(int partition, long offset, String key, String reason) {
    }

    public record ReplayResult(int replayed, List<Skipped> skipped) {
    }
}
