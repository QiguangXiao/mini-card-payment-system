package com.minicard.statement.application;

/**
 * 「这个账户本账期不应出账」的业务信号。
 *
 * <p>关键词：账单生成异常, 业务跳过, statement generation exception, business skip,
 * 請求作成例外(せいきゅうさくせいれいがい)。</p>
 *
 * <p>它表示确定性的业务结论（例如加锁读取时该账户已经没有 UNBILLED 交易），
 * 不是系统故障。{@link StatementBatchService} 把它计入 skipped 并继续下一个账户；
 * 真正的系统异常（DB 故障、死锁）走 {@code RuntimeException} 分支计入 failed，
 * 由第二天的批处理自然重试——那些账户的交易仍是 UNBILLED，还在候选集合里。</p>
 *
 * <p>反向事实：如果不区分这两类，一个「本来就没有消费的账户」会和「数据库超时」一样被记成失败，
 * 出账日志里的 failed 数就永远不为零，真正的故障反而被淹没。</p>
 */
public class StatementGenerationException extends RuntimeException {

    private StatementGenerationException(String message) {
        super(message);
    }

    /**
     * 创建「不需要为该账户出账」的确定性业务跳过信号。
     */
    public static StatementGenerationException rejected(String message) {
        return new StatementGenerationException(message);
    }
}
