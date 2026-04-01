package com.zensee.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MeditationSoundCatalogTest {

    @Test
    fun orderedDisplayNames_matchExpectedMeditationSequence() {
        assertEquals(
            listOf(
                "古寺长钟",
                "铜钵初震",
                "静夜清磬",
                "空山回钵",
                "清铃一响"
            ),
            MeditationSoundCatalog.sounds.map { it.displayName }
        )
    }

    @Test
    fun storageKeys_matchExpectedRawResourceNames() {
        assertEquals(
            listOf(
                "gusi_changzhong",
                "tongbo_chuzhen",
                "jingye_qingqing",
                "kongshan_huibo",
                "qingling_yixiang"
            ),
            MeditationSoundCatalog.sounds.map { it.storageKey }
        )
    }

    @Test
    fun previewAudioResources_swapThirdAndFifthWithoutChangingOrder() {
        assertEquals(
            listOf(
                "gusi_changzhong",
                "tongbo_chuzhen",
                "qingling_yixiang",
                "kongshan_huibo",
                "jingye_qingqing"
            ),
            MeditationSoundCatalog.sounds.map { it.rawResName }
        )
    }
}
