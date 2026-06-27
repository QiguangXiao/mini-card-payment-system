package com.minicard.risk.application;

/**
 * velocity 计数来源。
 *
 * <p>关键词：风控 velocity, 降级来源, risk velocity source,
 * degraded signal, ベロシティ判定(ベロシティはんてい)。</p>
 */
public enum VelocitySource {

    /** Redis sliding-window hot path。 */
    REDIS,

    /** JDBC COUNT(*) 对照实现，通常只在测试或显式配置切换时使用。 */
    JDBC
}
