package com.minicard.statement.api;

import java.util.UUID;

import com.minicard.statement.api.dto.GenerateStatementRequest;
import com.minicard.statement.api.dto.StatementResponse;
import com.minicard.statement.application.GenerateStatementCommand;
import com.minicard.statement.application.StatementReadModelService;
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
// Lombok 生成 final fields constructor，保留 constructor injection。
// 如果用 field injection，测试时依赖不明显，也更容易出现未初始化字段。
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statementService;
    private final StatementReadModelService statementReadModelService;

    @PostMapping("/generate")
    // @Valid 校验手动入口的 body；真实 batch 路径不会经过 controller，所以 service/domain 仍要防御非法账期。
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
    // @PathVariable 由 Spring MVC 把路径文本转换成 UUID；格式错误会在 HTTP boundary 变成 400。
    // 如果先收 String 再手动 parse，错误处理容易散到 controller 里。
    public StatementResponse get(@PathVariable UUID id) {
        return StatementResponse.from(statementReadModelService.get(id));
    }
}
