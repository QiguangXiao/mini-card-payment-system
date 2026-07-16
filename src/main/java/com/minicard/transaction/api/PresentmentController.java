package com.minicard.transaction.api;

import java.util.Currency;

import com.minicard.transaction.api.dto.CardTransactionResponse;
import com.minicard.transaction.api.dto.CreatePresentmentRequest;
import com.minicard.transaction.application.PostingService;
import com.minicard.transaction.application.PostPresentmentCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presentment API controller。
 *
 * <p>Controller 不做入账判断，只把外部网络/clearing 侧请求转换成 application command。
 * 幂等、row lock、posting state transition 都在 PostingService 和 domain objects 中完成。</p>
 */
@RestController
// @RequestMapping 放在 class 上表达资源前缀；如果每个方法写完整路径，重命名资源时容易漏改。
@RequestMapping("/api/presentments")
@RequiredArgsConstructor
public class PresentmentController {

    private final PostingService postingService;

    @PostMapping
    public CardTransactionResponse postPresentment(
            // @Valid 拦住不可信 HTTP 输入；Money/CardTransaction 仍保留业务 invariant，
            // 但 command 只做已校验 DTO 到 application use case 的类型传递，不重复一套字段校验。
            @Valid @RequestBody CreatePresentmentRequest request
    ) {
        PostPresentmentCommand command = new PostPresentmentCommand(
                request.networkTransactionId(),
                request.authorizationId(),
                request.amount(),
                Currency.getInstance(request.currency())
        );
        return CardTransactionResponse.from(postingService.post(command));
    }
}
