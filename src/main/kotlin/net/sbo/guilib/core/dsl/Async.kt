package net.sbo.guilib.core.dsl

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * State of an asynchronous load ([useAsync], [useFuture], [usePromise]).
 *
 * [value] is the result of the latest load that succeeded, and stays available while a newer load runs (show the old
 * data with a spinner) — it is `null` only before the first success. [error] is the failure of the load for the
 * current keys (`null` while it runs or if it succeeded). [loading] is true while the load for the current keys has
 * not finished.
 */
class Async<T> internal constructor(
    val value: T?,
    val error: Throwable?,
    val loading: Boolean,
    private val reloadAction: () -> Unit,
) {
    /** The load for the current keys finished without an error. */
    val isSuccess: Boolean get() = !loading && error == null

    /** Starts the load again with the same keys (e.g. a "Retry" or "Refresh" button). */
    fun reload() = reloadAction()
}

/** Background threads for [useAsync]: daemon threads, so they never keep the game from closing. */
internal object AsyncExecutor {
    private val count = AtomicInteger()
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "GuiLib async #${count.incrementAndGet()}").also { it.isDaemon = true }
    }
}

/**
 * Runs [load] on a background thread and re-renders with its result — for slow work like HTTP requests or file
 * reads that must not block the game. Runs after mount and again whenever one of [keys] changes; results of outdated
 * loads (keys changed, component unmounted) are dropped.
 *
 * ```
 * val commit = useAsync { fetchLatestCommit() }
 * span { +when { commit.loading -> "loading…"; commit.error != null -> "unavailable"; else -> commit.value!! } }
 * ```
 *
 * [load] must not touch the UI (it runs on another thread); everything it needs should be passed in or captured.
 */
fun <T> ComponentScope.useAsync(vararg keys: Any?, load: () -> T): Async<T> =
    useFuture(*keys) { CompletableFuture.supplyAsync(load, AsyncExecutor.executor) }

/**
 * Like [useAsync] for code that already returns a [CompletableFuture]: [start] is called after mount and whenever
 * one of [keys] changes, and the component re-renders when the future completes. Futures are not cancelled when they
 * become outdated (they may be shared); their result is just ignored.
 */
fun <T> ComponentScope.useFuture(vararg keys: Any?, start: () -> CompletableFuture<T>): Async<T> {
    class Result<T>(val token: Any, val value: T?, val error: Throwable?)

    val attempt = useState(0)
    val token = keys.toList() to attempt.value
    val result = useState<Result<T>?>(null)
    val lastValue = useRef<T?>(null)
    val doc = useDocument()
    val latestStart = useRef(start)
    latestStart.current = start

    useEffect(*keys, attempt.value) {
        var active = true
        onCleanup { active = false }
        val future = try {
            latestStart.current()
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
        future.whenComplete { v, e ->
            // Applied on the UI thread, so `active` is read where the cleanup writes it.
            doc.post {
                if (!active) return@post
                val err = unwrap(e)
                if (err == null) lastValue.current = v
                result.value = Result(token, v, err)
            }
        }
    }

    val current = result.value?.takeIf { it.token == token }
    return Async(lastValue.current, current?.error, loading = current == null) { attempt.update { it + 1 } }
}

/**
 * Like [useAsync] for callback-style APIs, shaped like JavaScript's `new Promise((resolve, reject) => …)`: [start]
 * gets `resolve` and `reject` and calls one of them when done, from any thread. Later calls are ignored.
 *
 * ```
 * val parties = usePromise(floor) { resolve, reject -> api.loadParties(floor, onResult = resolve, onError = reject) }
 * ```
 */
fun <T> ComponentScope.usePromise(vararg keys: Any?, start: (resolve: (T) -> Unit, reject: (Throwable) -> Unit) -> Unit): Async<T> =
    useFuture(*keys) {
        val future = CompletableFuture<T>()
        start({ future.complete(it) }, { future.completeExceptionally(it) })
        future
    }

private fun unwrap(e: Throwable?): Throwable? {
    var t = e
    while ((t is CompletionException || t is ExecutionException) && t.cause != null) t = t.cause
    return t
}
