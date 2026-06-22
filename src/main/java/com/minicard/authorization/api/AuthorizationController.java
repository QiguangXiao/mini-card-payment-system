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
 * 授权 API controller，只负责 HTTP request/response 和参数校验。
 *
 * <p>interview重点：不要把业务逻辑放在 controller。idempotency、transaction boundary、
 * row lock 和 aggregate state transition 都在 application/domain 层。</p>
 */
// @Validated 让 header/path variable 上的 @NotBlank/@Size 生效；@Valid 只覆盖 request body。
// 如果省掉它，空 Idempotency-Key 可能进入 service，最后变成更难懂的 DB/业务异常。
@Validated
// @RestController = @Controller + @ResponseBody。用普通 @Controller 时，record 返回值可能被当成 view name。
@RestController
// class-level path 把同一资源的 API 收口在一起；否则每个方法都要重复完整 URL，后期改路径容易漏。
@RequestMapping("/api/authorizations")
// Lombok 只生成 final field 的 constructor，保持 constructor injection，同时避免手写样板代码。
// 这里不使用 @Data，因为 controller 不需要 setter/equals，这些额外方法会让依赖看起来可变。
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    @PostMapping
    public AuthorizationResponse authorize(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody CreateAuthorizationRequest request
    ) {
        // @Valid 在 HTTP boundary fail fast。如果不在这里校验，null/blank 会进入 Currency/Money/domain，
        // 客户端看到的错误会更晚、更低层，也更难对应到哪个 API 字段错了。
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
