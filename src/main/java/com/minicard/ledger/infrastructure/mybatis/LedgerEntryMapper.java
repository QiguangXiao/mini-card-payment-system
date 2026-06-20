package com.minicard.ledger.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;

/**
 * LedgerEntry MyBatis mapper。
 */
@Mapper
public interface LedgerEntryMapper {

    /**
     * 插入 ledger entry；source_event_id + entry_type 唯一键冲突表示重复事件。
     */
    int insert(LedgerEntryRow row);
}
