package coffee.crema

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for code that calls suspend functions: a failure becomes a
 * [Result], but a [CancellationException] is rethrown so a cancelled coroutine
 * stops instead of carrying on (and persisting) as if a call had failed.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Result.failure(e)
    }
