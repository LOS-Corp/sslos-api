package com.laundry.wallet.entity;

/** Why money moved in or out of a wallet. Credits are positive entries, debits negative. */
public enum WalletEntryType {
    /** Cancellation, no-show or exception refund (BR-11…15). */
    REFUND_CREDIT,
    /** payOS paid after the hold expired; the slot is not re-taken (BR-04, MSG11). */
    LATE_PAYMENT_CREDIT,
    /** Wallet used to pay an order (FR-PAY-04). */
    PAYMENT_DEBIT,
    /** Wallet part given back because the rest of the payment failed (SC-31). */
    PAYMENT_REVERSAL,
    WITHDRAWAL_HOLD,
    WITHDRAWAL_RELEASE,
    STORAGE_FEE
}
