package com.rasel.RasFocus.combo.selfcontrol

import androidx.compose.runtime.*
import androidx.navigation.NavController
import com.rasel.RasFocus.MainViewModel
import com.rasel.RasFocus.combo.parental.ParentControlScreen
import com.rasel.RasFocus.combo.parental.ParentControls

/**
 * PC Control — SelfControl module থেকে launch হওয়া PC parental control screen।
 * ParentalRootScreen-এর picker bypass করে সরাসরি PC control-এ যায়।
 * PC device না থাকলে pairing PIN দিয়ে pair করার সুযোগ দেয়।
 */
@Composable
fun PcControlScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val pcPin by viewModel.connectionPin.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val pcDevices = devices.filter { it.type == com.rasel.RasFocus.DeviceType.PC }
    val firstPcId = pcDevices.firstOrNull()?.id ?: ""

    val pairedId = viewModel.pairedPcDeviceId.collectAsState(null).value
    var resolvedDeviceId by remember { mutableStateOf(firstPcId) }

    LaunchedEffect(pairedId) {
        pairedId?.let { hwId ->
            resolvedDeviceId = hwId
            viewModel.stopPcPairingListener()
            viewModel.clearPairedPcDeviceId()
        }
    }

    ParentControlScreen(
        onBack         = { navController.popBackStack() },
        deviceId       = resolvedDeviceId,
        viewModel      = viewModel,
        pin            = pcPin,
        devices        = pcDevices.map { it.id to (it.isOnline) },
        selectedDevice = resolvedDeviceId.ifEmpty { null },
        onRefreshPin   = { viewModel.refreshPin() },
        onSelectDevice = { id -> resolvedDeviceId = id },
        onLogout       = { navController.popBackStack() },
        onControlChange = { controls ->
            if (resolvedDeviceId.isNotEmpty()) {
                viewModel.updatePcControls(resolvedDeviceId, controls.toNonComboControls())
            }
        },
        onSendPower = { action ->
            if (resolvedDeviceId.isNotEmpty()) {
                viewModel.sendPcPowerCommand(resolvedDeviceId, action)
            }
        },
        onScheduleLock = { ms, type ->
            if (resolvedDeviceId.isNotEmpty()) {
                viewModel.schedulePcLock(resolvedDeviceId, ms, type)
            }
        },
        onCancelSchedule = {
            if (resolvedDeviceId.isNotEmpty()) {
                viewModel.cancelPcSchedule(resolvedDeviceId)
            }
        },
        onPaired = { hwId ->
            resolvedDeviceId = hwId
        }
    )
}

// Extension: ParentControls (combo) → ParentControls (non-combo) conversion
private fun ParentControls.toNonComboControls(): com.rasel.RasFocus.parental.ParentControls =
    com.rasel.RasFocus.parental.ParentControls(
        lockAllTabs = lockAllTabs, forceAdultBlock = forceAdultBlock,
        forceReelsBlock = forceReelsBlock, forceShortsBlock = forceShortsBlock,
        appControlEnabled = appControlEnabled, appMode = appMode,
        allowedAppsCsv = allowedAppsCsv, blockedAppsCsv = blockedAppsCsv,
        webBlockEnabled = webBlockEnabled, blockedWebsCsv = blockedWebsCsv,
        blockTaskManager = blockTaskManager, blockSettings = blockSettings,
        blockFileManager = blockFileManager, blockedFoldersCsv = blockedFoldersCsv,
        internetFasting = internetFasting, timeLimitMinutes = timeLimitMinutes,
        powerAction = powerAction, lockUntilEpoch = lockUntilEpoch,
        lockType = lockType, newInstalledAppsCsv = newInstalledAppsCsv,
        fbEnabled = fbEnabled, fbStartTime = fbStartTime, fbEndTime = fbEndTime,
        fbLiteEnabled = fbLiteEnabled, fbLiteStartTime = fbLiteStartTime, fbLiteEndTime = fbLiteEndTime,
        ytEnabled = ytEnabled, ytStartTime = ytStartTime, ytEndTime = ytEndTime,
        chromeEnabled = chromeEnabled, chromeStartTime = chromeStartTime, chromeEndTime = chromeEndTime,
        deepStudyEnabled = deepStudyEnabled, buttonPhoneEnabled = buttonPhoneEnabled,
        singleAppsBlockEnabled = singleAppsBlockEnabled, extremeBlockEnabled = extremeBlockEnabled,
        singleWebsiteBlockEnabled = singleWebsiteBlockEnabled, familyBrowserEnabled = familyBrowserEnabled
    )
