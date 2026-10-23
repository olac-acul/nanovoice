package dev.nanovoice

import dev.nanovoice.actions.photo.ProductKind

/**
 * A barcode from the scanner with the kind of product the person chose, for the next message. [kind] is the one
 * looked up (an ISBN is a book whatever was chosen); [notIsbn] says the person chose a book but the code is not an
 * ISBN, so it is looked up among the other products.
 */
data class ScannedProduct(val code: String, val kind: ProductKind, val notIsbn: Boolean = false) {
    /** The line the agent's instructions know; the kind is the id its `product` action takes. */
    fun lineForAgent() = "[codice a barre: $code, tipo: ${kind.id}]"

    companion object {
        fun of(code: String, chosen: ProductKind): ScannedProduct {
            val kind = ProductKind.resolve(code, chosen)
            return ScannedProduct(code, kind, notIsbn = chosen == ProductKind.Book && kind != ProductKind.Book)
        }
    }
}

/** The menu's name for each kind of product. */
val ProductKind.label: Int
    get() = when (this) {
        ProductKind.Food -> R.string.product_kind_food
        ProductKind.Beauty -> R.string.product_kind_beauty
        ProductKind.PetFood -> R.string.product_kind_pet_food
        ProductKind.Book -> R.string.product_kind_book
        ProductKind.Other -> R.string.product_kind_other
    }

/** The kinds in the menu: the one chosen last first, then the others in their usual order. */
fun productKindsFor(lastId: String): List<ProductKind> {
    val last = ProductKind.byId(lastId) ?: ProductKind.Food
    return listOf(last) + ProductKind.entries.filter { it != last }
}
