package dev.nanovoice

import dev.nanovoice.actions.photo.ProductKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ScannedProductTest {
    @Test fun aScannedCodeTellsTheAgentItsKind() {
        assertEquals("[codice a barre: 3600523307500, tipo: beauty]", ScannedProduct.of("3600523307500", ProductKind.Beauty).lineForAgent())
        assertEquals("[codice a barre: 5010394133852, tipo: pet_food]", ScannedProduct.of("5010394133852", ProductKind.PetFood).lineForAgent())
    }

    @Test fun anIsbnIsABookEvenWhenAnotherKindWasChosen() {
        val scanned = ScannedProduct.of("9788804668237", ProductKind.Other)
        assertEquals(ProductKind.Book, scanned.kind)
        assertFalse(scanned.notIsbn)
        assertEquals("[codice a barre: 9788804668237, tipo: book]", scanned.lineForAgent())
    }

    @Test fun aBookWithoutAnIsbnIsLookedUpAmongOtherProductsAndSaysSo() {
        val scanned = ScannedProduct.of("9771234567003", ProductKind.Book)
        assertEquals(ProductKind.Other, scanned.kind)
        assertTrue(scanned.notIsbn)
    }

    @Test fun theKindChosenLastComesFirstAndTheOthersKeepTheirOrder() {
        assertEquals(ProductKind.entries.toList(), productKindsFor("food"))
        assertEquals(
            listOf(ProductKind.Book, ProductKind.Food, ProductKind.Beauty, ProductKind.PetFood, ProductKind.Other),
            productKindsFor("book"),
        )
        assertEquals(ProductKind.entries.toList(), productKindsFor("something else"))
    }
}
