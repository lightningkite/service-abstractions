package com.lightningkite.services.files

import com.lightningkite.services.TestSettingContext
import kotlin.test.Test
import kotlin.test.assertIs

class FileScannerSettingsTest {
    @Test
    fun defaultSettingsBuildAScanner() {
        assertIs<CheckMimeFileScanner>(FileScanner.Settings()("scanner", TestSettingContext()))
    }
}
