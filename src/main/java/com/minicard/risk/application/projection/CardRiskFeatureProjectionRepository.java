package com.minicard.risk.application.projection;

/**
 * Output port for a derived, replayable risk read model.
 *
 * <p>This is deliberately not a domain aggregate repository. The projection
 * summarizes facts from authorization events and does not own a transactional
 * business invariant.</p>
 */
public interface CardRiskFeatureProjectionRepository {

    void applyDecision(RecordAuthorizationDecisionCommand decision);
}
