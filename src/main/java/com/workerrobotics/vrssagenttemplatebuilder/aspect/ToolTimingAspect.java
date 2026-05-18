package com.workerrobotics.vrssagenttemplatebuilder.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class ToolTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolTimingAspect.class);

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getName();
        log.info("Tool '{}' invoked with args={}", name, Arrays.toString(pjp.getArgs()));
        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            log.info("Tool '{}' completed in {}ms", name, System.currentTimeMillis() - start);
        }
    }
}
