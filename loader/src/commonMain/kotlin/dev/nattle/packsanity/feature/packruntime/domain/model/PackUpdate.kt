package dev.nattle.packsanity.feature.packruntime.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val packVersionsJson = Json { ignoreUnknownKeys = true }

data class PackUpdate(
    val packageUid: String,
    val packName: String,
    val currentVersion: String,
    val availableVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val isDownloaded: Boolean = false,
)

@Serializable private data class RawPackVersions(val versions: List<RawPackVersion> = emptyList())

@Serializable
private data class RawPackVersion(
    @SerialName("package_version") val packageVersion: String,
    @SerialName("download_url") val downloadUrl: String? = null,
    val sha256: String? = null,
)

fun findAvailablePackUpdate(pack: TrackerPack, versionsJson: String): PackUpdate? {
    val currentVersion = pack.packageVersion?.trim().orEmpty()
    if (currentVersion.isEmpty()) return null

    val candidate =
        packVersionsJson.decodeFromString<RawPackVersions>(versionsJson).versions.firstOrNull {
            !it.downloadUrl.isNullOrBlank()
        } ?: return null
    val availableVersion = candidate.packageVersion.trim()
    if (availableVersion.isEmpty() || comparePackVersions(availableVersion, currentVersion) <= 0)
        return null

    val downloadUrl = requireNotNull(candidate.downloadUrl).trim()
    require(downloadUrl.startsWith("https://", ignoreCase = true)) {
        "Pack update download URL must use HTTPS"
    }
    val sha256 = candidate.sha256?.trim().orEmpty()
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
        "Pack update is missing a valid SHA-256 checksum"
    }
    return PackUpdate(
        packageUid = pack.uid,
        packName = pack.name,
        currentVersion = currentVersion,
        availableVersion = availableVersion,
        downloadUrl = downloadUrl,
        sha256 = sha256.lowercase(),
    )
}

internal fun comparePackVersions(left: String, right: String): Int {
    val leftVersion = ParsedPackVersion.parse(left)
    val rightVersion = ParsedPackVersion.parse(right)
    if (leftVersion != null && rightVersion != null) return leftVersion.compareTo(rightVersion)
    return left.compareTo(right, ignoreCase = true)
}

/** Compares release tags and manifest versions while allowing equivalent tag prefixes. */
fun arePackVersionsEqual(left: String, right: String): Boolean =
    comparePackVersions(left, right) == 0

private data class ParsedPackVersion(val core: List<Int>, val prerelease: List<String>?) :
    Comparable<ParsedPackVersion> {
    override fun compareTo(other: ParsedPackVersion): Int {
        repeat(maxOf(core.size, other.core.size)) { index ->
            val comparison =
                core.getOrElse(index) { 0 }.compareTo(other.core.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        if (prerelease == null && other.prerelease != null) return 1
        if (prerelease != null && other.prerelease == null) return -1
        val leftPrerelease = prerelease ?: return 0
        val rightPrerelease = other.prerelease ?: return 0
        repeat(maxOf(leftPrerelease.size, rightPrerelease.size)) { index ->
            val leftPart = leftPrerelease.getOrNull(index) ?: return -1
            val rightPart = rightPrerelease.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison =
                when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> leftPart.compareTo(rightPart, ignoreCase = true)
                }
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private val pattern =
            Regex("^(?:v\\.?)?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?" + "(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(value: String): ParsedPackVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val core = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val prerelease = match.groupValues[2].takeIf(String::isNotEmpty)?.split('.', '-')
            return ParsedPackVersion(core, prerelease)
        }
    }
}
