package suwayomi.tachidesk.manga.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.Page.getPageImage
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Downloading
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Error
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Finished
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Queued
import suwayomi.tachidesk.manga.model.table.ChapterTable
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

class Downloader(private val downloadQueue: CopyOnWriteArrayList<DownloadChapter>, val notifier: () -> Unit) {

    private suspend fun step(download: DownloadChapter?) {
        notifier()
        currentCoroutineContext().ensureActive()
        if (download != null && download !in downloadQueue) throw Exception("Cancelled download")
    }

    suspend fun run() {
        while (downloadQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            val download = downloadQueue.firstOrNull {
                it.state == Queued ||
                    (it.state == Error && it.tries < 3) // 3 re-tries per download
            } ?: break

            try {
                download.state = Downloading
                step(download)

                download.chapter = getChapterDownloadReady(download.chapterIndex, download.mangaId)
                step(download)

                val pageCount = download.chapter.pageCount
                for (pageNum in 0 until pageCount) {
                    var pageProgressJob: Job? = null
                    try {
                        getPageImage(
                            mangaId = download.mangaId,
                            chapterIndex = download.chapterIndex,
                            index = pageNum,
                            progressFlow = { flow ->
                                pageProgressJob = flow
                                    .sample(100)
                                    .distinctUntilChanged()
                                    .onEach {
                                        download.progress = (pageNum.toFloat() + (it.toFloat() * 0.01f)) / pageCount
                                        step(null) // don't throw on canceled download here since we can't do anything
                                    }
                                    .launchIn(GlobalScope)
                            }
                        ).first.close()
                    } finally {
                        // always cancel the page progress job even if it throws an exception to avoid memory leaks
                        pageProgressJob?.cancel()
                    }
                    // TODO: retry on error with 2,4,8 seconds of wait
                    // TODO: download multiple pages at once, possible solution: rx observer's strategy is used in Tachiyomi
                    download.progress = ((pageNum + 1).toFloat()) / pageCount
                    step(download)
                }
                download.state = Finished
                transaction {
                    ChapterTable.update({ (ChapterTable.manga eq download.mangaId) and (ChapterTable.sourceOrder eq download.chapterIndex) }) {
                        it[isDownloaded] = true
                    }
                }
                step(download)

                downloadQueue.removeIf { it.mangaId == download.mangaId && it.chapterIndex == download.chapterIndex }
                step(null)
            } catch (e: CancellationException) {
                logger.debug("Downloader was stopped")
                downloadQueue.filter { it.state == Downloading }.forEach { it.state = Queued }
            } catch (e: Exception) {
                logger.info("Downloader faced an exception", e)
                download.tries++
                download.state = Error
            } finally {
                notifier()
            }
        }
    }
}
