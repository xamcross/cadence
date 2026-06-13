package com.cadence.scheduler;

import net.logstash.logback.argument.StructuredArguments;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ScheduledTaskExceptionHandler {

    private final DeadLetterService deadLetterService;

    public ScheduledTaskExceptionHandler(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object handleScheduledTask(ProceedingJoinPoint joinPoint) throws Throwable {
        String taskName = joinPoint.getSignature().getDeclaringTypeName()
            + "." + joinPoint.getSignature().getName();
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            deadLetterService.recordFailure(taskName, ex, null);
            throw ex;
        }
    }
}
