package com.example.demo.controller;

import com.example.demo.dto.DeadLetterReport.DeadLetter;
import com.example.demo.dto.DeadLetterReport.ReplayResult;
import com.example.demo.service.DeadLetterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Requires the API key, like everything under /api/admin (see ApiKeyFilter). */
@RestController
@RequestMapping("/api/admin/dead-letters")
public class DeadLetterController {

    private static final int MAX_LIMIT = 500;

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public List<DeadLetter> pending(@RequestParam(defaultValue = "50") int limit) {
        return deadLetterService.pending(validLimit(limit));
    }

    @PostMapping("/replay")
    public ReplayResult replay(@RequestParam(defaultValue = "100") int limit) {
        return deadLetterService.replay(validLimit(limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> replayFailed(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(exception.getMessage());
    }

    private static int validLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }
}
