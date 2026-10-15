package com.fliplauncher

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetUpdateQueueTest {
    private val handler = Handler(Looper.getMainLooper())
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun burstInflatesOnlyLatestPayloadForEachWidget() {
        val processed = mutableListOf<Pair<Int, Int>>()
        val queue = WidgetUpdateQueue<Int>(handler) { id, value, publish ->
            publish { processed.add(id to value) }
        }
        repeat(100) { revision -> repeat(12) { id -> queue.submit(id, revision) } }
        assertTrue(processed.isEmpty())
        advance(1000)
        assertEquals((0..11).map { it to 99 }, processed)
    }

    @Test fun busyWidgetDoesNotMoveDeadlineOrStarveAnotherWidget() {
        val processed = mutableListOf<Pair<Int, Int>>()
        val queue = WidgetUpdateQueue<Int>(handler) { id, value, publish -> publish { processed.add(id to value) } }
        queue.submit(1, 0)
        queue.submit(2, 10)
        repeat(9) { advance(10); queue.submit(1, it + 1) }
        advance(10)
        assertEquals(listOf(1 to 9), processed)
        queue.submit(1, 20)
        advance(16)
        assertEquals(listOf(1 to 9, 2 to 10), processed)
        advance(16)
        assertEquals(listOf(1 to 9, 2 to 10, 1 to 20), processed)
    }

    @Test fun removalAndRestoreRetainPreventPendingPublication() {
        val processed = mutableListOf<Int>()
        val queue = WidgetUpdateQueue<Int>(handler) { id, _, publish -> publish { processed.add(id) } }
        queue.submit(1, 1); queue.submit(2, 2); queue.submit(3, 3)
        queue.cancel(1)
        queue.retain(setOf(3))
        advance(1000)
        assertEquals(listOf(3), processed)
    }

    @Test fun supersededCompletionCannotOverwriteNewerContent() {
        val published = mutableListOf<Int>()
        lateinit var queue: WidgetUpdateQueue<Int>
        queue = WidgetUpdateQueue(handler) { id, value, publish ->
            if (value == 1) queue.submit(id, 2)
            publish { published.add(value) }
        }
        queue.submit(1, 1)
        advance(1000)
        assertEquals(listOf(2), published)
    }

    @Test fun removalDuringWorkRejectsCompletionEvenWhenIdIsReused() {
        val published = mutableListOf<Int>()
        lateinit var queue: WidgetUpdateQueue<Int>
        queue = WidgetUpdateQueue(handler) { id, value, publish ->
            if (value == 1) { queue.cancel(id); queue.submit(id, 2) }
            publish { published.add(value) }
        }
        queue.submit(1, 1)
        advance(1000)
        assertEquals(listOf(2), published)
    }
}
