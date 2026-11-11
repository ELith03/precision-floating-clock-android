package com.elith.precision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineRenderTest {
    private fun render(remaining: Long): Bitmap {
        val view=ClockView(RuntimeEnvironment.getApplication()).apply {
            config=ClockConfig(showFrameTime=false)
            frame=CountdownFrame.of(remaining)
        }
        val width=800; val height=view.desiredHeight(width)
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    @Test fun fiveDotsSitInsideTheThreeSecondTrack() {
        val bitmap=render(3000)
        // Track: x=38..362. Half-second checkpoints are one sixth apart.
        for(x in listOf(92,146,200,254,308)) {
            val border=bitmap.getPixel(x*2,278)
            assertTrue("Missing checkpoint at x=$x",Color.red(border)>220 && Color.green(border)>220 && Color.blue(border)>220)
        }
    }
    @Test fun lineMeetsEachDotExactlyWhenItActivates() {
        for((remaining,x) in listOf(2500L to 92,2000L to 146,1500L to 200,1000L to 254,500L to 308)) {
            val before=render(remaining+1).getPixel(x*2,272)
            val reached=render(remaining).getPixel(x*2,272)
            assertNotEquals("Ring must wait for $remaining ms",ClockConfig().accent,before)
            assertEquals("Ring must activate at $remaining ms",ClockConfig().accent,reached)
        }
    }
    @Test fun progressTraversesBothEndSegments() {
        val accent=ClockConfig().accent
        assertNotEquals(accent,render(3000).getPixel(130,286))
        assertEquals(accent,render(2500).getPixel(130,286))
        assertNotEquals(accent,render(500).getPixel(670,286))
        assertEquals(accent,render(1).getPixel(670,286))
    }
}
