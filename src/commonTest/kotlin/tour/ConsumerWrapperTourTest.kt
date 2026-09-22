package kotlinx.html.tests.tour

import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.consumers.delayed
import kotlinx.html.consumers.onFinalizeMap
import kotlinx.html.tests.tour.RecordedEvent.Content
import kotlinx.html.tests.tour.RecordedEvent.Finalize
import kotlinx.html.tests.tour.RecordedEvent.TagStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A custom consumer wrapper defined in the test sources: forwards everything
 * untouched except text content, which it uppercases. Demonstrates that a
 * TagConsumer wrapper sits between the DSL and the downstream consumer and
 * can rewrite events independently per callback type.
 */
class ShoutContentConsumer<R>(private val downstream: TagConsumer<R>) : TagConsumer<R> by downstream {
    override fun onTagContent(content: CharSequence) {
        downstream.onTagContent(content.toString().uppercase())
    }
}

class ConsumerWrapperTourTest {

    @Test
    fun customWrapperRewritesContentEvents() {
        val recording = RecordingConsumer()
        val events = ShoutContentConsumer(recording).tourFixture()

        // text content was uppercased by the wrapper...
        assertTrue(Content("GO") in events, "wrapper should uppercase 'Go', events:\n${events.joinToString("\n")}")
        assertTrue(Content("Go") !in events)

        // ...but entities and unsafe blocks are different callbacks and pass through untouched
        assertTrue(RecordedEvent.ContentEntity("copy", "&copy;") in events)
        assertTrue(RecordedEvent.ContentUnsafe("<b>raw</b>") in events)

        // structure events are delegated 1:1
        assertEquals(TagStart("div", emptyMap()), events.first())
        assertEquals(Finalize, events.last())
    }

    @Test
    fun delayedFoldsAttributeChangesIntoDownstreamStartEvent() {
        val recording = RecordingConsumer()
        val events = recording.delayed().tourFixture()

        // downstream of DelayedConsumer there are NO AttributeChange events:
        // they are absorbed while the start tag is held back, and are visible
        // as a completed attribute snapshot when onTagStart finally fires
        assertTrue(events.none { it is RecordedEvent.AttributeChange }, "delayed must absorb attribute changes")
        assertEquals(
            TagStart("div", mapOf("id" to "tour-root", "class" to "alpha beta", "data-raw" to "5 < 6 & \"ok\"")),
            events.first(),
        )
        assertEquals(TagStart("input", mapOf("disabled" to "disabled")), events[1])
    }

    @Test
    fun onFinalizeMapTransformsDownstreamResult() {
        val recording = RecordingConsumer()
        val summary = recording.onFinalizeMap { events, partial ->
            "events=${events.size} partial=$partial"
        }.tourFixture()

        assertEquals("events=20 partial=false", summary)
    }

    @Test
    fun wrappersComposeDelayedInsideAndCustomRewriteOutside() {
        val recording = RecordingConsumer()
        val events = ShoutContentConsumer(recording.delayed()).tourFixture()

        // delayed absorbed attribute changes AND the outer wrapper uppercased content
        assertTrue(events.none { it is RecordedEvent.AttributeChange })
        assertTrue(Content("GO") in events)
        assertEquals(Finalize, events.last())
    }
}
