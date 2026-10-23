package dev.nanovoice.actions.photo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The sister databases of Open Food Facts, by the same non-profit and with the same API: Open Beauty Facts (cosmetics
 * and hygiene), Open Pet Food Facts and Open Products Facts (everything else). Smaller than Open Food Facts: many
 * products are missing. Only the number leaves the phone.
 */
object OpenProductsFacts {
    private val HOSTS = mapOf(
        ProductKind.Beauty to "world.openbeautyfacts.org",
        ProductKind.PetFood to "world.openpetfoodfacts.org",
        ProductKind.Other to "world.openproductsfacts.org",
    )
    private val FIELDS = listOf(
        "product_name_it", "product_name", "generic_name_it", "generic_name", "brands", "quantity",
        "categories_tags", "labels_tags", "ingredients_text_it", "ingredients_text",
    ).joinToString(",")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_INGREDIENTS = 400

    suspend fun product(barcode: String, kind: ProductKind): Result<String> = runCatching {
        require(OpenFoodFacts.isBarcode(barcode)) { "il codice a barre deve avere da 8 a 14 cifre" }
        val host = requireNotNull(HOSTS[kind]) { "${kind.service} non è uno dei database dei prodotti" }
        val body = openDataGet("https://$host/api/v2/product/$barcode.json?fields=$FIELDS", kind.service)
        if (body == null) notFound(kind, barcode) else describe(kind, barcode, body)
    }

    /** A short summary for the agent, or that the product is unknown. */
    fun describe(kind: ProductKind, barcode: String, body: String): String {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return notFound(kind, barcode)
        val p = root["product"] as? JsonObject
        if (p == null || (root["status"] as? JsonPrimitive)?.contentOrNull == "0") return notFound(kind, barcode)
        val parts = mutableListOf<String>()
        (p.string("product_name_it") ?: p.string("product_name"))?.let { parts += "prodotto: ${thirdPartyText(it, 120)}" }
        (p.string("generic_name_it") ?: p.string("generic_name"))?.let { parts += "tipo: ${thirdPartyText(it, 160)}" }
        p.string("brands")?.let { parts += "marca: ${thirdPartyText(it, 80)}" }
        p.string("quantity")?.let { parts += "quantità: ${thirdPartyText(it, 40)}" }
        tags(p["categories_tags"])?.let { parts += "categorie: $it" }
        tags(p["labels_tags"])?.let { parts += "etichette: $it" }
        (p.string("ingredients_text_it") ?: p.string("ingredients_text"))?.let {
            parts += "ingredienti: " + thirdPartyText(it, MAX_INGREDIENTS)
        }
        if (parts.isEmpty()) return notFound(kind, barcode)
        return "${kind.service}, codice $barcode: " + parts.joinToString("; ")
    }

    private fun notFound(kind: ProductKind, barcode: String) =
        "${kind.service} non conosce il prodotto con codice $barcode (questo database ha meno prodotti di Open Food Facts)."

    /** "en:dog-food" -> "dog food", without the database's own tags ("open-beauty-facts"); the agent translates. */
    private fun tags(e: Any?): String? = (e as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.substringAfter(':')?.replace('-', ' ') }
        .filterNot { it.endsWith(" facts", ignoreCase = true) }
        .map { thirdPartyText(withoutEntities(it), 40) }
        .take(8)
        .takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let(::withoutEntities)?.trim()?.takeIf { it.isNotEmpty() }
}
