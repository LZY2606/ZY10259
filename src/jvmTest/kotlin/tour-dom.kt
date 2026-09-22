import kotlinx.html.dom.create
import kotlinx.html.dom.document
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DOM (JVM) leg of the TagConsumer tour. See docs/tag-consumer-tour.md.
 *
 * Drives the exact same [tourFixture] as the common recording/stream tests and
 * asserts on DOM nodes directly (never on a serialized string), covering the
 * fourth layer: DOM text nodes and attribute values hold raw, unescaped data.
 */
class TourDomTest {

    @Test
    fun `dom path receives the same dsl events as the recording path`() {
        val recorder = RecordingTagConsumer(document { }.create)

        recorder.tourFixture()

        assertEquals(
            expectedTourEvents,
            recorder.events.rendered(),
            "events observed in front of the JVM DOM backend",
        )
    }

    @Test
    fun `dom stores raw unescaped attribute values`() {
        val root = document { }.create.tourFixture()

        // No escaping happens at the DOM layer; values are stored as delivered by the events.
        assertEquals("a\"b<c>&d", root.getAttribute("data-raw"), "raw attribute value with special characters")
        assertEquals("alpha beta", root.getAttribute("class"), "class set encoded as a space-separated list")
        assertEquals("true", root.getAttribute("contenteditable"), "boolean attribute encoded as \"true\"")

        val input = root.getElementsByTagName("input").item(0) as Element
        assertTrue(input.hasAttribute("checked"), "ticker boolean attribute present after true/false/true toggling")
        assertEquals("checked", input.getAttribute("checked"))

        val button = root.getElementsByTagName("button").item(0) as Element
        assertEquals("submit", button.getAttribute("type"), "enum attribute encoded with its realValue")
        assertEquals("Go", button.textContent)
    }

    @Test
    fun `dom stores raw text nodes and materializes entities and unsafe markup`() {
        val root = document { }.create.tourFixture()

        // Children of <div> in document order; assertions address nodes by index,
        // not by attribute iteration order.
        val children = root.childNodes
        assertEquals(8, children.length, "div child node count")

        assertEquals("input", children.item(0).nodeName)
        assertEquals("button", children.item(1).nodeName)

        val emptyText = children.item(2)
        assertEquals(Node.TEXT_NODE, emptyText.nodeType, "empty text produces an (empty) text node")
        assertEquals("", emptyText.nodeValue)

        val specialText = children.item(3)
        assertEquals(Node.TEXT_NODE, specialText.nodeType)
        assertEquals("5 < 6 & \"quoted\"", specialText.nodeValue, "text node holds raw, unescaped characters")

        val entity = children.item(4)
        assertEquals(Node.ENTITY_REFERENCE_NODE, entity.nodeType, "JVM DOM materializes an entity reference node")
        assertEquals("nbsp", entity.nodeName)

        val unsafeEm = children.item(5)
        assertEquals(Node.ELEMENT_NODE, unsafeEm.nodeType, "unsafe markup is parsed into real elements on JVM DOM")
        assertEquals("em", unsafeEm.nodeName)
        assertEquals("raw", unsafeEm.textContent)

        val afterUnsafe = children.item(6)
        assertEquals(Node.TEXT_NODE, afterUnsafe.nodeType, "content after the unsafe block is a plain text node again")
        assertEquals("<b>escaped again</b>", afterUnsafe.nodeValue)

        assertEquals("span", children.item(7).nodeName)

        // The unsafe block added exactly one element; the later "<b>...</b>" text stayed text.
        assertEquals(1, root.getElementsByTagName("em").length)
        assertEquals(0, root.getElementsByTagName("b").length, "no <b> element may appear after the unsafe block")
    }

    @Test
    fun `exception propagates through the dom backend without pending end events`() {
        val recorder = RecordingTagConsumer(document { }.create)

        assertFailsWith<FixtureException> {
            recorder.throwingTourFixture()
        }

        assertEquals(
            listOf(
                "start(div)",
                "content(\"before\")",
                "start(span)",
            ),
            recorder.events.rendered(),
            "DOM backend observes the same truncated event sequence; finalize is never called",
        )
    }
}
