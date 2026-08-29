package com.alex.speedshare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "unexpected applicationId: ${appContext.packageName}",
            appContext.packageName == "com.alex.speedshare" ||
                appContext.packageName == "com.alex.speedshare.migration"
        )
    }
}
