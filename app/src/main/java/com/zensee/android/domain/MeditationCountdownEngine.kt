package com.zensee.android.domain

enum class MeditationCountdownPhase {
    MEDITATION,
    COOL_DOWN,
    COMPLETED
}

enum class MeditationTickEffect {
    NONE,
    START_COOL_DOWN,
    COMPLETE
}

data class MeditationTickResult(
    val phase: MeditationCountdownPhase,
    val secondsRemaining: Int,
    val effect: MeditationTickEffect
)

object MeditationCountdownEngine {
    fun tick(
        phase: MeditationCountdownPhase,
        secondsRemaining: Int,
        meditationSeconds: Int,
        coolDownSeconds: Int
    ): MeditationTickResult {
        if (phase == MeditationCountdownPhase.COMPLETED) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.NONE
            )
        }

        return when (phase) {
            MeditationCountdownPhase.MEDITATION -> meditationTick(
                secondsRemaining = secondsRemaining,
                meditationSeconds = meditationSeconds,
                coolDownSeconds = coolDownSeconds
            )

            MeditationCountdownPhase.COOL_DOWN -> coolDownTick(secondsRemaining)
            MeditationCountdownPhase.COMPLETED -> MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.NONE
            )
        }
    }

    private fun meditationTick(
        secondsRemaining: Int,
        meditationSeconds: Int,
        coolDownSeconds: Int
    ): MeditationTickResult {
        if (secondsRemaining <= 0) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.COMPLETE
            )
        }

        val nextRemaining = secondsRemaining - 1
        if (coolDownSeconds > 0 && meditationSeconds > coolDownSeconds && nextRemaining == coolDownSeconds) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COOL_DOWN,
                secondsRemaining = nextRemaining,
                effect = MeditationTickEffect.START_COOL_DOWN
            )
        }

        if (nextRemaining == 0) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.COMPLETE
            )
        }

        return MeditationTickResult(
            phase = MeditationCountdownPhase.MEDITATION,
            secondsRemaining = nextRemaining,
            effect = MeditationTickEffect.NONE
        )
    }

    private fun coolDownTick(secondsRemaining: Int): MeditationTickResult {
        if (secondsRemaining <= 0) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.COMPLETE
            )
        }

        val nextRemaining = secondsRemaining - 1
        if (nextRemaining == 0) {
            return MeditationTickResult(
                phase = MeditationCountdownPhase.COMPLETED,
                secondsRemaining = 0,
                effect = MeditationTickEffect.COMPLETE
            )
        }

        return MeditationTickResult(
            phase = MeditationCountdownPhase.COOL_DOWN,
            secondsRemaining = nextRemaining,
            effect = MeditationTickEffect.NONE
        )
    }
}
