package app.discoredact.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DiscoRedactApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme) {
        BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
        when (val r = vm.route) {
            Route.Channels -> ChannelsScreen(vm)
            Route.Import -> ImportScreen(vm)
            is Route.Browser -> vm.browser?.takeIf { it.channelId == r.channelId }?.let { BrowserScreen(vm, it) }
            is Route.Filters -> vm.filters?.let { FiltersScreen(vm, it) }
            Route.Review -> ReviewScreen(vm)
            is Route.Frozen -> FrozenScreen(vm, r)
            Route.Settings -> SettingsScreen(vm)
        }
    }
}
