package com.liftoff.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapesTest {

    @Test
    fun buttonsAndCheckboxesHaveFourDpCornersAndCardsAreSquare() {
        // Buttons and checkboxes use 4 dp rounded corners; cards are square.
        val shapes = LiftoffShapes

        // extraSmall and small (buttons, checkboxes) → 4 dp
        assertEquals("extraSmall is 4dp", RoundedCornerShape(4.dp), shapes.extraSmall)
        assertEquals("small is 4dp", RoundedCornerShape(4.dp), shapes.small)

        // medium, large, extraLarge (cards) → 0dp rounded = square
        assertEquals("medium is 0dp rounded", RoundedCornerShape(0.dp), shapes.medium)
        assertEquals("large is 0dp rounded", RoundedCornerShape(0.dp), shapes.large)
        assertEquals("extraLarge is 0dp rounded", RoundedCornerShape(0.dp), shapes.extraLarge)

        // Named vals match.
        assertEquals("ButtonShape is 4dp", RoundedCornerShape(4.dp), ButtonShape)
        assertEquals("CardShape is square", RoundedCornerShape(0.dp), CardShape)
    }
}
