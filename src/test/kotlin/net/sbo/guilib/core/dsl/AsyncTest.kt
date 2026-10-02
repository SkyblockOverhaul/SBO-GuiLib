package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AsyncTest {
    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block }", "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        frames(root)
        return root
    }

    private fun frames(root: UiRoot) = repeat(2) { root.frame(200f, 100f) }

    @Test
    fun futureResultsReRenderAndOutdatedOnesAreDropped() {
        val futures = HashMap<String, CompletableFuture<String>>()
        var seen: Async<String>? = null
        lateinit var setKey: (String) -> Unit
        var renders = 0
        val root = ui {
            val (key, s) = useState("a")
            setKey = s
            renders++
            seen = useFuture(key) { futures.getOrPut(key) { CompletableFuture() } }
        }
        assertTrue(seen!!.loading)
        assertNull(seen!!.value)

        futures.getValue("a").complete("A")
        frames(root)
        assertFalse(seen!!.loading)
        assertTrue(seen!!.isSuccess)
        assertEquals("A", seen!!.value)

        // New key: loading again, the old value stays available.
        setKey("b")
        frames(root)
        assertTrue(seen!!.loading)
        assertEquals("A", seen!!.value)
        // A late result for an outdated key is ignored.
        setKey("c")
        frames(root)
        futures.getValue("b").complete("B")
        frames(root)
        assertTrue(seen!!.loading)
        assertEquals("A", seen!!.value)

        // Failures are reported for the current key, unwrapped.
        futures.getValue("c").completeExceptionally(IllegalStateException("offline"))
        frames(root)
        assertFalse(seen!!.loading)
        assertEquals("offline", seen!!.error?.message)
        assertEquals("A", seen!!.value)

        // reload() starts it again (same key, a new future).
        futures.remove("c")
        seen!!.reload()
        frames(root)
        assertTrue(seen!!.loading)
        futures.getValue("c").complete("C")
        frames(root)
        assertTrue(seen!!.isSuccess)
        assertEquals("C", seen!!.value)
    }

    @Test
    fun aThrowingStartIsAnError() {
        var seen: Async<Int>? = null
        val root = ui { seen = useFuture { throw IllegalArgumentException("bad") } }
        frames(root)
        assertFalse(seen!!.loading)
        assertEquals("bad", seen!!.error?.message)
    }

    @Test
    fun useAsyncRunsOffTheUiThread() {
        var seen: Async<String>? = null
        val uiThread = Thread.currentThread()
        val root = ui { seen = useAsync { if (Thread.currentThread() === uiThread) "same thread" else "background" } }
        val deadline = System.currentTimeMillis() + 5000
        while (seen!!.loading && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            frames(root)
        }
        assertEquals("background", seen!!.value)
    }

    @Test
    fun resultsAfterUnmountAreIgnored() {
        val future = CompletableFuture<String>()
        lateinit var setShown: (Boolean) -> Unit
        val child = component("Child") { useFuture { future } }
        val root = ui {
            val (shown, s) = useState(true)
            setShown = s
            if (shown) child(Unit)
        }
        setShown(false)
        frames(root)
        future.complete("late")
        frames(root) // no crash, nothing to update
    }

    @Test
    fun promiseStyleCallbacks() {
        var resolve: ((String) -> Unit)? = null
        var seen: Async<String>? = null
        val root = ui { seen = usePromise { res, _ -> resolve = res } }
        assertTrue(seen!!.loading)
        Thread { resolve!!("from a callback") }.apply { start(); join() } // any thread
        frames(root)
        assertEquals("from a callback", seen!!.value)
        resolve!!("again") // ignored: the promise is settled
        frames(root)
        assertEquals("from a callback", seen!!.value)

        var rejected: Async<String>? = null
        val r2 = ui { rejected = usePromise { _, reject -> reject(RuntimeException("nope")) } }
        frames(r2)
        assertEquals("nope", rejected!!.error?.message)
    }
}
