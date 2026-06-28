package com.minicard.statement.infrastructure.cache;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.minicard.statement.application.StatementReadService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StatementReadCacheEvictListenerTest {

    private final StatementReadService statementReadService = mock(StatementReadService.class);
    private final StatementReadCacheEvictListener listener =
            new StatementReadCacheEvictListener(statementReadService);

    @Test
    void validMessageInvalidatesLocalL1Only() {
        UUID statementId = UUID.randomUUID();

        listener.onMessage(message(statementId.toString()), null);

        // 订阅者只清本地 L1（invalidateLocal），不触发删 L2 / 再广播。
        verify(statementReadService).invalidateLocal(statementId);
    }

    @Test
    void invalidMessageIsIgnoredWithoutThrowing() {
        // 毒消息不该毒死订阅者，也不应触发任何失效。
        listener.onMessage(message("not-a-uuid"), null);

        verify(statementReadService, never()).invalidateLocal(any());
    }

    private DefaultMessage message(String body) {
        return new DefaultMessage(
                RedisStatementReadCacheBroadcaster.EVICT_CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
