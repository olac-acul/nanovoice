package dev.nanovoice

import android.content.Context
import dev.nanovoice.actions.photo.OpenFoodFacts
import dev.nanovoice.actions.photo.PlantNet
import dev.nanovoice.actions.photo.ProductFacts
import dev.nanovoice.actions.photo.ProductKind
import dev.nanovoice.agent.ActionOutcome
import dev.nanovoice.agent.AgentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * The agent's photo tools. Each works on the photo sent with the request the agent is answering, which the client
 * keeps while that request is open: never an older photo, and nothing at all for a request without one (a turn the
 * agent started on its own, or text injected into what it reads, finds no photo to send out). Which service gets it is
 * said by the line the server tool sends first ("Chiedo a Pl@ntNet...").
 */
class PhotoActions(private val context: Context, private val plantNetKey: () -> String) {
    suspend fun handle(name: String, args: JsonObject, photo: AgentImage?): ActionOutcome = when (name) {
        "identify_plant" -> {
            val key = plantNetKey().trim()
            when {
                photo == null -> ActionOutcome(false, NO_PHOTO)
                key.isEmpty() -> ActionOutcome(false, "manca la chiave di Pl@ntNet: si inserisce nell'app in Impostazioni → Foto")
                else -> PlantNet.identify(key, photo.bytes, args.text("organ") ?: "auto").outcome("Pl@ntNet")
            }
        }
        "food_product" -> {
            val code = args.text("barcode")
            if (code == null || !OpenFoodFacts.isBarcode(code)) ActionOutcome(false, "il codice a barre deve avere da 8 a 14 cifre")
            else OpenFoodFacts.product(code).outcome("Open Food Facts")
        }
        "product" -> {
            val code = args.text("barcode")
            val kind = ProductKind.byId(args.text("kind"))
            when {
                code == null || !OpenFoodFacts.isBarcode(code) -> ActionOutcome(false, "il codice a barre deve avere da 8 a 14 cifre")
                kind == null -> ActionOutcome(false, "kind deve essere uno di ${ProductKind.entries.joinToString { it.id }}")
                else -> ProductFacts.lookup(code, kind).outcome(ProductKind.resolve(code, kind).service)
            }
        }
        "image_search" -> {
            val engine = ImageSearchActivity.Engine.byId(args.text("engine"))
            when {
                photo == null -> ActionOutcome(false, NO_PHOTO)
                engine == null -> ActionOutcome(false, "engine deve essere tineye")
                else -> runCatching { withContext(Dispatchers.Main) { ImageSearchActivity.open(context, photo, engine) } }
                    .fold({ ActionOutcome(true, "${engine.label} è aperto sul telefono con la foto: i risultati li vede la persona") }) {
                        ActionOutcome(false, "non riesco ad aprire ${engine.label}: ${it.javaClass.simpleName}")
                    }
            }
        }
        else -> ActionOutcome(false, "azione sconosciuta: $name")
    }

    private fun Result<String>.outcome(service: String) = fold(
        { ActionOutcome(true, it) },
        { e -> ActionOutcome(false, if (e is IOException) "$service non è raggiungibile (${e.javaClass.simpleName})" else e.message ?: e.javaClass.simpleName) },
    )

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        // food_product: the older lookup, food only, still answered for a server that has not got `product`.
        val NAMES = setOf("identify_plant", "product", "food_product", "image_search")
        private const val NO_PHOTO = "questa richiesta non ha una foto: la persona deve mandarla insieme alla domanda"
    }
}
