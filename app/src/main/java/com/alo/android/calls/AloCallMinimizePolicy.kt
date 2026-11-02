package com.alo.android

import kotlin.math.abs

internal object AloCallMinimizePolicy {
    fun shouldMinimize(translationPx: Float, thresholdPx: Float): Boolean {
        return abs(translationPx) >= thresholdPx
    }
}
