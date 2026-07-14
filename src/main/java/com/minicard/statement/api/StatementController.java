package com.minicard.statement.api;

import java.util.UUID;

import com.minicard.statement.api.dto.StatementResponse;
import com.minicard.statement.application.read.StatementReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statement API controller。
 *
 * <p>只暴露账单查询。真实出账入口是 BillingCycleScheduler 创建
 * durable statement jobs 后由 worker 处理；不提供公开 generate API，避免请求绕过
 * billing-cycle reconciliation 直接触发关账。</p>
 */
@RestController
@RequestMapping("/api/statements")
// Lombok 生成 final fields constructor，保留 constructor injection。
// 如果用 field injection，测试时依赖不明显，也更容易出现未初始化字段。
@RequiredArgsConstructor
public class StatementController {

    private final StatementReadService statementReadService;

    @GetMapping("/{id}")
    // @PathVariable 由 Spring MVC 把路径文本转换成 UUID；格式错误会在 HTTP boundary 变成 400。
    // 如果先收 String 再手动 parse，错误处理容易散到 controller 里。
    // GET 走 statement read cache：Controller 不关心 L1/L2 细节，只依赖一个查询服务。
    // 这能避免把 cache key、Redis JSON、TTL 等技术细节泄漏到 HTTP adapter。
    // 写路径仍更新 MySQL source of truth；还款提交后由 RepaymentService 注册 after-commit evict。
    public StatementResponse get(@PathVariable UUID id) {
        return StatementResponse.from(statementReadService.get(id));
    }
}
