package dev.whispr.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.whispr.android.ui.chats.ChatsRoute
import dev.whispr.android.ui.onboarding.OnboardingRoute
import dev.whispr.android.ui.settings.SettingsRoute
import dev.whispr.domain.usecase.StartDestination
import kotlinx.serialization.Serializable

@Serializable object OnboardingDestination

@Serializable object ChatsDestination

@Serializable object SettingsDestination

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
            ChatsRoute(onOpenSettings = { nav.navigate(SettingsDestination) { launchSingleTop = true } })
        }
        composable<SettingsDestination> {
            SettingsRoute(onBack = { nav.popBackStack() })
        }
    }
}
