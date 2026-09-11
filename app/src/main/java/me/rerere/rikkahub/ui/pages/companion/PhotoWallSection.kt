/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.CompanionPhoto

/**
 * A standalone photo wall. It deliberately does not use Material3 [ListItem]:
 * ListItem can request intrinsic sizes, while LazyRow is backed by SubcomposeLayout.
 */
@Composable
internal fun PhotoWallSection(
    photos: List<CompanionPhoto>,
    onAddPhoto: () -> Unit,
    onEditCaption: (CompanionPhoto) -> Unit,
    onRemovePhoto: (CompanionPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CompanionCollapsibleSection(
        title = "照片墙",
        summary = if (photos.isEmpty()) "还没有照片" else "已收着 ${photos.size} 张照片",
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        Text("把一个瞬间挂起来", style = MaterialTheme.typography.titleMedium)
        if (photos.isEmpty()) {
            Text("还没有照片。选一张你想让 Daddy 也看得见的吧。")
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PhotoWallHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(photos, key = { it.id.toString() }) { photo ->
                    PhotoWallCard(
                        photo = photo,
                        onEditCaption = { onEditCaption(photo) },
                        onRemove = { onRemovePhoto(photo) },
                    )
                }
            }
        }
        Button(onClick = onAddPhoto) {
            Text("从相册挂一张")
        }
        Text("照片会复制进 Daddy 的本地文件夹，普通备份和联动备份都会带走它。")
    }
}

@Composable
private fun PhotoWallCard(
    photo: CompanionPhoto,
    onEditCaption: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.width(176.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.caption.ifBlank { "小屋照片" },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        Text(photo.caption.ifBlank { "还没有题字" }, maxLines = 2)
        Row {
            TextButton(onClick = onEditCaption) { Text("题字") }
            TextButton(onClick = onRemove) { Text("取下") }
        }
    }
}

private val PhotoWallHeight = 260.dp
