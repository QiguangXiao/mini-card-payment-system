package com.minicard.authorization.domain;

public interface AuthorizationDecisionPolicy {

    AuthorizationDecision decide(Authorization authorization);
}
