package com.minicard.authorization.api;

import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.api.dto.AuthorizationResponse;
import com.minicard.authorization.api.dto.CreateAuthorizationRequest;
import com.minicard.authorization.application.AuthorizationCommand;
import com.minicard.authorization.application.AuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/authorizations")
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    public AuthorizationController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping
    public AuthorizationResponse authorize(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody CreateAuthorizationRequest request
    ) {
        // Controller 只做 HTTP adapter：把 header/body 转成 application command。
        // 真正的幂等性(idempotency)、锁(row lock)、额度预占(reservation)都放在 Service/Domain。
        AuthorizationCommand command = new AuthorizationCommand(
                idempotencyKey,
                request.cardId(),
                request.amount(),
                Currency.getInstance(request.currency()),
                request.merchantId(),
                request.merchantCountry(),
                request.cardholderCountry()
        );
        // authorize(command) 是核心 use case 入口；返回 domain object 后再映射成 API response。
        return AuthorizationResponse.from(authorizationService.authorize(command));
    }

    @GetMapping("/{id}")
    public AuthorizationResponse get(@PathVariable UUID id) {
        // GET 是只读查询(read-only query)，按 authorizationId 读取当前状态，不修改额度或事件表。
        return AuthorizationResponse.from(authorizationService.get(id));
    }
}
