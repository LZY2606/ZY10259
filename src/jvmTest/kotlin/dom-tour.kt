package kotlinx.html.tests

import kotlinx.html.dom.create
import kotlinx.html.dom.serialize
import kotlinx.html.tests.tour.tourExceptionFixture
import kotlinx.html.tests.tour.tourFixture
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JVM DOM backend driven by the SAME fixture as the recording and stream
 * tests. DOM is layer 4 of the model: text and attribute values are stored
 * raw (unescaped) in nodes; escaping only reappears if the tree is serialized.
 *
 * Assertions use getAttribute(name) / node types instead of iterating
 * element.attributes, so nothing depends on attribute iteration order.
 */
class DomTourTest {

    private fun newDocument() = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    @Test
    fun `fixture builds a DOM tree with raw unescaped values`() {
        val document = newDocument()
        val root = document.create.tourFixture()

        assertEquals("tour-root", root.getAttribute("id"))
        assertEquals("alpha beta", root.getAttribute("class"))
        // layer 4: the attribute value is stored RAW, exactly as the DSL passed it
        assertEquals("5 < 6 & \"ok\"", root.getAttribute("data-raw"))

        val input = root.getElementsByTagName("input").item(0) as Element
        assertEquals("disabled", input.getAttribute("disabled")) // boolean ticker

        val button = root.getElementsByTagName("button").item(0) as Element
        assertEquals("submit", button.getAttribute("type")) // enum attribute
        assertEquals("Go", button.textContent)
    }

    @Test
    fun `span children show text, entity, unsafe and post-unsafe nodes distinctly`() {
        val document = newDocument()
        val root = document.create.tourFixture()
        val span = root.getElementsByTagName("span").item(0) as Element

        val children = span.childNodes.let { nodes -> (0 until nodes.length).map(nodes::item) }

        // text nodes hold RAW characters; no escaping exists at this layer
        assertEquals("Fish & Chips <3 \"quoted\"", children[0].nodeValue)
        assertEquals(Node.TEXT_NODE, children[0].nodeType)

        // the empty text event becomes an empty text node
        assertEquals("", children[1].nodeValue)
        assertEquals(Node.TEXT_NODE, children[1].nodeType)

        // JVM DOM represents an entity as an EntityReference node;
        // the JS backend cannot do this (browsers dropped createEntityReference)
        // and inserts a decoded text node instead - see docs/consumer-events-tour.md
        assertEquals(Node.ENTITY_REFERENCE_NODE, children[2].nodeType)
        assertEquals("copy", children[2].nodeName)

        // unsafe content is parsed as XML by the JVM backend and becomes real elements
        assertEquals(Node.ELEMENT_NODE, children[3].nodeType)
        assertEquals("b", (children[3] as Element).tagName)
        assertEquals("raw", children[3].textContent)

        // content after the unsafe block is an ordinary text node again
        assertEquals("still <escaped> & safe", children[4].nodeValue)
        assertEquals(Node.TEXT_NODE, children[4].nodeType)
    }

    @Test
    fun `serializing the DOM re-escapes what the nodes hold raw`() {
        val document = newDocument()
        val root = document.create.tourFixture()

        // The JDK transformer uses the HTML output method (see Writer.write in
        // src/jvmMain/kotlin/dom-jvm.kt). Its escaping differs from HTMLStreamBuilder:
        // attribute values escape & and " but NOT <; text escapes <, > and &.
        val serialized = root.serialize(prettyPrint = false)
        assertTrue(
            serialized.contains("data-raw=\"5 < 6 &amp; &quot;ok&quot;\""),
            "attribute value must escape & and quotes (but not <): $serialized",
        )
        assertTrue(serialized.contains("Fish &amp; Chips &lt;3"), "text must escape & and <: $serialized")
        assertTrue(serialized.contains("still &lt;escaped&gt; &amp; safe"), "text must be escaped: $serialized")
    }

    @Test
    fun `exception fixture leaves the JVM DOM builder without a result`() {
        val document = newDocument()

        // Current contract, JVM DOM backend: onTagEnd never ran for any tag, so
        // lastLeaved is null and finalize() throws instead of returning a partial tree.
        // The stream backend under the same fixture returns partial output instead.
        assertFailsWith<IllegalStateException> {
            document.create.tourExceptionFixture()
        }
    }
}
