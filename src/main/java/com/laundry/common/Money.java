package com.laundry.common;

/** Tiền là long, đơn vị đồng, không số lẻ (§4.3). */
public final class Money {

    private Money() {
    }

    /** {@code percent}% của {@code amount}, làm tròn LÊN tới bội số {@code step} (tiền giữ chỗ: 20%, 1.000 đ). */
    public static long percentRoundedUp(long amount, int percent, long step) {
        if (amount <= 0 || percent <= 0) return 0;
        if (percent >= 100) return amount;
        long raw = ceilDiv(amount * percent, 100);
        long rounded = ceilDiv(raw, step) * step;
        return Math.min(rounded, amount);
    }

    /** {@code percent}% làm tròn xuống tới đồng (hoàn 80%: phần còn lại thuộc về cửa hàng). */
    public static long percentFloor(long amount, int percent) {
        return Math.floorDiv(amount * percent, 100);
    }

    /** 30000 -> "30.000 đ" */
    public static String format(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount).replace(',', '.') + " đ";
    }

    public static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
