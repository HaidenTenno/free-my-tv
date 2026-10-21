package io.freemytv;

/** Immutable UI/backend timer values, in milliseconds; -1 means disabled. No Android dependencies. */
final class PowerTimers {
    enum Kind { SCREENSAVER, IDLE_SLEEP, PLAYBACK_SLEEP }

    final long saverMs, sleepMs, attentiveMs;

    PowerTimers(long saverMs, long sleepMs, long attentiveMs) {
        this.saverMs = saverMs;
        this.sleepMs = sleepMs;
        this.attentiveMs = attentiveMs;
    }

    long get(Kind kind) {
        switch (kind) {
            case SCREENSAVER: return saverMs;
            case IDLE_SLEEP: return sleepMs;
            default: return attentiveMs;
        }
    }

    PowerTimers with(Kind kind, long value) {
        return new PowerTimers(kind == Kind.SCREENSAVER ? value : saverMs,
                kind == Kind.IDLE_SLEEP ? value : sleepMs, kind == Kind.PLAYBACK_SLEEP ? value : attentiveMs);
    }

    boolean sameAs(PowerTimers other) {
        return other != null && saverMs == other.saverMs && sleepMs == other.sleepMs && attentiveMs == other.attentiveMs;
    }

    /** Validate only edits: an unchanged firmware-specific interval must survive Apply exactly. */
    String validationError(PowerTimers original) {
        if (original == null) return "Read the current settings first.";
        for (Kind kind : Kind.values()) {
            long value = get(kind);
            if (value != original.get(kind) && value != -1 && (value < 60_000 || value > 7L * 24 * 60 * 60 * 1000))
                return "Choose a delay of at least one minute, or Never.";
        }
        if ((saverMs != original.saverMs || sleepMs != original.sleepMs)
                && saverMs > 0 && sleepMs > 0 && sleepMs <= saverMs)
            return "Sleep must start later than the screensaver.";
        return "";
    }
}
