package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDecision;
import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.AuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {

    private final AuthorizationRepository repository;
    private final AuthorizationDecisionPolicy decisionPolicy;
    private final Clock clock;

    public AuthorizationService(
            AuthorizationRepository repository,
            AuthorizationDecisionPolicy decisionPolicy,
            Clock clock
    ) {
        this.repository = repository;
        this.decisionPolicy = decisionPolicy;
        this.clock = clock;
    }

    @Transactional
    public Authorization authorize(AuthorizationCommand command) {
        Authorization candidate = createAuthorization(command);

        // The repository performs one atomic upsert and returns the database
        // winner, avoiding a check-then-insert race across application nodes.
        Authorization persisted = repository.saveOrGet(command.idempotencyKey(), candidate);
        return returnIdempotentResult(persisted, command);
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
    }

    private Authorization createAuthorization(AuthorizationCommand command) {
        Instant now = Instant.now(clock);
        Authorization authorization = Authorization.request(
                command.cardId(),
                command.requestedAmount(),
                now
        );
        AuthorizationDecision decision = decisionPolicy.decide(authorization);
        authorization.apply(decision, now);
        return authorization;
    }

    private Authorization returnIdempotentResult(
            Authorization existing,
            AuthorizationCommand command
    ) {
        if (!command.matches(existing)) {
            throw new IdempotencyConflictException();
        }
        return existing;
    }
}
