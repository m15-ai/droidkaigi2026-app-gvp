package com.m15.gvp.util

import kotlin.math.max

fun areSimilar(text1: String, text2: String, threshold: Double = 0.85): Boolean {
    if (text1.isEmpty() || text2.isEmpty()) return false
    val lower1 = text1.lowercase()
    val lower2 = text2.lowercase()

    // Simple Levenshtein distance (edit distance)
    val m = lower1.length
    val n = lower2.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j
    for (i in 1..m) {
        for (j in 1..n) {
            val cost = if (lower1[i - 1] == lower2[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,      // deletion
                dp[i][j - 1] + 1,      // insertion
                dp[i - 1][j - 1] + cost // substitution
            )
        }
    }
    val distance = dp[m][n]
    val similarity = 1.0 - (distance.toDouble() / max(m, n).toDouble())
    return similarity >= threshold
}