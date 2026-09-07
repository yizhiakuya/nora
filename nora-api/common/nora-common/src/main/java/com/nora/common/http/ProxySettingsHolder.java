package com.nora.common.http;

import java.net.InetSocketAddress;

/**
 * Runtime-mutable holder for outbound proxy settings.
 *
 * <p>Starts from the static configuration (application.yml / env) and can be
 * overridden at runtime via the settings UI (persisted in DB by the owning
 * service). All HTTP client construction sites read {@link #current()} at
 * call time, so an update takes effect immediately — no restart.
 *
 * <p>Static mutable state is deliberate here: {@code ProxyProperties} is
 * injected in several services/controllers and a holder keeps the change
 * site-minimal while remaining thread-safe.
 */
public final class ProxySettingsHolder {

    private static volatile ProxyProperties current = ProxyProperties.disabled();

    private ProxySettingsHolder() {
    }

    /** Value to use for the next outbound call. Never null. */
    public static ProxyProperties current() {
        return current;
    }

    /** Swap the active settings (e.g. on startup or when the UI saves). */
    public static void set(ProxyProperties props) {
        current = props != null ? props : ProxyProperties.disabled();
    }

    /** Convenience for client construction sites. */
    public static InetSocketAddress addressFor(String targetUrl) {
        return ProxySupport.addressFor(current(), targetUrl);
    }
}
