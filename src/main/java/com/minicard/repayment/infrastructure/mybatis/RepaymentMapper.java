package com.minicard.repayment.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepaymentMapper {

    int insert(RepaymentRow repayment);

    RepaymentRow findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    RepaymentRow findById(@Param("id") String id);

    int update(RepaymentRow repayment);
}
