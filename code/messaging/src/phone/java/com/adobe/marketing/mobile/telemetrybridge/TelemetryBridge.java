/*
  POC ONLY - telemetry-integration branch. Optional reflective bridge to the telemetry plugin.
  When the plugin artifact is present, calls flow; when absent, everything is a no-op.
  No compile-time dependency of this SDK on the telemetry SDK.
*/
package com.adobe.marketing.mobile.telemetrybridge;

import java.lang.reflect.Method;

/** Optional bridge to the telemetry plugin public API (aepsdk-telemetry-android). */
public final class TelemetryBridge {
    private static final String TELEMETRY_CLASS = "com.adobe.marketing.mobile.telemetry.Telemetry";

    private TelemetryBridge() {}

    public static String operationStarted(final String producer, final String eventName) {
        try {
            final Class<?> telemetry = Class.forName(TELEMETRY_CLASS);
            final Method m = telemetry.getMethod("operationStarted", String.class, String.class, String.class);
            return (String) m.invoke(null, producer, eventName, "directHook");
        } catch (Throwable t) {
            return null; // plugin absent or disabled - business path unaffected
        }
    }

    public static void operationCompleted(final String operationId, final String producer,
                                          final String eventName, final String outcome, final String meaning) {
        if (operationId == null) return;
        try {
            final Class<?> telemetry = Class.forName(TELEMETRY_CLASS);
            final Method m = telemetry.getMethod("operationCompleted",
                    String.class, String.class, String.class, String.class, String.class, String.class);
            m.invoke(null, operationId, producer, eventName, outcome, meaning, "directHook");
        } catch (Throwable ignored) { }
    }
}
