/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.CompanionAnniversary
import me.rerere.rikkahub.data.datastore.CompanionLetter
import me.rerere.rikkahub.data.datastore.CompanionPhoto
import me.rerere.rikkahub.data.datastore.CompanionSharedTask
import me.rerere.rikkahub.data.datastore.CompanionSpaceSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.withAnniversary
import me.rerere.rikkahub.data.datastore.withLetter
import me.rerere.rikkahub.data.datastore.withPhoto
import me.rerere.rikkahub.data.datastore.withSharedTask
import me.rerere.rikkahub.data.datastore.withoutAnniversary
import me.rerere.rikkahub.data.datastore.withoutLetter
import me.rerere.rikkahub.data.datastore.withoutPhoto
import me.rerere.rikkahub.data.datastore.withoutSharedTask
import kotlin.uuid.Uuid

/** Local-only editing for the rooms in Daddy's little house. */
class CompanionSpaceService(
    private val settingsStore: SettingsStore,
) {
    suspend fun addPhoto(uri: String, caption: String = "") {
        require(uri.isNotBlank())
        settingsStore.update { settings ->
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withPhoto(
                    CompanionPhoto(uri = uri, caption = caption.trim().take(MAX_CAPTION_LENGTH))
                )
            )
        }
    }

    suspend fun updatePhotoCaption(photo: CompanionPhoto, caption: String) {
        settingsStore.update { settings ->
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withPhoto(
                    photo.copy(caption = caption.trim().take(MAX_CAPTION_LENGTH))
                )
            )
        }
    }

    suspend fun removePhoto(id: Uuid) = updateSpace { it.withoutPhoto(id) }

    suspend fun addAnniversary(title: String, dateText: String, note: String) {
        require(title.isNotBlank() && dateText.isNotBlank())
        settingsStore.update { settings ->
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withAnniversary(
                    CompanionAnniversary(
                        title = title.trim().take(MAX_TITLE_LENGTH),
                        dateText = dateText.trim().take(MAX_DATE_LENGTH),
                        note = note.trim().take(MAX_NOTE_LENGTH),
                    )
                )
            )
        }
    }

    suspend fun removeAnniversary(id: Uuid) = updateSpace { it.withoutAnniversary(id) }

    suspend fun addLetter(author: String, title: String, content: String) {
        require(author.isNotBlank() && title.isNotBlank() && content.isNotBlank())
        settingsStore.update { settings ->
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withLetter(
                    CompanionLetter(
                        author = author.trim().take(MAX_AUTHOR_LENGTH),
                        title = title.trim().take(MAX_TITLE_LENGTH),
                        content = content.trim().take(MAX_LETTER_LENGTH),
                    )
                )
            )
        }
    }

    suspend fun removeLetter(id: Uuid) = updateSpace { it.withoutLetter(id) }

    suspend fun addSharedTask(content: String) {
        require(content.isNotBlank())
        settingsStore.update { settings ->
            settings.copy(
                companionSpaceSetting = settings.companionSpaceSetting.withSharedTask(
                    CompanionSharedTask(content = content.trim().take(MAX_TASK_LENGTH))
                )
            )
        }
    }

    suspend fun setSharedTaskCompleted(task: CompanionSharedTask, completed: Boolean) = updateSpace {
        it.withSharedTask(task.copy(completed = completed))
    }

    suspend fun removeSharedTask(id: Uuid) = updateSpace { it.withoutSharedTask(id) }

    private suspend fun updateSpace(transform: (CompanionSpaceSetting) -> CompanionSpaceSetting) {
        settingsStore.update { settings ->
            settings.copy(companionSpaceSetting = transform(settings.companionSpaceSetting))
        }
    }

    private companion object {
        const val MAX_CAPTION_LENGTH = 120
        const val MAX_TITLE_LENGTH = 80
        const val MAX_DATE_LENGTH = 32
        const val MAX_NOTE_LENGTH = 240
        const val MAX_AUTHOR_LENGTH = 32
        const val MAX_LETTER_LENGTH = 2_000
        const val MAX_TASK_LENGTH = 160
    }
}
