package com.minicard.creditaccount.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CreditAccountMapper {

    CreditAccountRow findByIdForUpdate(@Param("accountId") String accountId);

    int update(CreditAccountRow account);
}
