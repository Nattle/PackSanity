package dev.nattle.packsanity.feature.packruntime.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerItemTest {
    @Test
    fun imageModifierPipelinePreservesOverlayOrderAndExpandsDisabledFilters() {
        assertEquals(
            listOf(
                TrackerImageModifier.Overlay("images/badge.png"),
                TrackerImageModifier.Filter("brightness", listOf("0.5")),
            ),
            "overlay|images/badge.png,brightness|0.5".toTrackerImageModifiers(),
        )
        assertEquals(
            listOf(
                TrackerImageModifier.Filter("saturation", listOf("0", "bt601")),
                TrackerImageModifier.Filter("brightness", listOf("0.75")),
            ),
            "@disabled".toTrackerImageModifiers("saturation|0|bt601, brightness|0.75"),
        )
    }

    @Test
    fun overlayDisabledFilterExpansionIncludesReferencedAssets() {
        val modifiers = "overlay|images/badge.png|@disabled"

        assertEquals(
            listOf("images/badge.png"),
            modifiers.modifierOverlayPaths("grayscale,dim"),
        )
        assertEquals(
            TrackerImageModifier.Overlay(
                path = "images/badge.png",
                filters =
                    listOf(
                        TrackerImageModifier.Filter("grayscale"),
                        TrackerImageModifier.Filter("dim"),
                    ),
            ),
            modifiers.toTrackerImageModifiers("grayscale,dim").single(),
        )
    }

    @Test
    fun stageCodeInheritanceMatchesThePublishedMixedChain() {
        val item =
            TrackerItem(
                id = "milestone",
                displayName = "Milestone",
                codes = setOf("a", "b", "c", "d", "e", "f"),
                imagePath = null,
                kind = TrackerItemKind.Progressive,
                maxQuantity = null,
                initiallyActive = false,
                stages =
                    listOf(
                        TrackerItemStage(null, setOf("a"), null, inheritCodes = false),
                        TrackerItemStage(null, setOf("b"), null, inheritCodes = false),
                        TrackerItemStage(null, setOf("c"), null),
                        TrackerItemStage(null, setOf("d"), null),
                        TrackerItemStage(null, setOf("e"), null, inheritCodes = false),
                        TrackerItemStage(null, setOf("f"), null),
                    ),
            )

        assertEquals(setOf("a"), item.codesProvidedAtStage(0))
        assertEquals(setOf("b"), item.codesProvidedAtStage(1))
        assertEquals(setOf("b", "c"), item.codesProvidedAtStage(2))
        assertEquals(setOf("b", "c", "d"), item.codesProvidedAtStage(3))
        assertEquals(setOf("e"), item.codesProvidedAtStage(4))
        assertEquals(setOf("e", "f"), item.codesProvidedAtStage(5))
    }

    @Test
    fun falseCurrentStageStillGetsPreviousCodesWhenItWasReachedFromALaterStage() {
        val item =
            TrackerItem(
                id = "milestone",
                displayName = "Milestone",
                codes = setOf("get", "buy"),
                imagePath = null,
                kind = TrackerItemKind.Progressive,
                maxQuantity = null,
                initiallyActive = false,
                stages =
                    listOf(
                        TrackerItemStage(null, setOf("get"), null, inheritCodes = false),
                        TrackerItemStage(null, setOf("buy"), null),
                    ),
            )

        assertEquals(setOf("get"), item.codesProvidedAtStage(0))
        assertEquals(setOf("get", "buy"), item.codesProvidedAtStage(1))
    }
}
