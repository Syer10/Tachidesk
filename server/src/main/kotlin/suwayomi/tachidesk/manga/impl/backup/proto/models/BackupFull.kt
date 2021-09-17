package suwayomi.tachidesk.manga.impl.backup.proto.models

import java.time.Instant
import java.time.format.DateTimeFormatter

object BackupFull {
    fun getDefaultFilename(): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
            .format(Instant.now())
        return "tachidesk_$date.proto.gz"
    }
}
