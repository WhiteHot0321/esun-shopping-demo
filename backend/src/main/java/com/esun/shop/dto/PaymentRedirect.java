package com.esun.shop.dto;

import java.util.Map;

/** A form the browser must POST to the payment provider's hosted page (no HTML is generated server-side). */
public record PaymentRedirect(String actionUrl, Map<String, String> fields) { }
