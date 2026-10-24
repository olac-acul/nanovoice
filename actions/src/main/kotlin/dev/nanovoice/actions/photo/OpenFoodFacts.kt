package dev.nanovoice.actions.photo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * Open Food Facts, the open non-profit food database: a product from its barcode. Reads need no account, only an
 * application User-Agent. Only the number leaves the phone (the barcode is read on the phone, see Photos).
 */
object OpenFoodFacts {
    private val FIELDS = listOf(
        "product_name_it", "product_name", "brands", "quantity", "nutriscore_grade", "nova_group",
        "ingredients_text_it", "ingredients_text", "allergens_tags", "traces_tags", "nutriments",
    ).joinToString(",")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_INGREDIENTS = 400

    fun isBarcode(code: String) = code.length in 8..14 && code.all { it in '0'..'9' }

    suspend fun product(barcode: String): Result<String> = runCatching {
        require(isBarcode(barcode)) { "il codice a barre deve avere da 8 a 14 cifre" }
        openDataGet("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS", "Open Food Facts")
            ?.let { describe(barcode, it) } ?: notFound(barcode)
    }

    /** A short summary for the agent, or that the product is unknown. */
    fun describe(barcode: String, body: String): String {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return notFound(barcode)
        val status = (root["status"] as? JsonPrimitive)?.contentOrNull
        val p = root["product"] as? JsonObject
        if (p == null || status == "0") return notFound(barcode)
        val parts = mutableListOf<String>()
        (p.string("product_name_it") ?: p.string("product_name"))?.let { parts += "prodotto: ${thirdPartyText(it, 120)}" }
        p.string("brands")?.let { parts += "marca: ${thirdPartyText(it, 80)}" }
        p.string("quantity")?.let { parts += "quantità: ${thirdPartyText(it, 40)}" }
        p.string("nutriscore_grade")?.takeIf { it.length == 1 && it[0] in 'a'..'e' }?.let { parts += "Nutri-Score: ${it.uppercase(Locale.ROOT)}" }
        p.string("nova_group")?.takeIf { it in setOf("1", "2", "3", "4") }?.let { parts += "gruppo NOVA: $it" }
        nutrition(p["nutriments"] as? JsonObject)?.let { parts += "per 100 g: $it" }
        tags(p["allergens_tags"])?.let { parts += "allergeni: $it" }
        tags(p["traces_tags"])?.let { parts += "può contenere tracce di: $it" }
        (p.string("ingredients_text_it") ?: p.string("ingredients_text"))?.let {
            parts += "ingredienti: " + thirdPartyText(it, MAX_INGREDIENTS)
        }
        if (parts.isEmpty()) return notFound(barcode)
        return "Open Food Facts, codice $barcode: " + parts.joinToString("; ")
    }

    private fun notFound(barcode: String) = "Open Food Facts non conosce il prodotto con codice $barcode."

    private fun nutrition(n: JsonObject?): String? {
        n ?: return null
        fun v(key: String) = (n[key] as? JsonPrimitive)?.doubleOrNull
        val items = listOfNotNull(
            v("energy-kcal_100g")?.let { "${fmt(it)} kcal" },
            v("fat_100g")?.let { "grassi ${fmt(it)} g" },
            v("sugars_100g")?.let { "zuccheri ${fmt(it)} g" },
            v("proteins_100g")?.let { "proteine ${fmt(it)} g" },
            v("salt_100g")?.let { "sale ${fmt(it)} g" },
        )
        return items.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** "en:milk" -> "milk": the agent translates. */
    private fun tags(e: Any?): String? = (e as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.substringAfter(':')?.replace('-', ' ')?.let { t -> thirdPartyText(t, 40) } }
        .take(12)
        .takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ITALY, "%.1f", d)

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let(::withoutEntities)?.trim()?.takeIf { it.isNotEmpty() }
}
