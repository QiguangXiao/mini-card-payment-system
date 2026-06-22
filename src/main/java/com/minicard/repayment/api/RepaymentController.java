package com.minicard.repayment.api;

import java.util.Currency;
import java.util.UUID;

import com.minicard.repayment.api.dto.ReceiveRepaymentRequest;
import com.minicard.repayment.api.dto.RepaymentResponse;
import com.minicard.repayment.application.ReceiveRepaymentCommand;
import com.minicard.repayment.application.RepaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Repayment API controller。
 *
 * <p>Controller 只把 HTTP header/body 转成 application command。
 * idempotency、row lock、statement/account state transition 和 Outbox 都在 Service/Domain。</p>
 */
// @Validated 让 Idempotency-Key header 上的 @NotBlank/@Size 生效；这是 API boundary 的第一道保护。
@Validated
// @RestController 保证 RepaymentResponse record 被 Jackson 写成 JSON，而不是被当作 MVC view。
@RestController
@RequestMapping("/api/repayments")
@RequiredArgsConstructor
public class RepaymentController {

    private final RepaymentService repaymentService;

    @PostMapping
    public RepaymentResponse receive(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody ReceiveRepaymentRequest request
    ) {
        // Currency.getInstance 放在 controller adapter：把 HTTP string contract 转成 application command 的 typed value。
        // 如果 service 直接接收 currency string，业务层会重复处理格式错误和 JDK currency 校验。
        ReceiveRepaymentCommand command = new ReceiveRepaymentCommand(
                idempotencyKey,
                request.statementId(),
                request.amount(),
                Currency.getInstance(request.currency())
        );
        return RepaymentResponse.from(repaymentService.receive(command));
    }

    @GetMapping("/{id}")
    public RepaymentResponse get(@PathVariable UUID id) {
        return RepaymentResponse.from(repaymentService.get(id));
    }
}
