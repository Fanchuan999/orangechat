package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class CompanionSpaceSettingTest {
    @Test
    fun replacingACandidateKeepsItsPlaceInsteadOfDuplicatingIt() {
        val id = Uuid.random()
        val original = DiaryCandidate(id = id, title = "今天", content = "原草稿", createdAtMillis = 1L)
        val edited = original.copy(content = "改过的草稿")

        val saved = CompanionSpaceSetting().withCandidate(original).withCandidate(edited)

        assertEquals(1, saved.diaryCandidates.size)
        assertEquals("改过的草稿", saved.diaryCandidates.single().content)
    }

    @Test
    fun diaryDeskKeepsTheMostRecentSixtyCandidates() {
        val candidates = (1..65).fold(CompanionSpaceSetting()) { setting, index ->
            setting.withCandidate(
                DiaryCandidate(title = "候选$index", content = "内容$index", createdAtMillis = index.toLong())
            )
        }

        assertEquals(60, candidates.diaryCandidates.size)
        assertEquals("候选6", candidates.diaryCandidates.first().title)
        assertEquals("候选65", candidates.diaryCandidates.last().title)
    }

    @Test
    fun littleHouseItemsReplaceByIdAndCanBeRemoved() {
        val photo = CompanionPhoto(uri = "file:///photo.jpg", createdAtMillis = 1L)
        val task = CompanionSharedTask(content = "一起看一部电影", createdAtMillis = 2L)

        val setting = CompanionSpaceSetting()
            .withPhoto(photo)
            .withPhoto(photo.copy(caption = "第一张照片"))
            .withSharedTask(task)
            .withSharedTask(task.copy(completed = true))

        assertEquals(1, setting.photos.size)
        assertEquals("第一张照片", setting.photos.single().caption)
        assertEquals(true, setting.sharedTasks.single().completed)
        assertEquals(0, setting.withoutPhoto(photo.id).photos.size)
        assertEquals(0, setting.withoutSharedTask(task.id).sharedTasks.size)
    }
}
