package com.minicard.statement.api;

import java.util.UUID;

import com.minicard.statement.api.dto.GenerateStatementRequest;
import com.minicard.statement.api.dto.StatementResponse;
import com.minicard.statement.application.GenerateStatementCommand;
import com.minicard.statement.application.StatementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statement API controller。
 *
 * <p>真实主路径是 StatementBatchPoller 触发的 monthly batch。
 * 这个 HTTP 入口保留为学习/运营 backfill 用；账单生成的 idempotency、row lock、
 * transaction boundary、snapshot 和 due-date DelayJob 仍在 StatementService/domain 内。</p>
 */
@RestController
@RequestMapping("/api/statements")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statementService;

    @PostMapping("/generate")
    public StatementResponse generate(@Valid @RequestBody GenerateStatementRequest request) {
        GenerateStatementCommand command = new GenerateStatementCommand(
                request.creditAccountId(),
                request.periodStart(),
                request.periodEnd(),
                request.dueDate()
        );
        return StatementResponse.from(statementService.generate(command));
    }

    @GetMapping("/{id}")
    public StatementResponse get(@PathVariable UUID id) {
        return StatementResponse.from(statementService.get(id));
    }
}
