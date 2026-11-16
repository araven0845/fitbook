package com.fitbook.ai.assistant;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds at most one prepared-but-unconfirmed action per member. This is the server side of
 * confirm-before-write: the model cannot book by asking nicely, it can only prepare an action
 * here, and the confirm tool refuses to run it in the same turn it was prepared in.
 */
@Component
public class PendingActionStore {

    private final Map<Long, PendingAction> byUser = new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingActionStore(Clock clock) {
        this.clock = clock;
    }

    /** A new preparation replaces whatever was waiting before. */
    public void put(PendingAction action) {
        byUser.put(action.userId(), action);
    }

    /** The member's waiting action, if there is one and it has not expired. */
    public Optional<PendingAction> find(long userId) {
        PendingAction action = byUser.get(userId);
        if (action == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(action.expiresAt())) {
            byUser.remove(userId, action);
            return Optional.empty();
        }
        return Optional.of(action);
    }

    /** Removes exactly this action. False means another request already took or replaced it. */
    public boolean take(PendingAction action) {
        return byUser.remove(action.userId(), action);
    }

    /**
     * Called at the end of every chat turn. An action waits for exactly one reply from the member:
     * if this turn neither confirmed it nor prepared it (again), it is dropped. So "no", a change of
     * subject, or silence all cancel the request, whether or not the model says so.
     */
    public void dropIfLeftUnconfirmed(long userId, long currentTurn) {
        PendingAction action = byUser.get(userId);
        if (action != null && action.refreshedInTurn() < currentTurn) {
            byUser.remove(userId, action);
        }
    }

    public boolean discard(long userId) {
        return byUser.remove(userId) != null;
    }
}
