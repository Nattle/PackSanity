package dev.nattle.packsanity.feature.packruntime.data

import dev.nattle.packsanity.core.json.PopJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopTrackerItemGridMarginTest {
    @Test
    fun transparentSingleChildWrapperTranslatesBackgroundWithoutOwningIt() {
        val parser = PopTrackerItemGridParser(emptyMap())
        val wrapper =
            PopJson.parseToJsonElement(
                """{"type":"group","margin":10,"content":
                  {"type":"itemgrid","item_size":32,"background":"80FF0000","rows":[["a"]]}}
                """
            )
        val grid = parser.parse(wrapper)!!
        assertNull(grid.widgetBackgroundIndex)
        assertEquals(62, grid.width)
        assertEquals(62, grid.height)
        val background = grid.backgrounds.single()
        assertEquals(10, background.x)
        assertEquals(10, background.y)
        assertEquals(42, background.width)
        assertEquals(42, background.height)
        assertEquals(15, grid.cells.single().x)
        assertEquals(15, grid.cells.single().y)

        for (orientation in listOf("horizontal", "vertical")) {
            val composed =
                parser.parse(
                    PopJson.parseToJsonElement(
                        """{"type":"array","orientation":"$orientation","content":[$wrapper,
                          {"type":"itemgrid","item_size":100,"rows":[["b"]]}]}
                        """
                    )
                )!!
            assertEquals(background, composed.backgrounds.single())
        }
    }

    @Test
    fun coloredSingleChildWrapperOwnsOnlyItsNewBackground() {
        val grid =
            PopTrackerItemGridParser(emptyMap())
                .parse(
                    PopJson.parseToJsonElement(
                        """{"type":"group","margin":10,"background":"80000000","content":
                          {"type":"itemgrid","background":"80FF0000","rows":[["a"]]}}
                        """
                    )
                )!!
        assertEquals(0, grid.widgetBackgroundIndex)
        assertEquals(listOf(0, 10), grid.backgrounds.map { it.x })
        assertEquals(listOf(0, 10), grid.backgrounds.map { it.y })
        assertEquals(listOf(62, 42), grid.backgrounds.map { it.width })
        assertEquals(listOf(62, 42), grid.backgrounds.map { it.height })
    }

    @Test
    fun stretchesSiblingBackgroundsToRowHeightWithoutResizingSprites() {
        val grid =
            PopTrackerItemGridParser(emptyMap())
                .parse(
                    PopJson.parseToJsonElement(
                        """
          {"type":"array","orientation":"horizontal","content":[
            {"type":"itemgrid","item_size":44,"item_margin":"-1,0","background":"111111","rows":[["a","b"]]},
            {"type":"itemgrid","item_size":44,"margin":"0,0","background":"222222","rows":[["c","d"]]}
          ]}
        """
                    )
                )!!
        assertEquals(54, grid.height)
        assertEquals(listOf(54, 54), grid.backgrounds.map { it.height })
        assertEquals(listOf(0, 0), grid.backgrounds.map { it.y })
        assertEquals(listOf(0, 0, 5, 5), grid.cells.map { it.y })
        assertTrue(grid.cells.all { it.height == 44 && it.width == 44 })
    }

    @Test
    fun paintsPaddingAndBlankCellsWithTheWholeGridBackground() {
        val grid =
            PopTrackerItemGridParser(emptyMap())
                .parse(
                    PopJson.parseToJsonElement(
                        """
            {"type":"array","orientation":"vertical","background":"111111","content":[
              {"type":"array","orientation":"horizontal","margin":"-2,0","content":[
                {"type":"itemgrid","margin":"-2,0","background":"3A3127","item_size":44,"rows":[["first"]]},
                {"type":"itemgrid","margin":"-3,0","background":"0C2800","item_size":44,"rows":[["a","","c"]]}
              ]},
              {"type":"itemgrid","margin":"-3,0","background":"440442","item_size":44,"rows":[["next"]]}
            ]}
        """
                    )
                )!!
        assertEquals(4, grid.backgrounds.size)
        assertEquals(
            listOf("111111", "3A3127", "0C2800", "440442"),
            grid.backgrounds.map { it.color },
        )
        assertEquals(listOf(0, -2, 48, 0), grid.backgrounds.map { it.x })
        assertEquals(listOf(0, 0, 0, 54), grid.backgrounds.map { it.y })
        assertEquals(listOf(202, 50, 156, 202), grid.backgrounds.map { it.width })
        assertEquals(listOf(108, 54, 54, 54), grid.backgrounds.map { it.height })
        // The middle blank cell and the ten-pixel spaces between sprites stay colored.
        val strip = grid.backgrounds[2]
        val cells = grid.cells.filter { it.y == 5 }.drop(1)
        assertEquals(10, cells[1].x - (cells[0].x + cells[0].width))
        assertTrue(cells.all { it.x >= strip.x && it.x + it.width <= strip.x + strip.width })
    }

    @Test
    fun appliesNegativeWidgetMarginsSeparatelyFromDefaultItemSpacing() {
        val grid =
            PopTrackerItemGridParser(emptyMap())
                .parse(
                    PopJson.parseToJsonElement(
                        """
            {"type":"array","orientation":"horizontal","margin":"-2,0","content":[
              {"type":"itemgrid","margin":"-2,0","item_size":44,"rows":[["first"]]},
              {"type":"itemgrid","margin":"-3,0","item_size":44,"rows":[["second"]]},
              {"type":"itemgrid","margin":"-3,0","item_size":44,"rows":[["a","b","c"]]}
            ]}
        """
                    )
                )!!
        assertEquals(listOf(1, 50, 98, 152, 206), grid.cells.map { it.x })
        assertEquals(listOf(5, 5, 5, 5, 5), grid.cells.map { it.y })
        assertEquals(250, grid.width)
        assertEquals(54, grid.height)
    }

    @Test
    fun preservesExplicitItemSpacingAndFourSidedOuterMargins() {
        val grid =
            PopTrackerItemGridParser(emptyMap())
                .parse(
                    PopJson.parseToJsonElement(
                        """
            {"type":"itemgrid","margin":"1,2,3,4","item_margin":"0,0","item_size":44,
              "rows":[["first","second"]]}
        """
                    )
                )!!
        assertEquals(listOf(1, 45), grid.cells.map { it.x })
        assertEquals(listOf(2, 2), grid.cells.map { it.y })
        assertEquals(92, grid.width)
        assertEquals(50, grid.height)
    }
}
