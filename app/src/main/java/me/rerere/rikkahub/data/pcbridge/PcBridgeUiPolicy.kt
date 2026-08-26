package me.rerere.rikkahub.data.pcbridge

/**
 * Stable, display-safe copy for the Code Hut computer card.
 *
 * Do not use values carried by [PcBridgeUiState] here. Those values originate outside the UI
 * boundary and may contain credentials or other private machine data.
 */
data class PcBridgeUiPolicy(
    val title: String,
    val status: String,
    val detail: String,
    val primaryAction: String?,
    val dangerAction: String?,
) {
    fun flattenText(): String = listOfNotNull(title, status, detail, primaryAction, dangerAction).joinToString("\n")

    companion object {
        fun from(state: PcBridgeUiState): PcBridgeUiPolicy = when (state) {
            PcBridgeUiState.Unpaired -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "未连接",
                detail = "连接电脑后，可在此安全发送命令。",
                primaryAction = "连接电脑",
                dangerAction = null,
            )

            is PcBridgeUiState.InvitationDraft -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "等待确认",
                detail = "请确认电脑邀请后继续连接。",
                primaryAction = "确认连接",
                dangerAction = null,
            )

            PcBridgeUiState.Pairing -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "连接中",
                detail = "正在建立安全连接，请稍候。",
                primaryAction = null,
                dangerAction = null,
            )

            is PcBridgeUiState.Paired -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "已连接",
                detail = "中继已连接，可随时刷新状态或解除配对。",
                primaryAction = "刷新状态",
                dangerAction = "解除配对",
            )

            is PcBridgeUiState.Unavailable -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "暂不可用",
                detail = "暂时无法连接电脑，请稍后刷新状态或重新连接。",
                primaryAction = "刷新状态",
                dangerAction = null,
            )
        }
    }
}
