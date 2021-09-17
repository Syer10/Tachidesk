package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import mu.KotlinLogging
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.backup.AbstractBackupValidator.ValidationResult
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator.validate
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupHistory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupSerializer
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.io.InputStream
import java.lang.Integer.max
import java.util.Date

object ProtoBackupImport : ProtoBackupBase() {
    private val logger = KotlinLogging.logger {}

    private var restoreAmount = 0

    private val errors = mutableListOf<Pair<Date, String>>()

    suspend fun performRestore(sourceStream: InputStream): ValidationResult {
        val backupByteArray = sourceStream.source().gzip().buffer().use { it.readByteArray() }
        val backup = parser.decodeFromByteArray(BackupSerializer, backupByteArray)

        val validationResult = validate(backup)

        restoreAmount = backup.backupManga.size + 1 // +1 for categories

        // Restore categories
        if (backup.backupCategories.isNotEmpty()) {
            restoreCategories(backup.backupCategories)
        }

        val categoryMapping = transaction {
            backup.backupCategories.associate {
                it.order to CategoryTable.select { CategoryTable.name eq it.name }.first()[CategoryTable.id].value
            }
        }

        // Store source mapping for error messages
        sourceMapping = backup.getSourceMap()

        // Restore individual manga
        backup.backupManga.forEach {
            restoreManga(it, backup.backupCategories, categoryMapping)
        }

        logger.info {
            """
                Restore Errors:
                ${errors.joinToString("\n") { "${it.first} - ${it.second}" }}
                Restore Summary:
                - Missing Sources:
                    ${validationResult.missingSources.joinToString("\n                    ")}
                - Missing Trackers:
                    ${validationResult.missingTrackers.joinToString("\n                    ")}
            """.trimIndent()
        }

        return validationResult
    }

    private fun restoreCategories(backupCategories: List<BackupCategory>) {
        val dbCategories = Category.getCategoryList()

        // Iterate over them and create missing categories
        backupCategories.forEach { category ->
            if (dbCategories.none { it.name == category.name }) {
                Category.createCategory(category.name)
            }
        }
    }

    private fun restoreManga(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        categoryMapping: Map<Int, Int>
    ) {
        val manga = backupManga.getMangaDataClass()
        val chapters = backupManga.getChapterDataClasses()
        val categories = backupManga.categories
        val history = backupManga.brokenHistory.map { BackupHistory(it.url, it.lastRead) } + backupManga.history
        //val tracks = backupManga.getTrackingImpl()

        try {
            restoreMangaData(manga, chapters, categories, history, /*tracks,*/ backupCategories, categoryMapping)
        } catch (e: Exception) {
            val sourceName = sourceMapping[manga.sourceId.toLong()] ?: manga.sourceId
            errors.add(Date() to "${manga.title} [$sourceName]: ${e.message}")
        }
    }

    @Suppress("UNUSED_PARAMETER") // TODO: remove
    private fun restoreMangaData(
        manga: MangaDataClass,
        chapters: List<ChapterDataClass>,
        categories: List<Int>,
        history: List<BackupHistory>,
        //tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        categoryMapping: Map<Int, Int>
    ) {
        val dbManga = transaction {
            MangaTable.select { (MangaTable.url eq manga.url) and (MangaTable.sourceReference eq manga.sourceId.toLong()) }
                .firstOrNull()
        }

        if (dbManga == null) { // Manga not in database
            transaction {
                // insert manga to database
                val mangaId = MangaTable.insertAndGetId {
                    it[url] = manga.url
                    it[title] = manga.title

                    it[artist] = manga.artist
                    it[author] = manga.author
                    it[description] = manga.description
                    it[genre] = manga.genre.joinToString()
                    it[status] = MangaStatus.valueOf(manga.status).value
                    it[thumbnail_url] = manga.thumbnailUrl

                    it[sourceReference] = manga.sourceId.toLong()

                    it[initialized] = manga.description != null

                    it[inLibrary] = manga.inLibrary
                }.value

                // insert chapter data
                chapters.forEach { chapter ->
                    ChapterTable.insert {
                        it[url] = chapter.url
                        it[name] = chapter.name
                        it[date_upload] = chapter.uploadDate
                        it[chapter_number] = chapter.chapterNumber
                        it[scanlator] = chapter.scanlator

                        it[chapterIndex] = chapter.index
                        it[ChapterTable.manga] = mangaId

                        it[isRead] = chapter.read
                        it[lastPageRead] = chapter.lastPageRead
                        it[isBookmarked] = chapter.bookmarked
                    }
                }

                // insert categories
                categories.forEach { backupCategoryOrder ->
                    CategoryManga.addMangaToCategory(mangaId, categoryMapping[backupCategoryOrder]!!)
                }
            }
        } else { // Manga in database
            transaction {
                val mangaId = dbManga[MangaTable.id].value

                // Merge manga data
                MangaTable.update({ MangaTable.id eq mangaId }) {
                    it[artist] = manga.artist ?: dbManga[artist]
                    it[author] = manga.author ?: dbManga[author]
                    it[description] = manga.description ?: dbManga[description]
                    it[genre] = manga.genre.joinToString().ifEmpty { dbManga[genre] }
                    it[status] = MangaStatus.valueOf(manga.status).value
                    it[thumbnail_url] = manga.thumbnailUrl ?: dbManga[thumbnail_url]

                    it[initialized] = dbManga[initialized] || manga.description != null

                    it[inLibrary] = manga.inLibrary || dbManga[inLibrary]
                }

                // merge chapter data
                val dbChapters = ChapterTable.select { ChapterTable.manga eq mangaId }

                chapters.forEach { chapter ->
                    val dbChapter = dbChapters.find { it[ChapterTable.url] == chapter.url }

                    if (dbChapter == null) {
                        ChapterTable.insert {
                            it[url] = chapter.url
                            it[name] = chapter.name
                            it[date_upload] = chapter.uploadDate
                            it[chapter_number] = chapter.chapterNumber
                            it[scanlator] = chapter.scanlator

                            it[chapterIndex] = chapter.index
                            it[ChapterTable.manga] = mangaId

                            it[isRead] = chapter.read
                            it[lastPageRead] = chapter.lastPageRead
                            it[isBookmarked] = chapter.bookmarked
                        }
                    } else {
                        ChapterTable.update({ (ChapterTable.url eq dbChapter[ChapterTable.url]) and (ChapterTable.manga eq mangaId) }) {
                            it[isRead] = chapter.read || dbChapter[isRead]
                            it[lastPageRead] = max(chapter.lastPageRead, dbChapter[lastPageRead])
                            it[isBookmarked] = chapter.bookmarked || dbChapter[isBookmarked]
                        }
                    }
                }

                // merge categories
                categories.forEach { backupCategoryOrder ->
                    CategoryManga.addMangaToCategory(mangaId, categoryMapping[backupCategoryOrder]!!)
                }
            }
        }

        // TODO: insert/merge history

        // TODO: insert/merge tracking
    }
}
