package io.github.jakubrasken.stayloaded.core.session;

public enum SessionOutcome {
    NONE,
    PLAYER_RETURNED,
    TIMEOUT,
    ADMIN_TERMINATED,
    PLAYER_DIED,
    RECOVERY_FAILED,
    PLUGIN_DISABLED
}
