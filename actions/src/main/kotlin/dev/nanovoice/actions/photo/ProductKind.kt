package dev.nanovoice.actions.photo

/**
 * What a scanned product is. Each kind has its own open database, so the person's choice means one lookup instead of
 * trying them all. The ids are the ones the agent's `product` action uses.
 */
enum class ProductKind(val id: String, val service: String) {
    Food("food", "Open Food Facts"),
    Beauty("beauty", "Open Beauty Facts"),
    PetFood("pet_food", "Open Pet Food Facts"),
    Book("book", "Open Library"),
    Other("other", "Open Products Facts"),
    ;

    companion object {
        fun byId(id: String?): ProductKind? = entries.firstOrNull { it.id == id }

        /** A book's barcode is its ISBN: 13 digits starting with 978 or 979. */
        fun isIsbn(code: String) = code.length == 13 && (code.startsWith("978") || code.startsWith("979")) && code.all { it in '0'..'9' }

        /** The kind actually looked up: an ISBN is always a book, and a "book" without an ISBN is looked up as other. */
        fun resolve(code: String, chosen: ProductKind): ProductKind = when {
            isIsbn(code) -> Book
            chosen == Book -> Other
            else -> chosen
        }
    }
}
