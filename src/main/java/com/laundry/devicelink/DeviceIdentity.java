package com.laundry.devicelink;

/**
 * What a board proved and reported in its HELLO: the public key whose private half signed this
 * session's nonce, plus where it is on the store network.
 *
 * @param publicKey verified P-256 public key (see {@link DeviceKeys})
 * @param ip        the board's address on the store Wi-Fi, as it reported it
 * @param rssi      Wi-Fi signal strength in dBm, as it reported it
 */
public record DeviceIdentity(byte[] publicKey, String ip, Integer rssi) {
}
