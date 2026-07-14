package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun storedValuesResolveToExpectedTheme() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStored("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStored("dark"))
    }

    @Test
    fun missingOrUnknownValueFallsBackToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("unsupported"))
    }
}
