package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okio.buffer
import okio.gzip
import okio.sink
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupChapter
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSerializer
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSource
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ProtoBackupExport : ProtoBackupBase() {
    suspend fun createBackup(flags: BackupFlags): InputStream {
        // Create root object

        val databaseManga = transaction { MangaTable.select { MangaTable.inLibrary eq true } }

        val backup: Backup = transaction {
            Backup(
                backupManga(databaseManga, flags),
                backupCategories(),
                emptyList(),
                backupExtensionInfo(databaseManga)
            )
        }

        val byteArray = parser.encodeToByteArray(BackupSerializer, backup)

        val byteStream = ByteArrayOutputStream()
        byteStream.sink().gzip().buffer().use { it.write(byteArray) }

        return byteStream.toByteArray().inputStream()
    }

    private fun backupManga(databaseManga: Query, flags: BackupFlags): List<BackupManga> {
        return databaseManga.map { mangaRow ->
            val backupManga = BackupManga.copyFrom(
                MangaTable.toDataClass(mangaRow)
            )

            val mangaId = mangaRow[MangaTable.id].value

            if (flags.includeChapters) {
                backupManga.chapters = Chapter.getDatabaseChapters(mangaId)
                    .map {
                        BackupChapter.copyFrom(it)
                    }
            }

            if (flags.includeCategories) {
                backupManga.categories = CategoryManga.getMangaCategories(mangaId).map { it.order }
            }

//            if(flags.includeTracking) {
//                backupManga.tracking = TODO()
//            }

//            if (flags.includeHistory) {
//                backupManga.history = TODO()
//            }

            backupManga
        }
    }

    private fun backupCategories(): List<BackupCategory> {
        return CategoryTable.selectAll().orderBy(CategoryTable.order to SortOrder.ASC).map {
            CategoryTable.toDataClass(it)
        }.map {
            BackupCategory.copyFrom(it)
        }
    }

    private fun backupExtensionInfo(mangas: Query): List<BackupSource> {
        return mangas
            .asSequence()
            .map { it[MangaTable.sourceReference] }
            .distinct()
            .map {
                val sourceRow = SourceTable.select { SourceTable.id eq it }.firstOrNull()
                BackupSource(
                    sourceRow?.get(SourceTable.name) ?: "",
                    it
                )
            }
            .toList()
    }
}
