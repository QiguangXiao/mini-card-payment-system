package com.minicard.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.ledger.domain.LedgerEntryType;
import com.minicard.ledger.domain.LedgerSourceType;

/**
 * 从 integration event 进入 Ledger use case 的 command。
 *
 * <p>关键词：账本命令, 事件转命令, ledger command,
 * integration event mapping, 仕訳コマンド(しわけコマンド)。</p>
 */
public record RecordLedgerEntryCommand(
        UUID sourceEventId,
        LedgerEntryType entryType,
        LedgerSourceType sourceType,
        UUID sourceId,
        UUID creditAccountId,
        BigDecimal amount,
        Currency currency,
        Instant occurredAt
) {

    // compact constructor 用 Objects.requireNonNull 统一保护 listener/test/replay 创建路径。
    // 如果只在 Kafka payload parser 校验，其他入口仍可能构造出缺字段 command。
    public RecordLedgerEntryCommand {
        Objects.requireNonNull(sourceEventId);
        Objects.requireNonNull(entryType);
        Objects.requireNonNull(sourceType);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(occurredAt);
    }

    public static RecordLedgerEntryCommand cardTransactionPosted(
            UUID sourceEventId,
            UUID cardTransactionId,
            UUID creditAccountId,
            BigDecimal amount,
            Currency currency,
            Instant postedAt
    ) {
        return new RecordLedgerEntryCommand(
                sourceEventId,
                LedgerEntryType.CARD_TRANSACTION_POSTED,
                LedgerSourceType.CARD_TRANSACTION,
                cardTransactionId,
                creditAccountId,
                amount,
                currency,
                postedAt
        );
    }

    public static RecordLedgerEntryCommand repaymentReceived(
            UUID sourceEventId,
            UUID repaymentId,
            UUID creditAccountId,
            BigDecimal amount,
            Currency currency,
            Instant receivedAt
    ) {
        return new RecordLedgerEntryCommand(
                sourceEventId,
                LedgerEntryType.REPAYMENT_RECEIVED,
                LedgerSourceType.REPAYMENT,
                repaymentId,
                creditAccountId,
                amount,
                currency,
                receivedAt
        );
    }

    /**
     * 把 event payload 中的 amount/currency 组合成 Money value object。
     */
    public Money money() {
        return new Money(amount, currency);
    }
}
