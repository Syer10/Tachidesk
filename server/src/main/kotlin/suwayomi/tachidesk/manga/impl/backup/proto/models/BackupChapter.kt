package suwayomi.tachidesk.manga.impl.backup.proto.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass

@Serializable
data class BackupChapter(
    // in 1.x some of these values have different names
    // url is called key in 1.x
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    // lastPageRead is called progress in 1.x
    @ProtoNumber(6) var lastPageRead: Int = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    // chapterNumber is called number is 1.x
    @ProtoNumber(9) var chapterNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Int = 0,
) {
    fun toChapterDataClass(chapterCount: Int): ChapterDataClass {
        return ChapterDataClass (
            url = this@BackupChapter.url,
            name = this@BackupChapter.name,
            chapterNumber = this@BackupChapter.chapterNumber,
            scanlator = this@BackupChapter.scanlator,
            read = this@BackupChapter.read,
            bookmarked = this@BackupChapter.bookmark,
            lastPageRead = this@BackupChapter.lastPageRead,
            uploadDate = this@BackupChapter.dateUpload,
            index = chapterCount - this@BackupChapter.sourceOrder,
            downloaded = false,
            lastReadAt = 0,
            mangaId = -1
        )
    }

    companion object {
        fun copyFrom(chapter: ChapterDataClass): BackupChapter {
            return BackupChapter(
                url = chapter.url,
                name = chapter.name,
                chapterNumber = chapter.chapterNumber,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmarked,
                lastPageRead = chapter.lastPageRead,
                dateUpload = chapter.uploadDate,
                sourceOrder = chapter.index
            )
        }
    }
}
