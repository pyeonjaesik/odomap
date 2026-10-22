package com.odomap.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.odomap.app.OdomapApp
import com.odomap.app.record.RecordingService
import com.odomap.app.ui.theme.OdoTheme
import com.odomap.app.ui.theme.UiScaleRoot

class MainActivity : ComponentActivity() {
    private val hasLocation = mutableStateOf(false)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recorder = (application as OdomapApp).recorder
        refreshPermission()
        setContent {
            OdoTheme {
                UiScaleRoot {
                    OdoAppUi(
                        recorder = recorder,
                        hasLocation = hasLocation.value,
                        onAskPermission = ::askForPermissions,
                    )
                }
            }
        }
    }

    private fun askForPermissions() {
        val wanted = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (packageManager.hasSystemFeature("android.hardware.type.automotive")) wanted.add("android.car.permission.CAR_SPEED")
        askPermissions.launch(wanted.toTypedArray())
    }

    private fun refreshPermission() {
        hasLocation.value = RecordingService.canRun(this)
        if (hasLocation.value) RecordingService.start(this)
    }
}
