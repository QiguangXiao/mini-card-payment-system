package com.minicard.ledger.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.ledger.domain.LedgerEntry;
import com.minicard.ledger.domain.LedgerEntryRepository;
import com.minicard.ledger.domain.LedgerEntryType;
import com.minicard.messaging.inbox.ConsumerInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ledger projection 的 application service。
 *
 * <p>关键词：账本投影, Inbox 幂等, 事件最终一致性,
 * ledger projection, consumer idempotency, eventual consistency,
 * 仕訳投影(しわけとうえい)。</p>
 *
 * <p>interview重点：Ledger 不在 posting/repayment 主事务里同步写。
 * 主事务先通过 Outbox 发布业务事实，Ledger 再作为独立 consumer 记录分录。
 * 这样主交易不等待账本投影，但要接受 eventual consistency 和 consumer 幂等。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecordLedgerEntryService {

    public static final String CONSUMER_NAME = "ledger-v1";

    private final ConsumerInboxRepository inboxRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final Clock clock;

    @Transactional
    public void record(RecordLedgerEntryCommand command) {
        Instant now = Instant.now(clock);
        // CONSUMER_NAME 必须稳定：它是 consumer_inbox 唯一键的一部分。
        // 如果每次重命名 class/package 都改变 consumer name，同一 Kafka event 会被当成新消费者重新执行。
        // Inbox claim 是第一道 consumer-side idempotency：
        // Kafka/Outbox 是 at-least-once，同一个 eventId 可能被重复投递给 ledger-v1。
        // 如果没有这道门，consumer 重启后 offset 回放会把同一笔消费/还款记两次 ledger entry。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), now)) {
            log.info("ledger_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        LedgerEntry entry = toEntry(command, now);
        if (!ledgerEntryRepository.appendIfAbsent(entry)) {
            // ledger_entries 的唯一键是第二道保护；如果未来有手工 replay 或 Inbox 迁移，
            // 这里仍能避免同一个 source event 造成重复账本分录。
            // 没有这个 fallback，手工补偿脚本或 replay job 一旦绕过 Inbox 就会污染账本投影。
            log.info("ledger_entry_duplicate eventId={} type={}",
                    command.sourceEventId(),
                    command.entryType());
            return;
        }
        log.info(
                "ledger_entry_recorded eventId={} entryId={} type={} direction={} accountId={} amount={}",
                command.sourceEventId(),
                entry.id(),
                entry.entryType(),
                entry.direction(),
                entry.creditAccountId(),
                entry.amount().amount()
        );
    }

    private LedgerEntry toEntry(RecordLedgerEntryCommand command, Instant now) {
        if (command.entryType() == LedgerEntryType.CARD_TRANSACTION_POSTED) {
            return LedgerEntry.recordPurchasePosted(
                    command.sourceEventId(),
                    command.sourceId(),
                    command.creditAccountId(),
                    command.money(),
                    command.occurredAt(),
                    now
            );
        }
        if (command.entryType() == LedgerEntryType.REPAYMENT_RECEIVED) {
            return LedgerEntry.recordRepaymentReceived(
                    command.sourceEventId(),
                    command.sourceId(),
                    command.creditAccountId(),
                    command.money(),
                    command.occurredAt(),
                    now
            );
        }
        throw new IllegalArgumentException("unsupported ledger entry type " + command.entryType());
    }
}
