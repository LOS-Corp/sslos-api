package com.laundry.qr.exception;

/**
 * Exception for QR token errors
 */
public class QrTokenException extends RuntimeException {

    public QrTokenException(String message) {
        super(message);
    }
}
