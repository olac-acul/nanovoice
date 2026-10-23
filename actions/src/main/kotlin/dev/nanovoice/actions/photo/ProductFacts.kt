package dev.nanovoice.actions.photo

/** A product barcode looked up in the one database for its kind (see [ProductKind.resolve]). */
object ProductFacts {
    suspend fun lookup(barcode: String, chosen: ProductKind): Result<String> = when (val kind = ProductKind.resolve(barcode, chosen)) {
        ProductKind.Food -> OpenFoodFacts.product(barcode)
        ProductKind.Book -> OpenLibrary.book(barcode)
        else -> OpenProductsFacts.product(barcode, kind)
    }
}
