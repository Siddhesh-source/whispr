package dev.whispr.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.whispr.android.ui.chat.ChatRoute
import dev.whispr.android.ui.chats.ChatsRoute
import dev.whispr.android.ui.contacts.AddContactRoute
import dev.whispr.android.ui.onboarding.OnboardingRoute
import dev.whispr.android.ui.profile.EditProfileRoute
import dev.whispr.android.ui.profile.MyCodeRoute
import dev.whispr.android.ui.scan.ScanContactRoute
import dev.whispr.android.ui.settings.SettingsRoute
import dev.whispr.android.ui.verify.VerifyRoute
import dev.whispr.domain.usecase.StartDestination
import kotlinx.serialization.Serializable

@Serializable object OnboardingDestination

@Serializable object ChatsDestination

@Serializable object SettingsDestination

@Serializable object AddContactDestination

@Serializable data class ChatDestination(val peerId: String)

@Serializable data class VerifyDestination(val peerId: String)

@Serializable object ScanContactDestination

@Serializable object MyCodeDestination

@Serializable object EditProfileDestination

@Composable
fun WhisprNavHost(start: StartDestination) {
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
            ChatsRoute(
                onOpenSettings = { nav.navigate(SettingsDestination) { launchSingleTop = true } },
                onMyCode = { nav.navigate(MyCodeDestination) { launchSingleTop = true } },
                onOpenChat = { peer -> nav.navigate(ChatDestination(peer.value)) { launchSingleTop = true } },
                onNewChat = { nav.navigate(AddContactDestination) { launchSingleTop = true } },
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
            ChatRoute(onBack = { nav.popBackStack() }, onVerify = { nav.navigate(VerifyDestination(peer)) })
        }
        composable<VerifyDestination> { VerifyRoute(onBack = { nav.popBackStack() }) }
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
