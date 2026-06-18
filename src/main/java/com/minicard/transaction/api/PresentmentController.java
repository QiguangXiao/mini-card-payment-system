package com.minicard.transaction.api;

import java.util.Currency;

import com.minicard.transaction.api.dto.CardTransactionResponse;
import com.minicard.transaction.api.dto.CreatePresentmentRequest;
import com.minicard.transaction.application.PostingService;
import com.minicard.transaction.application.PostPresentmentCommand;
import jakarta.validation.Valid;
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
@RequestMapping("/api/presentments")
public class PresentmentController {

    private final PostingService postingService;

    public PresentmentController(PostingService postingService) {
        this.postingService = postingService;
    }

    @PostMapping
    public CardTransactionResponse postPresentment(
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
