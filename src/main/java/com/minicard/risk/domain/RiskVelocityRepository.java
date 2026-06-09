package com.minicard.risk.domain;

import java.time.Instant;

public interface RiskVelocityRepository {

    int countRecentAuthorizations(String cardId, Instant since);
}
