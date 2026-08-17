package dev.dshremote.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TimelineBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartAndScroll720TypedRows() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            FrameTimingMetric(),
            StartupTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
        },
    ) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("renderer_fixture", "long")
        }
        startActivityAndWait(intent)
        val uiDevice = this.device
        val width = uiDevice.displayWidth
        val height = uiDevice.displayHeight
        repeat(8) {
            uiDevice.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 18)
        }
        repeat(4) {
            uiDevice.swipe(width / 2, height / 4, width / 2, height * 3 / 4, 18)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "dev.dshremote.gate0c"
    }
}
