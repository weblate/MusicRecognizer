package com.mrsep.musicrecognizer.core.domain.preferences

data class SnappedWindowPosition(
    val side: ScreenSide,
    val fractionY: Float,
) {
    init {
        check(fractionY in 0f..1f)
    }

    companion object {
        fun getDefault() = SnappedWindowPosition(
            side = ScreenSide.Right,
            fractionY = 0.4f,
        )
    }
}

enum class ScreenSide { Left, Right }
