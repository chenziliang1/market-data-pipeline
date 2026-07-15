package com.example.demo.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ExecutionTimeAspect {

    private static final Logger log =
            LoggerFactory.getLogger(ExecutionTimeAspect.class);

    @Around("@annotation(com.example.demo.aop.TrackExecutionTime)")
    public Object measure(ProceedingJoinPoint joinPoint)
            throws Throwable {

        long start = System.nanoTime();

        try {
            return joinPoint.proceed();
        } finally {
            double milliseconds =
                    (System.nanoTime() - start) / 1_000_000.0;

            log.info("{} took {} ms",
                    joinPoint.getSignature().toShortString(),
                    milliseconds);
        }
    }
}