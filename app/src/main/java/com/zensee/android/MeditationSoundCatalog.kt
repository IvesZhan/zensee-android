package com.zensee.android

data class MeditationSound(
    val storageKey: String,
    val displayName: String,
    val rawResName: String,
    val imageResName: String
)

object MeditationSoundCatalog {
    val sounds: List<MeditationSound> = listOf(
        MeditationSound(
            storageKey = "gusi_changzhong",
            displayName = "古寺长钟",
            rawResName = "gusi_changzhong",
            imageResName = "meditation_sound_chengxin"
        ),
        MeditationSound(
            storageKey = "tongbo_chuzhen",
            displayName = "铜钵初震",
            rawResName = "tongbo_chuzhen",
            imageResName = "meditation_sound_dayuan"
        ),
        MeditationSound(
            storageKey = "jingye_qingqing",
            displayName = "静夜清磬",
            rawResName = "qingling_yixiang",
            imageResName = "meditation_sound_jingye"
        ),
        MeditationSound(
            storageKey = "kongshan_huibo",
            displayName = "空山回钵",
            rawResName = "kongshan_huibo",
            imageResName = "meditation_sound_kongshan"
        ),
        MeditationSound(
            storageKey = "qingling_yixiang",
            displayName = "清铃一响",
            rawResName = "jingye_qingqing",
            imageResName = "meditation_sound_yinian"
        )
    )

    val defaultSound: MeditationSound = sounds.first()

    fun findByStorageKey(storageKey: String?): MeditationSound {
        return sounds.firstOrNull { it.storageKey == storageKey } ?: defaultSound
    }
}
