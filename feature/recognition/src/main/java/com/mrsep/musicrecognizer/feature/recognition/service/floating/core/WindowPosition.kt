package com.mrsep.musicrecognizer.feature.recognition.service.floating.core

import android.view.Gravity
import com.mrsep.musicrecognizer.core.domain.preferences.ScreenSide
import com.mrsep.musicrecognizer.core.domain.preferences.SnappedWindowPosition

/** Determines whether the window is currently on the left or right half of the screen. */
internal fun calculateScreenSide(
    currentX: Int,
    currentGravity: Int,
    displayWidth: Int,
    windowWidth: Int
): ScreenSide {
    val baseX = convertXForGravityChange(
        x = currentX,
        fromGravity = currentGravity,
        toGravity = Gravity.LEFT,
        displayWidth = displayWidth,
        windowWidth = windowWidth
    )
    val maxX = maxOf(1, displayWidth - windowWidth)
    return if (baseX < (maxX / 2)) ScreenSide.Left else ScreenSide.Right
}

/** Calculates the snapped window position (screen side and height fraction). */
internal fun calculateSnappedWindowPosition(
    currentX: Int,
    currentY: Int,
    currentGravity: Int,
    displayWidth: Int,
    displayHeight: Int,
    windowWidth: Int,
    windowHeight: Int
): SnappedWindowPosition {
    val side = calculateScreenSide(
        currentX = currentX,
        currentGravity = currentGravity,
        displayWidth = displayWidth,
        windowWidth = windowWidth
    )
    val baseY = convertYForGravityChange(
        y = currentY,
        fromGravity = currentGravity,
        toGravity = Gravity.TOP,
        displayHeight = displayHeight,
        windowHeight = windowHeight
    )
    val maxY = maxOf(1, displayHeight - windowHeight)
    val fractionY = (baseY.toFloat() / maxY).coerceIn(0f, 1f)
    return SnappedWindowPosition(side, fractionY)
}

/** Returns the screen side the window is strictly snapped to, or null if it is not fully snapped. */
internal fun calculateStrictScreenSide(
    currentX: Int,
    currentGravity: Int,
    displayWidth: Int,
    windowWidth: Int
): ScreenSide? {
    val baseX = convertXForGravityChange(
        x = currentX,
        fromGravity = currentGravity,
        toGravity = Gravity.LEFT,
        displayWidth = displayWidth,
        windowWidth = windowWidth
    )
    val maxX = maxOf(1, displayWidth - windowWidth)
    return when {
        baseX <= 0 -> ScreenSide.Left
        baseX >= maxX -> ScreenSide.Right
        else -> null
    }
}

/**
 * Calculates the snapped window position only if the window is strictly snapped to a screen edge.
 * Returns null otherwise.
 */
internal fun calculateStrictSnappedWindowPosition(
    currentX: Int,
    currentY: Int,
    currentGravity: Int,
    displayWidth: Int,
    displayHeight: Int,
    windowWidth: Int,
    windowHeight: Int
): SnappedWindowPosition? {
    val side = calculateStrictScreenSide(
        currentX = currentX,
        currentGravity = currentGravity,
        displayWidth = displayWidth,
        windowWidth = windowWidth
    ) ?: return null
    val baseY = convertYForGravityChange(
        y = currentY,
        fromGravity = currentGravity,
        toGravity = Gravity.TOP,
        displayHeight = displayHeight,
        windowHeight = windowHeight
    )
    val maxY = maxOf(1, displayHeight - windowHeight)
    val fractionY = (baseY.toFloat() / maxY).coerceIn(0f, 1f)
    return SnappedWindowPosition(side, fractionY)
}

/** Calculates the final X and Y coordinates to be set in WindowParams from a snapped position. */
internal fun calculateWindowPosition(
    snappedPosition: SnappedWindowPosition,
    targetGravity: Int,
    displayWidth: Int,
    displayHeight: Int,
    windowWidth: Int,
    windowHeight: Int
): Pair<Int, Int> {
    val newMaxX = maxOf(1, displayWidth - windowWidth)
    val newMaxY = maxOf(1, displayHeight - windowHeight)

    val newBaseX = if (snappedPosition.side == ScreenSide.Left) 0 else newMaxX
    val newBaseY = (newMaxY * snappedPosition.fractionY).toInt().coerceIn(0, newMaxY)

    val finalX = convertXForGravityChange(
        x = newBaseX,
        fromGravity = Gravity.LEFT,
        toGravity = targetGravity,
        displayWidth = displayWidth,
        windowWidth = windowWidth
    )
    val finalY = convertYForGravityChange(
        y = newBaseY,
        fromGravity = Gravity.TOP,
        toGravity = targetGravity,
        displayHeight = displayHeight,
        windowHeight = windowHeight
    )
    return Pair(finalX, finalY)
}
