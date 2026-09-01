package dev.nattle.packsanity.feature.packruntime.domain.model

/** Current package-loading stage and optional detail for the loading dialog. */
data class PackageLoadProgress(val stage: String, val detail: String? = null)
