package com.minicard.scheduling.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generic scheduler for delayed business actions.
 */
@Component
@ConditionalOnProperty(
        prefix = "delay-jobs.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class DelayJobScheduler {

    private final DelayJobService delayJobService;
    private final DelayJobSchedulerProperties properties;

    public DelayJobScheduler(
            DelayJobService delayJobService,
            DelayJobSchedulerProperties properties
    ) {
        this.delayJobService = delayJobService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${delay-jobs.scheduler.fixed-delay-ms:1000}",
            scheduler = "delayJobTaskScheduler"
    )
    public void dispatchDueJobs() {
        // This mirrors the Outbox publisher's polling shape, but uses a
        // separate worker pool because business jobs may contend with account
        // locks and should not delay message publication.
        for (int processed = 0; processed < properties.maxPerRun(); processed++) {
            if (!delayJobService.dispatchNext()) {
                return;
            }
        }
    }
}
