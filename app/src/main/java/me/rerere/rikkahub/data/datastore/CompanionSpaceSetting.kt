/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Local-first data for Daddy's companion space. It is regular Settings data,
 * so the existing Daddy backup/restore flow carries it with the user.
 */
@Serializable
data class CompanionSpaceSetting(
    val diaryCandidates: List<DiaryCandidate> = emptyList(),
)

/**
 * A short reflection generated from recent chat text. It starts as a draft and
 * only receives [ombreSavedAtMillis] after the user explicitly confirms it.
 */
@Serializable
data class DiaryCandidate(
    val id: Uuid = Uuid.random(),
    val title: String,
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val ombreSavedAtMillis: Long? = null,
)

fun CompanionSpaceSetting.withCandidate(candidate: DiaryCandidate): CompanionSpaceSetting = copy(
    diaryCandidates = (diaryCandidates.filterNot { it.id == candidate.id } + candidate)
        .sortedBy { it.createdAtMillis }
        .takeLast(MAX_DIARY_CANDIDATES),
)

const val MAX_DIARY_CANDIDATES = 60
