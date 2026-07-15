package com.example.demo.controller;

import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.service.AggregationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/aggregates")
public class AggregationController {

    private final AggregationService aggregationService;

    public AggregationController(
            AggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/{period}/{symbol}/{startTime}/{endTime}")
    public List<AggregatedTradeData> getAggregated(
            @PathVariable AggregationPeriod period,
            @PathVariable String symbol,
            @PathVariable long startTime,
            @PathVariable long endTime)
            throws JsonProcessingException {

        return aggregationService.getAggregated(
                symbol,
                startTime,
                endTime,
                period);
    }
}