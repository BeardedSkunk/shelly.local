package shelly.local.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import shelly.local.data.DeviceRepository
import shelly.local.ui.screens.AddEditDeviceScreen
import shelly.local.ui.screens.BluScreen
import shelly.local.ui.screens.DeviceControlScreen
import shelly.local.ui.screens.DeviceListScreen
import shelly.local.ui.screens.PowerScreen
import shelly.local.ui.screens.SensorScreen
import shelly.local.ui.screens.SettingsScreen

@Composable
fun AppNavHost(repo: DeviceRepository, initialDeviceId: String? = null) {
    val nav = rememberNavController()
    val start = if (initialDeviceId != null) "control/$initialDeviceId" else "devices"

    NavHost(navController = nav, startDestination = start) {
        composable("devices") {
            DeviceListScreen(
                repo = repo,
                onAdd = { nav.navigate("add_device") },
                onDevice = { id -> nav.navigate("control/$id") },
                onBluDevice = { id -> nav.navigate("blu/$id") },
                onEdit = { id -> nav.navigate("edit_device/$id") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "blu/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            BluScreen(
                repo = repo,
                deviceId = deviceId,
                onBack = { nav.popBackStack() },
                onHost = { id -> nav.navigate("control/$id") },
                onReadings = { nav.navigate("readings/$deviceId") },
            )
        }
        composable(
            "readings/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            SensorScreen(repo = repo, deviceId = deviceId, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("add_device") {
            AddEditDeviceScreen(repo = repo, deviceId = null, onDone = { nav.popBackStack() })
        }
        composable(
            "edit_device/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            AddEditDeviceScreen(
                repo = repo,
                deviceId = back.arguments?.getString("deviceId"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            "control/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            DeviceControlScreen(
                repo = repo,
                deviceId = deviceId,
                onBack = { nav.popBackStack() },
                onPower = { nav.navigate("power/$deviceId") },
            )
        }
        composable(
            "power/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            PowerScreen(repo = repo, deviceId = deviceId, onBack = { nav.popBackStack() })
        }
    }
}
