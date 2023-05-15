package com.mrsep.musicrecognizer.feature.track.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.mrsep.musicrecognizer.core.common.util.lifecycleIsResumed

object TrackScreen {

    private const val ROOT_ROUTE = "track"
    private const val ROOT_DEEP_LINK = "app://mrsep.musicrecognizer.com"
    private const val ARG_MB_ID = "mbId"

    const val ROUTE = "$ROOT_ROUTE/{$ARG_MB_ID}"

    data class Args(val mbId: String) {
        constructor(savedStateHandle: SavedStateHandle) : this(
            mbId = checkNotNull(savedStateHandle["mbId"])
        )
    }

    private fun routeWithArgs(mbId: String) = "$ROOT_ROUTE/$mbId"

    fun NavGraphBuilder.trackScreen(onBackPressed: () -> Unit) {
        composable(
            route = ROUTE,
            deepLinks = listOf(navDeepLink {
                uriPattern = "$ROOT_DEEP_LINK/$ROUTE"
            })
        ) { backStackEntry ->
            TrackScreen(onBackPressed = onBackPressed)
        }
    }

    fun NavController.navigateToTrackScreen(
        mbId: String,
        from: NavBackStackEntry,
        navOptions: NavOptions? = null
    ) {
        if (from.lifecycleIsResumed) {
            this.navigate(route = routeWithArgs(mbId), navOptions = navOptions)
        }

    }

    fun createDeepLink(mbId: String): String {
        return "$ROOT_DEEP_LINK/${routeWithArgs(mbId)}"
    }

}