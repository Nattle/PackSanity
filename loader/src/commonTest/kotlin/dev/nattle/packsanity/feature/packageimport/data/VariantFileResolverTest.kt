package dev.nattle.packsanity.feature.packageimport.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariantFileResolverTest {
    @Test
    fun searchesVariantBeforeRootWithExactSpellingPreferredWithinEach() {
        val resolver =
            VariantFileResolver(
                setOf(
                    "images/Icon.png",
                    "images/icon.png",
                    "compact/images/ICON.png",
                    "data/Readme.txt",
                )
            )

        assertEquals("compact/images/ICON.png", resolver.resolve("images/icon.png", "compact"))
        assertEquals("images/icon.png", resolver.resolve("/images/icon.png", "standard"))
        assertEquals("images/Icon.png", resolver.resolve("images/Icon.png", null))
        assertEquals("images/Icon.png", resolver.resolve("images/iCoN.png", null))
        assertEquals("data/Readme.txt", resolver.resolve("data/readme.txt", "compact"))
    }

    @Test
    fun rejectsUnsafePathsBeforeLookingForRootedAssets() {
        val resolver = VariantFileResolver(setOf("manifest.json", "server/share/manifest.json"))

        assertNull(resolver.resolve("/../manifest.json", null))
        assertNull(resolver.resolve("//server/share/manifest.json", null))
    }
}
