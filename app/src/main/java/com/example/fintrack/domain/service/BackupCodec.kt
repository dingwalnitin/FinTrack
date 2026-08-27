package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.model.BackupManifest

/**
 * Stage 11 P23 #1 — deterministic backup serialization.
 *
 * Format (line-oriented, no external JSON dependency, fully canonical):
 *
 *   FTBACKUP1
 *   M|formatVersion=1|schemaVersion=10|createdAtEpochMs=...|createdByVersion=...
 *     |includesRawEvidence=false|encrypted=false|redactionVersion=redact-v1
 *   D|accounts|3|<sha256-of-body>
 *   id=..;name=..;...
 *   id=..;name=..;...
 *   ...
 *   E|<sha256-of-whole-payload-after-header>
 *
 * Determinism rules:
 *  - rows are sorted lexicographically by the caller before encoding;
 *  - fields inside a row are emitted in sorted key order;
 *  - no timestamps are injected anywhere except the manifest line.
 */
class BackupCodec {

    data class DatasetBlock(
        val dataset: BackupDataset,
        /** Rows exactly as staged/decoded (unsorted). */
        val rows: List<String>,
        /** Canonical body used for checksums: sorted rows joined by '\n'. */
        val canonicalBody: String,
    )

    data class DecodedEnvelope(
        val manifest: BackupManifest,
        val blocks: List<DatasetBlock>,
    )

    fun encodeDataset(dataset: BackupDataset, rows: List<String>): DatasetBlock {
        val canonical = rows.sorted().joinToString("\n")
        return DatasetBlock(dataset, rows, canonical)
    }

    fun encodeEnvelope(manifest: BackupManifest, blocks: List<DatasetBlock>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("M|")
            .append("formatVersion=").append(manifest.formatVersion).append('|')
            .append("schemaVersion=").append(manifest.schemaVersion).append('|')
            .append("createdAtEpochMs=").append(manifest.createdAtEpochMs).append('|')
            .append("createdByVersion=").append(esc(manifest.createdByVersion)).append('|')
            .append("includesRawEvidence=").append(manifest.includesRawEvidence).append('|')
            .append("encrypted=").append(manifest.encrypted).append('|')
            .append("redactionVersion=").append(manifest.redactionVersion)
        sb.append('\n')
        for (m in manifest.datasets) {
            sb.append("D|").append(m.dataset.name).append('|')
                .append(m.rowCount).append('|').append(m.sha256).append('\n')
        }
        for (block in blocks) {
            sb.append("#BEGIN ").append(block.dataset.name).append('\n')
            sb.append(block.canonicalBody)
            if (block.canonicalBody.isNotEmpty()) sb.append('\n')
            sb.append("#END ").append(block.dataset.name).append('\n')
        }
        // Whole-payload integrity over everything after the header line.
        val bodySoFar = sb.toString().removePrefix(HEADER + "\n")
        sb.append("E|").append(RedactionEngine.sha256(bodySofer(bodySoFar)))
        return sb.toString()
    }

    private fun bodySofer(s: String) = s

    fun decodeEnvelope(payload: String): com.example.fintrack.domain.service.CodecResult {
        val reasons = mutableListOf<String>()
        val lines = payload.lines()
        if (lines.firstOrNull()?.trim() != HEADER) {
            return com.example.fintrack.domain.service.CodecResult.Bad(
                listOf("not a FinTrack backup (missing $HEADER header)"),
            )
        }
        var manifest: BackupManifest? = null
        val manifests = mutableListOf<com.example.fintrack.domain.model.BackupDatasetManifest>()
        val blocks = mutableListOf<DatasetBlock>()
        var current: Pair<BackupDataset, MutableList<String>>? = null

        for (raw in lines.drop(1)) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("M|") -> {
                    val fields = line.removePrefix("M|").split('|').mapNotNull {
                        val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                    }.toMap()
                    manifest = BackupManifest(
                        formatVersion = fields["formatVersion"]?.toIntOrNull() ?: -1,
                        schemaVersion = fields["schemaVersion"]?.toIntOrNull() ?: -1,
                        createdAtEpochMs = fields["createdAtEpochMs"]?.toLongOrNull() ?: 0L,
                        createdByVersion = unesc(fields["createdByVersion"] ?: ""),
                        datasets = emptyList(),
                        includesRawEvidence = fields["includesRawEvidence"] == "true",
                        encrypted = fields["encrypted"] == "true",
                        redactionVersion = fields["redactionVersion"] ?: "unknown",
                    )
                }
                line.startsWith("D|") -> {
                    val p = line.split('|')
                    val ds = p.getOrNull(1)?.let { n ->
                        BackupDataset.entries.firstOrNull { it.name == n }
                    } ?: run { reasons += "unknown dataset '${p.getOrNull(1)}'"; null }
                    if (ds != null) {
                        manifests += com.example.fintrack.domain.model.BackupDatasetManifest(
                            dataset = ds,
                            rowCount = p.getOrNull(2)?.toIntOrNull() ?: -1,
                            sha256 = p.getOrNull(3) ?: "",
                        )
                    }
                }
                line.startsWith("#BEGIN ") -> {
                    val name = line.removePrefix("#BEGIN ")
                    val ds = BackupDataset.entries.firstOrNull { it.name == name }
                    if (ds == null) reasons += "unknown dataset block '$name'"
                    else current = ds to mutableListOf()
                }
                line.startsWith("#END ") -> {
                    val c = current
                    if (c != null) {
                        blocks += encodeDataset(c.first, c.second.toList())
                        current = null
                    }
                }
                line.startsWith("E|") -> {
                    // whole-payload checksum verified by caller via validate()
                }
                else -> {
                    val c = current
                    if (c != null && line.isNotBlank()) c.second += line
                }
            }
        }
        val m = manifest
            ?: return com.example.fintrack.domain.service.CodecResult.Bad(listOf("missing manifest line"))
        if (reasons.isNotEmpty()) return com.example.fintrack.domain.service.CodecResult.Bad(reasons)
        return com.example.fintrack.domain.service.CodecResult.Ok(
            BackupEnvelope(m.copy(datasets = manifests), blocks),
        )
    }

    private fun esc(s: String) = s.replace("|", "\\|")
    private fun unesc(s: String) = s.replace("\\|", "|")

    companion object {
        const val HEADER = "FTBACKUP1"
    }
}
