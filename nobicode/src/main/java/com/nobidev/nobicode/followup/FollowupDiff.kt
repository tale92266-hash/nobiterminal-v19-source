package com.nobidev.nobicode.followup

/**
 * v11 follow-up (13): line-based diff used for the pre-count shown before
 * the existing CompareActivity opens. Classic LCS table, same shape as the
 * one CompareActivity already uses; files over 1000 lines are flagged as
 * too large so the caller shows a plain view instead of allocating a
 * huge table (performance rule: never block the UI thread with this —
 * callers run it on Dispatchers.Default).
 */
object FollowupDiff {

    data class Result(
        val changedInA: Set<Int>,
        val changedInB: Set<Int>,
        val differingLines: Int,
        val tooLarge: Boolean
    )

    fun diff(a: List<String>, b: List<String>): Result {
        if (a.size > 1000 || b.size > 1000) {
            return Result(emptySet(), emptySet(), 0, true)
        }
        val n = a.size
        val m = b.size
        val w = m + 1
        val table = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i * w + j] = if (a[i] == b[j]) table[(i + 1) * w + j + 1] + 1
                    else maxOf(table[(i + 1) * w + j], table[i * w + j + 1])
            }
        }
        val diffA = HashSet<Int>()
        val diffB = HashSet<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                i++
                j++
            } else if (table[(i + 1) * w + j] >= table[i * w + j + 1]) {
                diffA.add(i)
                i++
            } else {
                diffB.add(j)
                j++
            }
        }
        while (i < n) diffA.add(i++)
        while (j < m) diffB.add(j++)
        return Result(diffA, diffB, maxOf(diffA.size, diffB.size), false)
    }
}
