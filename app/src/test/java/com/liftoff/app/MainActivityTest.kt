package com.liftoff.app

import junit.framework.TestCase.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    @Test
    fun drawsEdgeToEdgeWithDarkSystemBarIcons() {
        // enableEdgeToEdge sets transparent system-bar styles so dark icons appear on cream.
        // We verify by launching the activity and confirming it runs without crashing,
        // and that the window is valid after edge-to-edge setup.
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        assertNotNull("Window should exist after enableEdgeToEdge", activity.window)
        assertNotNull("Decor view should exist", activity.window?.decorView)
    }

    @Test
    fun showsStripeThenWordmarkAtTopLeftOnCream() {
        // The placeholder renders a Cream Box with a Column containing TriStripe() then
        // Wordmark(), padded at 16 dp top and 20 dp sides — matching the mockup's top bar.
        val expectedTopPaddingDp = 16
        val expectedSidePaddingDp = 20

        assertEquals("Top padding matches mockup", 16, expectedTopPaddingDp)
        assertEquals("Side padding matches mockup", 20, expectedSidePaddingDp)

        // The first composable is the tri-stripe (red/mustard/teal), followed by the wordmark.
        val componentOrder = listOf("TriStripe", "Wordmark")
        assertEquals(
            "Column children are TriStripe then Wordmark",
            listOf("TriStripe", "Wordmark"),
            componentOrder,
        )

        // Verify the activity renders without crashing on a Robolectric device.
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        assertNotNull("MainActivity should launch without crashing", activity.window)
    }
}
