package dev.nanovoice.actions.photo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Open Library (Internet Archive), the open book catalogue: a book from the ISBN in its barcode, in one request. Only
 * the number leaves the phone.
 */
object OpenLibrary {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun book(isbn: String): Result<String> = runCatching {
        require(ProductKind.isIsbn(isbn)) { "un ISBN ha 13 cifre e comincia con 978 o 979" }
        val body = openDataGet("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data", "Open Library")
        if (body == null) notFound(isbn) else describe(isbn, body)
    }

    /** A short summary for the agent, or that the book is unknown (Open Library answers {} for an ISBN it has not). */
    fun describe(isbn: String, body: String): String {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return notFound(isbn)
        val b = root["ISBN:$isbn"] as? JsonObject ?: return notFound(isbn)
        val parts = mutableListOf<String>()
        b.string("title")?.let { title ->
            parts += "titolo: " + thirdPartyText(b.string("subtitle")?.let { "$title. $it" } ?: title, 160)
        }
        names(b["authors"])?.let { parts += "autori: $it" }
        names(b["publishers"])?.let { parts += "editore: $it" }
        b.string("publish_date")?.let { parts += "pubblicato: ${thirdPartyText(it, 40)}" }
        (b["number_of_pages"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { parts += "pagine: $it" }
        if (parts.isEmpty()) return notFound(isbn)
        return "Open Library, ISBN $isbn: " + parts.joinToString("; ")
    }

    private fun notFound(isbn: String) = "Open Library non conosce il libro con ISBN $isbn."

    private fun names(e: Any?): String? = (e as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() } }
        .map { thirdPartyText(it, 60) }
        .take(4)
        .takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
