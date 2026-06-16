package com.minicard.authorization.application;

import java.util.UUID;

import com.minicard.scheduling.application.DelayJobHandler;
import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobType;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationExpiryDelayJobHandler implements DelayJobHandler {

    private final AuthorizationExpiryService expiryService;

    public AuthorizationExpiryDelayJobHandler(AuthorizationExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Override
    public DelayJobType jobType() {
        return DelayJobType.AUTHORIZATION_EXPIRY;
    }

    @Override
    public void handle(DelayJob job) {
        expiryService.expire(UUID.fromString(job.aggregateId()));
    }
}
