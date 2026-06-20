package com.minicard.repayment.application;

public interface BankDebitGateway {

    BankDebitResult debit(BankDebitRequest request);
}
