package com.leon.synthez.core

import kotlin.math.abs
import kotlin.math.pow

object SZEase {
    /**
     * Moves `current` toward `target`, covering `easing` of the remaining
     * distance every 1/60 s whatever the frame rate, and snapping once
     * within `snap`.
     */
    fun approach(current: Double, target: Double, dt: Double, easing: Double = 0.15, snap: Double = 0.5): Double {
        if (abs(target - current) <= snap) return target
        val k = 1 - (1 - easing.coerceIn(0.0, 1.0)).pow(dt * 60)
        val next = current + (target - current) * k
        return if (abs(target - next) <= snap) target else next
    }
}
