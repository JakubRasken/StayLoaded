package io.github.jakubrasken.stayloaded.core.persistence;

import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import java.util.List;

public record SessionLoadResult(List<StayLoadedSession> sessions, SessionLoadSource source) {
    public SessionLoadResult {
        sessions = List.copyOf(sessions);
    }

    public boolean recovered() {
        return source == SessionLoadSource.TEMPORARY || source == SessionLoadSource.BACKUP;
    }
}
