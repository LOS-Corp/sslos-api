package com.laundry.machine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for the static machine QR code endpoint.
 *
 * <p>The QR content is deterministic: baseUrl + /machines/ + machineCode.
 * No token, no expiry — the QR is valid as long as the machineCode is valid.
 * Frontend/generator decodes the URL and calls the standard machine status API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineQrResponse {

    /** The machine's unique code, e.g. "WM001" */
    private String machineCode;

    /** The full URL embedded in the QR code. Decode with any QR scanner. */
    private String qrUrl;

    /** Absolute URL pointing to the machine detail/status page. */
    private String machinePageUrl;
}
