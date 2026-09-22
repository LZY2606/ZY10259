package kotlinx.html.tests.tour

import kotlinx.html.tests.tour.RecordedEvent.AttributeChange
import kotlinx.html.tests.tour.RecordedEvent.Content
import kotlinx.html.tests.tour.RecordedEvent.ContentEntity
import kotlinx.html.tests.tour.RecordedEvent.ContentUnsafe
import kotlinx.html.tests.tour.RecordedEvent.Finalize
import kotlinx.html.tests.tour.RecordedEvent.TagEnd
import kotlinx.html.tests.tour.RecordedEvent.TagStart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden event sequence for [tourFixture] as observed by a terminal consumer.
 * This is layer 2 of the four-layer model: exactly what the DSL hands to the
 * TagConsumer, before any backend-specific serialization. The same sequence is
 * printed in docs/consumer-events-tour.md.
 */
class RecordingConsumerTourTest {

    @Test
    fun fixtureEmitsTheGoldenEventSequence() {
        val events = RecordingConsumer().tourFixture()

        assertEquals(
            listOf(
                // attributes are NOT part of onTagStart here: each assignment inside
                // the DSL block arrives as its own onTagAttributeChange
                TagStart("div", emptyMap()),
                AttributeChange("div", "id", "tour-root"),
                AttributeChange("div", "class", "alpha beta"), // Set<String> encoded with a single space
                AttributeChange("div", "data-raw", "5 < 6 & \"ok\""), // raw, NOT escaped at this layer

                TagStart("input", emptyMap()),
                AttributeChange("input", "disabled", "disabled"), // boolean ticker: name as value
                TagEnd("input"), // empty tag still gets an end event; the backend decides how to render it

                TagStart("button", emptyMap()),
                AttributeChange("button", "type", "submit"), // enum encoded via AttributeEnum.realValue
                Content("Go"),
                TagEnd("button"),

                TagStart("span", emptyMap()),
                Content("Fish & Chips <3 \"quoted\""), // special characters travel unescaped
                Content(""), // empty text is still an event
                ContentEntity("copy", "&copy;"),
                ContentUnsafe("<b>raw</b>"),
                Content("still <escaped> & safe"), // unsafe does not affect later content events
                TagEnd("span"),

                TagEnd("div"),
                Finalize,
            ).joinToString("\n"),
            events.joinToString("\n"),
        )
    }
}
