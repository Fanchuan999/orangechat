package me.rerere.rikkahub.data.pcbridge

/**
 * Stable, display-safe copy for the Code Hut computer card.
 *
 * Do not use values carried by [PcBridgeUiState] here. Those values originate outside the UI
 * boundary and may contain credentials or other private machine data.
 */
enum class PcBridgeLocalRecoveryAction {
    AbandonPendingPairing,
    ForgetUnavailableConfirmedPairing,
}

data class PcBridgeUiPolicy(
    val title: String,
    val status: String,
    val detail: String,
    val primaryAction: String?,
    val dangerAction: String?,
    val localRecoveryAction: PcBridgeLocalRecoveryAction? = null,
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
                status = "正在安全连接电脑…",
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

            PcBridgeUiState.PendingRecovery -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "配对尚未确认",
                detail = "可先刷新状态；若电脑端确认未配对，可只清理本机待恢复配对后重新连接。",
                primaryAction = "刷新状态",
                dangerAction = "放弃本机待恢复配对",
                localRecoveryAction = PcBridgeLocalRecoveryAction.AbandonPendingPairing,
            )

            PcBridgeUiState.ConfirmedRecovery -> PcBridgeUiPolicy(
                title = "电脑命令",
                status = "配对状态未确认",
                detail = "可先刷新状态；若要重新连接，可忘记本机电脑配对。远端配对可能仍存在，" +
                    "请先在固定 PC 工作台清理。",
                primaryAction = "刷新状态",
                dangerAction = "忘记本机电脑配对",
                localRecoveryAction = PcBridgeLocalRecoveryAction.ForgetUnavailableConfirmedPairing,
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
