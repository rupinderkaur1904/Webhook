package dev.webhooks.platform.observability;

import java.util.regex.Pattern;

/**
 * Masks anything shaped like a signing secret ({@code whsec_…}) or API key ({@code whk_…}) in log
 * output. No code path is meant to log one; this is the backstop. The plain-text console pattern in
 * application.yml uses the same expression via Logback's {@code %replace}.
 */
public final class LogMasking {

    static final Pattern SECRET = Pattern.compile("(whsec_|whk_)[A-Za-z0-9+/=_-]+");

    private LogMasking() {}

    public static String mask(String value) {
        return value == null || value.indexOf('_') < 0 ? value : SECRET.matcher(value).replaceAll("$1****");
    }
}
