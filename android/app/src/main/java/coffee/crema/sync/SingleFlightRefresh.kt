package coffee.crema.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One token refresh at a time for a service, shared by Visualizer and Drive: the
 * Android port of the web `token-vault.ts` `refreshFrom` and `refreshStored`.
 *
 * Visualizer's refresh tokens rotate, so two refreshes at once spend one twice
 * and the second answers `invalid_grant` on a session that is fine. Each caller
 * therefore re-reads the stored set once it holds the lock, and uses a set
 * another caller already stored.
 *
 * Nothing here writes on what it remembers. [store] and [end] take the refresh
 * token that was spent, and must act only if it is still the stored one: a
 * refresh finishing after a sign-out would otherwise sign the user back in, one
 * finishing after a sign-in as someone else would store the old account's
 * tokens, and a late `invalid_grant` would end a session that replaced it.
 */
internal class SingleFlightRefresh<T : Any>(
    /** The set stored now. */
    private val stored: () -> T?,
    private val refreshTokenOf: (T) -> String?,
    /** Whether a set is too near its expiry to use as it stands. */
    private val expiring: (T) -> Boolean,
    /** The refresh-token grant. */
    private val grant: suspend (refreshToken: String) -> T,
    /** Whether a failed grant proves the session is over. */
    private val endsSession: (Throwable) -> Boolean,
    /** Store [T] if the refresh token spent is still the stored one. */
    private val store: suspend (spent: String, fresh: T) -> Unit,
    /** End the session if the refresh token is still the stored one (null: the set had none). */
    private val end: suspend (rejected: String?) -> Unit,
) {
    private val mutex = Mutex()

    /**
     * A set to use in place of [observed], or null when the session is over.
     * [rejected] marks the 401 path: the server refused [observed]'s access token,
     * so any other stored set is worth a try as it stands. Throws the grant's
     * failure when it leaves the session standing.
     */
    suspend fun refresh(observed: T, rejected: Boolean): T? = mutex.withLock {
        val current = stored() ?: return null
        if (current != observed && (rejected || !expiring(current))) return current
        val spent = refreshTokenOf(current)
        if (spent == null) {
            end(null)
            return stored()
        }
        val fresh = try {
            grant(spent)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (!endsSession(e)) throw e
            end(spent)
            return stored()
        }
        store(spent, fresh)
        stored()
    }
}
