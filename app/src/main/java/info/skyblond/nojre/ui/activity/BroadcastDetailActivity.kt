package info.skyblond.nojre.ui.activity

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.skyblond.nojre.NojreForegroundService
import info.skyblond.nojre.ui.intent
import info.skyblond.nojre.ui.showToast
import info.skyblond.nojre.ui.theme.NojreTheme

class BroadcastDetailActivity : NojreAbstractActivity() {
    private var serviceBounded by mutableStateOf(false)
    private lateinit var nojreService: NojreForegroundService
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NojreForegroundService.LocalBinder
            nojreService = binder.service
            serviceBounded = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            serviceBounded = false
        }
    }

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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(15.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.Start
                    ) {
                        if (serviceBounded) {
                            if (nojreService.serviceRunning.get()) {
                                DetailPage()
                            } else {
                                showToast("Service not running...")
                                finish()
                            }
                        } else {
                            Text(
                                text = "Connecting service...",
                                fontSize = 30.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ColumnScope.DetailPage() {
        Text(text = "Nickname: ${nojreService.nickname}")
        Text(text = "Password: ${nojreService.password}")
        Text(text = "Multicast ip: ${nojreService.groupAddress}")
        Text(text = "Multicast port: ${nojreService.groupPort}")
        Text(text = "Output: ${if (nojreService.useVoiceCall) "Voice" else "Media"}")
        Spacer(modifier = Modifier.height(20.dp))
        var volumeLock by remember { mutableStateOf(true) }
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Our volume: ${(nojreService.ourVolume * 100).toInt()}%")
            Spacer(modifier = Modifier.fillMaxWidth(0.04f))
            Button(
                onClick = { volumeLock = !volumeLock },
                colors = if (volumeLock) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = Color(0xFFB80000), contentColor = Color.White),
                ) {
                Text(text = if (volumeLock) "Unlock volume" else " Lock volume ")
            }
        }
        Slider(
            value = nojreService.ourVolume.toFloat(),
            valueRange = 0f..1.5f, onValueChange = {
                nojreService.ourVolume = (it * 100).toInt() / 100.0
            }, enabled = !volumeLock
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text(text = "Known peers:")
        Spacer(modifier = Modifier.height(10.dp))
        for (p in nojreService.peerMap) {
            Text(text = "${p.key}: ${p.value.nickname}")
            var localVolumeLock by remember { mutableStateOf(true) }
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "local volume: ${(p.value.volume * 100).toInt()}%")
                Spacer(modifier = Modifier.fillMaxWidth(0.04f))
                Button(
                    onClick = { localVolumeLock = !localVolumeLock },
                    colors = if (localVolumeLock) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = Color(0xFFB80000), contentColor = Color.White),
                ) {
                    Text(text = if (localVolumeLock) "Unlock volume" else " Lock volume ")
                }
            }
            Slider(
                value = p.value.volume.toFloat(),
                valueRange = 0f..1.5f, onValueChange = {
                    p.value.volume = (it * 100).toInt() / 100.0
                }, enabled = !localVolumeLock
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(intent(NojreForegroundService::class), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        serviceBounded = false
    }
}