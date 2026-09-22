import kotlinx.html.DefaultUnsafe
import kotlinx.html.Entities
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.Unsafe
import kotlinx.html.org.w3c.dom.events.Event

/**
 * A single event observed by [RecordingTagConsumer].
 *
 * Events render themselves as compact one-line strings so that test failures
 * read like a script of what the DSL emitted, e.g.:
 * `start(div)`, `attr(input, checked="checked")`, `content("Go")`, `end(div)`, `finalize`.
 */
sealed class RecordedEvent {
    abstract override fun toString(): String

    /** [TagConsumer.onTagStart]; [attributes] is a snapshot of the tag's attribute map at that moment. */
    data class TagStart(val tagName: String, val attributes: Map<String, String>) : RecordedEvent() {
        override fun toString(): String = when {
            attributes.isEmpty() -> "start($tagName)"
            else -> attributes.entries.joinToString(", ", "start($tagName, ", ")") { (key, value) ->
                "$key=${render(value)}"
            }
        }
    }

    /** [TagConsumer.onTagAttributeChange]; [value] is `null` when the attribute was removed. */
    data class AttributeChange(val tagName: String, val attribute: String, val value: String?) : RecordedEvent() {
        override fun toString(): String = "attr($tagName, $attribute=${value?.let(::render) ?: "null"})"
    }

    /** [TagConsumer.onTagEvent]. The handler itself is not recorded. */
    data class EventHandler(val tagName: String, val event: String) : RecordedEvent() {
        override fun toString(): String = "event($tagName, $event)"
    }

    /** [TagConsumer.onTagEnd]. */
    data class TagEnd(val tagName: String) : RecordedEvent() {
        override fun toString(): String = "end($tagName)"
    }

    /** [TagConsumer.onTagContent]. [text] is the raw, unescaped text exactly as the consumer received it. */
    data class Content(val text: String) : RecordedEvent() {
        override fun toString(): String = "content(${render(text)})"
    }

    /** [TagConsumer.onTagContentEntity]. */
    data class Entity(val name: String) : RecordedEvent() {
        override fun toString(): String = "entity($name)"
    }

    /** [TagConsumer.onTagContentUnsafe]; [text] is what the unsafe block would emit, captured via [DefaultUnsafe]. */
    data class UnsafeContent(val text: String) : RecordedEvent() {
        override fun toString(): String = "unsafe(${render(text)})"
    }

    /** [TagConsumer.onTagComment]. */
    data class Comment(val text: String) : RecordedEvent() {
        override fun toString(): String = "comment(${render(text)})"
    }

    /** [TagConsumer.finalize]. */
    data object Finalized : RecordedEvent() {
        override fun toString(): String = "finalize"
    }

    companion object {
        internal fun render(value: String): String = buildString(value.length + 2) {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * A [TagConsumer] that records every callback as a [RecordedEvent] in [events]
 * and forwards the call unchanged to [downstream].
 *
 * Wrap any backend (stream, DOM, or [BlackHoleTagConsumer] for a pure recording)
 * to observe exactly which events a DSL block produces, without inferring them
 * from a serialized HTML string.
 */
class RecordingTagConsumer<R>(val downstream: TagConsumer<R>) : TagConsumer<R> {
    val events = mutableListOf<RecordedEvent>()

    override fun onTagStart(tag: Tag) {
        events += RecordedEvent.TagStart(tag.tagName, tag.attributesEntries.associate { it.key to it.value })
        downstream.onTagStart(tag)
    }

    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {
        events += RecordedEvent.AttributeChange(tag.tagName, attribute, value)
        downstream.onTagAttributeChange(tag, attribute, value)
    }

    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {
        events += RecordedEvent.EventHandler(tag.tagName, event)
        downstream.onTagEvent(tag, event, value)
    }

    override fun onTagEnd(tag: Tag) {
        events += RecordedEvent.TagEnd(tag.tagName)
        downstream.onTagEnd(tag)
    }

    override fun onTagContent(content: CharSequence) {
        events += RecordedEvent.Content(content.toString())
        downstream.onTagContent(content)
    }

    override fun onTagContentEntity(entity: Entities) {
        events += RecordedEvent.Entity(entity.name)
        downstream.onTagContentEntity(entity)
    }

    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) {
        events += RecordedEvent.UnsafeContent(DefaultUnsafe().apply(block).toString())
        downstream.onTagContentUnsafe(block)
    }

    override fun onTagComment(content: CharSequence) {
        events += RecordedEvent.Comment(content.toString())
        downstream.onTagComment(content)
    }

    override fun finalize(): R {
        events += RecordedEvent.Finalized
        return downstream.finalize()
    }

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = downstream.head
}

/**
 * A sink consumer that accepts every event and does nothing.
 * Used as the downstream of [RecordingTagConsumer] for the pure recording path.
 */
class BlackHoleTagConsumer : TagConsumer<String> {
    override fun onTagStart(tag: Tag) {}
    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {}
    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {}
    override fun onTagEnd(tag: Tag) {}
    override fun onTagContent(content: CharSequence) {}
    override fun onTagContentEntity(entity: Entities) {}
    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) {}
    override fun onTagComment(content: CharSequence) {}
    override fun finalize(): String = "blackhole"

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = null
}

/** Renders [events] for comparison with a list of expected event strings. */
fun List<RecordedEvent>.rendered(): List<String> = map { it.toString() }
