package me.rerere.rikkahub.data.lounge

import kotlinx.coroutines.flow.first

/** The deliberately narrow Visitor Lounge boundary exposed to native tools. */
interface VisitorLoungeToolGateway {
    suspend fun savedFriends(): List<VisitorLoungeToolFriend>

    suspend fun startManual(
        sourceConversationId: String?,
        friendId: String,
        topic: String,
    ): VisitorLoungeStartResult
}

data class VisitorLoungeToolFriend(
    val id: String,
    val displayName: String,
)

class DefaultVisitorLoungeToolGateway(
    private val repository: VisitorLoungeRepository,
    private val coordinator: VisitorLoungeVisitCoordinator,
) : VisitorLoungeToolGateway {
    override suspend fun savedFriends(): List<VisitorLoungeToolFriend> = repository.observeFriends().first()
        .map { friend ->
            VisitorLoungeToolFriend(
                id = friend.id,
                displayName = friend.displayName.replace(Regex("[\\r\\n]+"), " ").take(MAX_DISPLAY_NAME_CHARACTERS),
            )
        }

    override suspend fun startManual(
        sourceConversationId: String?,
        friendId: String,
        topic: String,
    ): VisitorLoungeStartResult = coordinator.startManual(sourceConversationId, friendId, topic)

    private companion object {
        const val MAX_DISPLAY_NAME_CHARACTERS = 80
    }
}
