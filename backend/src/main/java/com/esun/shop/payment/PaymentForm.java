package com.esun.shop.payment;

import java.util.Map;

/** Data for a browser to POST directly to the provider; no hosted-payment HTML is generated here. */
public record PaymentForm(String actionUrl, Map<String, String> fields) {
}
