package com.minicard.authorization.application;

import com.minicard.authorization.domain.Authorization;

/**
 * Authorization expiry delay job 的 application port。
 *
 * <p>关键词：授权过期计划, 延迟任务端口, reservation 释放, authorization expiry,
 * delay job port, authorization release, オーソリ期限切れ(オーソリきげんぎれ),
 * オーソリのリリース(オーソリのリリース)。</p>
 *
 * <p>AuthorizationService 在这里表达业务意图；adapter 可以把计划写入通用 delay_jobs 表，
 * 不把 scheduler 表结构泄漏到 authorization use case。</p>
 */
public interface AuthorizationExpiryJobScheduler {

    /**
     * 为已批准 authorization 安排过期释放任务。
     */
    void schedule(Authorization authorization);
}
