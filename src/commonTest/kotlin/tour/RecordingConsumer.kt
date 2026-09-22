package kotlinx.html.tests.tour

import kotlinx.html.DefaultUnsafe
import kotlinx.html.Entities
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.Unsafe
import kotlinx.html.org.w3c.dom.events.Event

/**
 * One recorded [TagConsumer] callback. The tour tests assert exact sequences of these events,
 * and docs/consumer-events-tour.md prints the same sequences.
 *
 * Content is stored exactly as the consumer receives it: raw and unescaped. This is layer 2
 * of the four-layer model (DSL text -> consumer events -> stream escaping -> DOM nodes).
 */
sealed interface RecordedEvent {
    /** @param attributes snapshot of [Tag.attributes] at the moment [TagConsumer.onTagStart] fired */
    data class TagStart(val tagName: String, val attributes: Map<String, String>) : RecordedEvent
    data class AttributeChange(val tagName: String, val attribute: String, val value: String?) : RecordedEvent
    data class Content(val content: String) : RecordedEvent
    data class ContentEntity(val entityName: String, val entityText: String) : RecordedEvent
    data class ContentUnsafe(val raw: String) : RecordedEvent
    data class Comment(val content: String) : RecordedEvent
    data class EventHandler(val tagName: String, val event: String) : RecordedEvent
    data class TagEnd(val tagName: String) : RecordedEvent
    object Finalize : RecordedEvent
}

/**
 * A terminal [TagConsumer] that records every callback as a [RecordedEvent] instead of
 * producing markup or DOM nodes. `finalize()` appends [RecordedEvent.Finalize] and returns
 * the whole log, so `consumer.someTag { }` (which finalizes) hands the log back directly.
 *
 * Lives in commonTest so JVM, JS, Wasm and Native test runs all share the same expectations.
 */
class RecordingConsumer : TagConsumer<List<RecordedEvent>> {
    private val log = ArrayList<RecordedEvent>()

    val events: List<RecordedEvent>
        get() = log.toList()

    override fun onTagStart(tag: Tag) {
        // Snapshot into a LinkedHashMap: equality is order-insensitive, so tests never
        // depend on attribute iteration order of the underlying map implementation.
        log += RecordedEvent.TagStart(tag.tagName, LinkedHashMap(tag.attributes))
    }

    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {
        log += RecordedEvent.AttributeChange(tag.tagName, attribute, value)
    }

    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {
        log += RecordedEvent.EventHandler(tag.tagName, event)
    }

    override fun onTagContent(content: CharSequence) {
        log += RecordedEvent.Content(content.toString())
    }

    override fun onTagContentEntity(entity: Entities) {
        log += RecordedEvent.ContentEntity(entity.name, entity.text)
    }

    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) {
        // Materialize the unsafe block the same way JSDOMBuilder does (DefaultUnsafe),
        // instead of appending raw to a stream like HTMLStreamBuilder.
        val unsafe = DefaultUnsafe()
        unsafe.block()
        log += RecordedEvent.ContentUnsafe(unsafe.toString())
    }

    override fun onTagComment(content: CharSequence) {
        log += RecordedEvent.Comment(content.toString())
    }

    override fun onTagEnd(tag: Tag) {
        log += RecordedEvent.TagEnd(tag.tagName)
    }

    override fun finalize(): List<RecordedEvent> {
        log += RecordedEvent.Finalize
        return events
    }

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = null
}
