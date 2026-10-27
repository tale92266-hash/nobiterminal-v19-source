package com.nobidev.nobicode.followup

/**
 * v11 follow-up (1): fuzzy file matching for the quick file switcher.
 * Pure logic, no Android types, so it stays cheap: subsequence scoring
 * with bonuses for word starts and consecutive runs. Runs on a background
 * thread in the caller; only the top 50 results are ever shown.
 */
object FollowupFuzzy {

    /** Returns a score >= 0 for a subsequence match, or -1 for no match. */
    fun score(query: String, target: String): Int {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return 0
        val t = target.lowercase()
        var ti = 0
        var total = 0
        var consecutive = 0
        for (qc in q) {
            var found = false
            while (ti < t.length) {
                val tc = t[ti]
                ti++
                if (tc == qc) {
                    found = true
                    var bonus = 10
                    if (ti == 1 || t[ti - 2] == '/' || t[ti - 2] == '_' ||
                        t[ti - 2] == '-' || t[ti - 2] == '.'
                    ) bonus += 20
                    if (consecutive > 0) bonus += 15
                    total += bonus
                    consecutive++
                    break
                } else {
                    consecutive = 0
                }
            }
            if (!found) return -1
        }
        // Short names that match early rank above long paths.
        return total - (t.length / 20)
    }

    /** Best score across a name and its path; -1 when neither matches. */
    fun scoreFile(query: String, name: String, path: String): Int {
        if (query.isBlank()) return 0
        val byName = score(query, name)
        val byPath = score(query, path)
        return maxOf(byName, byPath)
    }
}
