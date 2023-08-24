package info.skyblond.nojre.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import info.skyblond.nojre.NojreForegroundService
import info.skyblond.nojre.ui.intent
import info.skyblond.nojre.ui.theme.NojreTheme

class BroadcastActivity:NojreAbstractActivity(
    buildMap {
        put(Manifest.permission.RECORD_AUDIO, "broadcast your voice")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            put(Manifest.permission.POST_NOTIFICATIONS, "foreground service")
    }
) {
    // list of peers port.
    private var recording by mutableStateOf(false)

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NojreTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        var volume by remember { mutableStateOf(100) }
                        Button(onClick = {
                            volume = (volume - 1).coerceAtLeast(0)
//                            if (serviceBounded) nojreService.ourVolume = volume / 100.0
                        }) {
                            Text(text = "Volume down")
                        }
                        Text(text = "$volume")
                        Button(onClick = {
                            volume = (volume + 1).coerceAtMost(200)
//                            if (serviceBounded) nojreService.ourVolume = volume / 100.0
                        }) {
                            Text(text = "Volume up")
                        }
//                        if (serviceBounded){
//                            for (p in nojreService.peerMap) {
//                                Text(text = p.key)
//                            }
//                        }
                    }
                }
            }
        }
    }
}