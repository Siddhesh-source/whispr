package dev.whispr.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.whispr.android.ui.calls.CallsRoute
import dev.whispr.android.ui.chat.ChatRoute
import dev.whispr.android.ui.chats.ChatsRoute
import dev.whispr.android.ui.contacts.AddContactRoute
import dev.whispr.android.ui.groups.GroupInfoRoute
import dev.whispr.android.ui.groups.NewGroupRoute
import dev.whispr.android.ui.home.HomeRoute
import dev.whispr.android.ui.onboarding.OnboardingRoute
import dev.whispr.android.ui.profile.EditProfileRoute
import dev.whispr.android.ui.profile.MyCodeRoute
import dev.whispr.android.ui.scan.ScanContactRoute
import dev.whispr.android.ui.search.SearchRoute
import dev.whispr.android.ui.settings.SettingsRoute
import dev.whispr.android.ui.status.StatusComposeRoute
import dev.whispr.android.ui.status.StatusRoute
import dev.whispr.android.ui.status.StatusStart
import dev.whispr.android.ui.status.StatusViewerRoute
import dev.whispr.android.ui.verify.VerifyRoute
import dev.whispr.domain.model.UserId
import dev.whispr.domain.usecase.StartDestination
import kotlinx.serialization.Serializable

@Serializable object OnboardingDestination

@Serializable object ChatsDestination

@Serializable object SettingsDestination

@Serializable object AddContactDestination

@Serializable data class ChatDestination(val peerId: String)

@Serializable data class VerifyDestination(val peerId: String)

@Serializable data class GroupChatDestination(val groupId: String)

@Serializable data class GroupInfoDestination(val groupId: String)

@Serializable object NewGroupDestination

@Serializable object ScanContactDestination

@Serializable object MyCodeDestination

@Serializable object EditProfileDestination

@Serializable object SearchDestination

@Serializable data class StatusComposeDestination(val start: String)

@Serializable data class StatusViewerDestination(val authorId: String)

/**
 * The app's screens. [onCall] starts a call (the caller has the microphone
 * and camera permission flow); [openCallsTab] is set when a missed-call
 * notification asks for the Calls tab.
 */
@Composable
fun WhisprNavHost(
    start: StartDestination,
    onCall: (UserId, Boolean) -> Unit = { _, _ -> },
    openCallsTab: Boolean = false,
    onCallsTabOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = when (start) {
            StartDestination.Onboarding -> OnboardingDestination
            StartDestination.Chats -> ChatsDestination
        },
    ) {
        composable<OnboardingDestination> {
            OnboardingRoute(
                onCompleted = {
                    // Onboarding is one-way: Back from Chats must not return to it.
                    nav.navigate(ChatsDestination) { popUpTo<OnboardingDestination> { inclusive = true } }
                },
            )
        }
        composable<ChatsDestination> {
            HomeRoute(
                chats = {
                    ChatsRoute(
                        onOpenSettings = { nav.navigate(SettingsDestination) { launchSingleTop = true } },
                        onMyCode = { nav.navigate(MyCodeDestination) { launchSingleTop = true } },
                        onOpenChat = { peer -> nav.navigate(ChatDestination(peer.value)) { launchSingleTop = true } },
                        onNewChat = { nav.navigate(AddContactDestination) { launchSingleTop = true } },
                        onOpenGroup = { g -> nav.navigate(GroupChatDestination(g.value)) { launchSingleTop = true } },
                        onNewGroup = { nav.navigate(NewGroupDestination) { launchSingleTop = true } },
                        onSearch = { nav.navigate(SearchDestination) { launchSingleTop = true } },
                    )
                },
                status = {
                    StatusRoute(
                        onCompose = { s -> nav.navigate(StatusComposeDestination(s.name)) { launchSingleTop = true } },
                        onOpen = { author ->
                            nav.navigate(StatusViewerDestination(author.value)) {
                                launchSingleTop =
                                    true
                            }
                        },
                    )
                },
                calls = { CallsRoute(onCall = onCall) },
                openCallsTab = openCallsTab,
                onCallsTabOpened = onCallsTabOpened,
            )
        }
        composable<StatusComposeDestination> { entry ->
            val start = StatusStart.valueOf(entry.toRoute<StatusComposeDestination>().start)
            StatusComposeRoute(start = start, onClose = { nav.popBackStack() })
        }
        composable<StatusViewerDestination> { entry ->
            StatusViewerRoute(
                author = UserId(entry.toRoute<StatusViewerDestination>().authorId),
                onClose = { nav.popBackStack() },
            )
        }
        composable<SearchDestination> {
            SearchRoute(
                onBack = { nav.popBackStack() },
                onOpenChat = { peer -> nav.navigate(ChatDestination(peer.value)) },
                onOpenGroup = { g -> nav.navigate(GroupChatDestination(g.value)) },
            )
        }
        composable<AddContactDestination> {
            AddContactRoute(
                onBack = { nav.popBackStack() },
                // Replace the add screen with the chat, so Back returns to the list.
                onAdded = { peer -> nav.openChatReplacing<AddContactDestination>(peer.value) },
                onScan = { nav.navigate(ScanContactDestination) },
                onMyCode = { nav.navigate(MyCodeDestination) },
            )
        }
        composable<ScanContactDestination> {
            ScanContactRoute(
                onBack = { nav.popBackStack() },
                onAdded = { peer ->
                    nav.navigate(ChatDestination(peer.value)) {
                        popUpTo<ChatsDestination>()
                    }
                },
            )
        }
        composable<MyCodeDestination> { MyCodeRoute(onBack = { nav.popBackStack() }) }
        composable<ChatDestination> { entry ->
            val peer = entry.toRoute<ChatDestination>().peerId
            ChatRoute(
                onBack = { nav.popBackStack() },
                onVerify = { nav.navigate(VerifyDestination(peer)) },
                onCall = { video -> onCall(UserId(peer), video) },
            )
        }
        composable<VerifyDestination> { VerifyRoute(onBack = { nav.popBackStack() }) }
        composable<GroupChatDestination> { entry ->
            val group = entry.toRoute<GroupChatDestination>().groupId
            ChatRoute(
                onBack = { nav.popBackStack() },
                onVerify = {},
                onGroupInfo = { nav.navigate(GroupInfoDestination(group)) { launchSingleTop = true } },
            )
        }
        composable<GroupInfoDestination> {
            GroupInfoRoute(
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack<ChatsDestination>(inclusive = false) },
            )
        }
        composable<NewGroupDestination> {
            NewGroupRoute(
                onBack = { nav.popBackStack() },
                onCreated = { g ->
                    nav.navigate(GroupChatDestination(g.value)) {
                        popUpTo<NewGroupDestination> {
                            inclusive =
                                true
                        }
                    }
                },
            )
        }
        composable<EditProfileDestination> { EditProfileRoute(onBack = { nav.popBackStack() }) }
        composable<SettingsDestination> {
            SettingsRoute(
                onBack = { nav.popBackStack() },
                onEditProfile = { nav.navigate(EditProfileDestination) },
                onMyCode = { nav.navigate(MyCodeDestination) },
            )
        }
    }
}

private inline fun <reified T : Any> NavHostController.openChatReplacing(peerId: String) {
    navigate(ChatDestination(peerId)) { popUpTo<T> { inclusive = true } }
}
