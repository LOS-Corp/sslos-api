package com.laundry.transaction.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception thrown when transaction is invalid or not found
 */
public class TransactionException extends BusinessException {

    public TransactionException(String message) {
        super(ErrorCode.TRANSACTION_REJECTED, message);
    }
}
