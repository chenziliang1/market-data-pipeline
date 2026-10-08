package com.example.demo.controller;

import com.example.demo.service.BinanceService;
import com.example.demo.service.MarketDataLoadException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loading writes data and calls the exchange, so it is a POST and requires the API key
 * (see {@link com.example.demo.config.ApiKeyFilter}).
 */
@RestController
public class LoadController {
    @Autowired
    private BinanceService binanceService;

    @PostMapping("/api/load/{symbol}/{startTime}/{endTime}")
    public ResponseEntity<String> load(
            @PathVariable String symbol,
            @PathVariable Long startTime,
            @PathVariable Long endTime) {
        int totalSaved = binanceService.load(symbol, startTime, endTime);
        return ResponseEntity.ok(totalSaved + " records were sent to Kafka");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }

    @ExceptionHandler(MarketDataLoadException.class)
    public ResponseEntity<String> partialFailure(MarketDataLoadException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(exception.getMessage());
    }
}
