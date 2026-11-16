package com.fitbook.ai.assistant;

import java.time.Instant;

/**
 * A write the assistant has prepared but not performed: it waits here, on the server, until the
 * member confirms. For BOOK the target is a slot id, for CANCEL it is an appointment id.
 *
 * @param preparedInTurn  the chat turn that first prepared it; it can only be confirmed in a later turn
 * @param refreshedInTurn the latest chat turn that prepared it (the same turn, unless the model prepared it again)
 */
public record PendingAction(
        Kind kind,
        long userId,
        long targetId,
        long serviceId,
        String summary,
        long preparedInTurn,
        long refreshedInTurn,
        Instant expiresAt
) {
    public enum Kind { BOOK, CANCEL }
}
