package me.rerere.rikkahub.ui.pages.lounge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.data.lounge.VisitorLoungeRepository
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitCoordinator
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitorLoungeVisitPage(
    visitId: String,
    repository: VisitorLoungeRepository = koinInject(),
    coordinator: VisitorLoungeVisitCoordinator = koinInject(),
) {
    val visits by repository.observeVisits().collectAsStateWithLifecycle(emptyList())
    val entries by repository.observeTranscript(visitId).collectAsStateWithLifecycle(emptyList())
    val visit = visits.firstOrNull { it.id == visitId }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会客室访问") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(title = { Text(visit?.status?.name ?: "记录不存在") }) {
                    item(
                        headlineContent = { Text(visit?.topic ?: "") },
                        supportingContent = { Text(visit?.summary ?: "") },
                    )
                    if (visit != null && visit.status.name in setOf("QUEUED", "CONNECTING", "VISITING")) {
                        item(
                            headlineContent = { Text("结束访问") },
                            supportingContent = { Button(onClick = { scope.launch { coordinator.cancel(visit.id) } }) { Text("取消") } },
                        )
                    }
                }
            }
            item {
                Text("本地转录（最多 20 条）。这些内容不会自动带入普通聊天。")
            }
            items(entries, key = { it.id }) { entry ->
                CardGroup(title = { Text(entry.kind.name) }) {
                    item(
                        headlineContent = { Text(entry.text) },
                    )
                }
            }
        }
    }
}
