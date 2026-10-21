package com.biasnil.audioforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun ownTagsMapToThemselves() {
        for (tag in AppLanguage.TAGS) assertEquals(tag, AppLanguage.supportedTag(tag))
    }

    @Test
    fun regionalTagsMapToTheLanguage() {
        assertEquals("fr", AppLanguage.supportedTag("fr-FR"))
        assertEquals("fr", AppLanguage.supportedTag("fr-CA"))
        assertEquals("en", AppLanguage.supportedTag("en-GB"))
        assertEquals("ms", AppLanguage.supportedTag("ms-MY"))
    }

    @Test
    fun chineseRegionsPickTheScript() {
        assertEquals("zh-Hant", AppLanguage.supportedTag("zh-TW"))
        assertEquals("zh-Hant", AppLanguage.supportedTag("zh-HK"))
        assertEquals("zh-Hant", AppLanguage.supportedTag("zh-MO"))
        assertEquals("zh-Hant", AppLanguage.supportedTag("zh-Hant-TW"))
        assertEquals("zh-Hans", AppLanguage.supportedTag("zh-CN"))
        assertEquals("zh-Hans", AppLanguage.supportedTag("zh-SG"))
        assertEquals("zh-Hans", AppLanguage.supportedTag("zh"))
    }

    @Test
    fun otherLanguagesAreUnsupported() {
        assertNull(AppLanguage.supportedTag("de-DE"))
        assertNull(AppLanguage.supportedTag("ja"))
    }

    @Test
    fun namesAreInTheirOwnLanguage() {
        assertEquals("Français", AppLanguage.nativeName("fr"))
        assertEquals("繁體中文", AppLanguage.nativeName("zh-Hant"))
    }
}
