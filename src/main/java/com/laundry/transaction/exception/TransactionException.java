package com.laundry.transaction.exception;

/**
 * Exception thrown when transaction is invalid or not found
 */
public class TransactionException extends RuntimeException {

    public TransactionException(String message) {
        super(message);
    }
}
