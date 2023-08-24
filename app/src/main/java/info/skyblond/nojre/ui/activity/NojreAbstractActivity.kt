package info.skyblond.nojre.ui.activity

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

abstract class NojreAbstractActivity(
    private val permissionExplanation: Map<String, String> = emptyMap()
) : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, isGranted) ->
            if (!isGranted) {
                AlertDialog.Builder(this)
                    .setTitle("Failed to grant permission")
                    .setMessage(
                        "Permission: $permission is not granted.\n" +
                                "This permission is required for ${permissionExplanation[permission]}."
                    )
                    .setCancelable(false)
                    .setNeutralButton("Fine") { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        finish()
                    }
                    .create()
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions(permissionExplanation.keys.toList())
    }

    private fun ensurePermissions(permissions: List<String>) {
        val array = permissions
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .onEach { require(permissionExplanation.containsKey(it)) { "Unexplainable permission $it" } }
            .toTypedArray()
        if (array.isNotEmpty())
            requestPermissionLauncher.launch(array)
    }

}