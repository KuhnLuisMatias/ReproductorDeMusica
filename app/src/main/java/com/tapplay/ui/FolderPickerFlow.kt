package com.tapplay.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Folder flow controller (spec: folder-selection, design D1): audio permission
 * on first use → native SAF tree picker. Denial is silent (idle PlayerScreen);
 * permanent denial makes the next tap open the app settings page.
 */
class FolderPickerFlow internal constructor(
    private val onTapped: () -> Unit,
) {
    fun onFolderIconTapped() = onTapped()
}

@Composable
fun rememberFolderPickerFlow(onFolderPicked: (Uri) -> Unit): FolderPickerFlow {
    val context = LocalContext.current
    val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val treePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onFolderPicked(uri)
            }
        }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                treePickerLauncher.launch(null)
            } else if (!context.findActivity().shouldShowRequestPermissionRationale(audioPermission)) {
                permanentlyDenied = true
            }
        }

    return remember {
        FolderPickerFlow(
            onTapped = {
                when {
                    permanentlyDenied -> openAppSettings(context)
                    ContextCompat.checkSelfPermission(context, audioPermission) ==
                        PackageManager.PERMISSION_GRANTED -> treePickerLauncher.launch(null)
                    else -> audioPermissionLauncher.launch(audioPermission)
                }
            },
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    context.startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("Context is not attached to an Activity")
}
