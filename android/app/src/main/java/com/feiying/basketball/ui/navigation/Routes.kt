package com.feiying.basketball.ui.navigation

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val RECORD = "record"
    const val REVIEW = "review"

    const val ANALYSIS_PATTERN = "analysis/{recordId}"
    const val RESULT_PATTERN = "result/{recordId}"
    const val CLIP_PATTERN = "clip/{recordId}/{index}"

    const val RECORD_ID_ARG = "recordId"
    const val CLIP_INDEX_ARG = "index"

    fun analysis(recordId: String): String = "analysis/$recordId"
    fun result(recordId: String): String = "result/$recordId"
    fun clip(recordId: String, index: Int): String = "clip/$recordId/$index"
}
