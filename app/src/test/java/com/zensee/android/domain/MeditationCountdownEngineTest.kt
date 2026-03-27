package com.zensee.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MeditationCountdownEngineTest {

    @Test
    fun `ten minute meditation with five minute cool down triggers bowl at cool down boundary`() {
        val result = MeditationCountdownEngine.tick(
            phase = MeditationCountdownPhase.MEDITATION,
            secondsRemaining = 301,
            meditationSeconds = 600,
            coolDownSeconds = 300
        )

        assertEquals(MeditationCountdownPhase.COOL_DOWN, result.phase)
        assertEquals(300, result.secondsRemaining)
        assertEquals(MeditationTickEffect.START_COOL_DOWN, result.effect)
    }

    @Test
    fun `tick enters cool down when remaining time reaches configured threshold`() {
        val result = MeditationCountdownEngine.tick(
            phase = MeditationCountdownPhase.MEDITATION,
            secondsRemaining = 121,
            meditationSeconds = 600,
            coolDownSeconds = 120
        )

        assertEquals(MeditationCountdownPhase.COOL_DOWN, result.phase)
        assertEquals(120, result.secondsRemaining)
        assertEquals(MeditationTickEffect.START_COOL_DOWN, result.effect)
    }

    @Test
    fun `tick completes when cool down counts down to zero`() {
        val result = MeditationCountdownEngine.tick(
            phase = MeditationCountdownPhase.COOL_DOWN,
            secondsRemaining = 1,
            meditationSeconds = 600,
            coolDownSeconds = 120
        )

        assertEquals(MeditationCountdownPhase.COMPLETED, result.phase)
        assertEquals(0, result.secondsRemaining)
        assertEquals(MeditationTickEffect.COMPLETE, result.effect)
    }

    @Test
    fun `meditation without cool down only completes at end`() {
        val result = MeditationCountdownEngine.tick(
            phase = MeditationCountdownPhase.MEDITATION,
            secondsRemaining = 1,
            meditationSeconds = 600,
            coolDownSeconds = 0
        )

        assertEquals(MeditationCountdownPhase.COMPLETED, result.phase)
        assertEquals(0, result.secondsRemaining)
        assertEquals(MeditationTickEffect.COMPLETE, result.effect)
    }
}
