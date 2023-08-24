package info.skyblond.nojre.ui.activity

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import info.skyblond.nojre.NojreForegroundService
import info.skyblond.nojre.dataStore
import info.skyblond.nojre.ui.intent
import info.skyblond.nojre.ui.startActivity
import info.skyblond.nojre.ui.theme.NojreTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : NojreAbstractActivity(
    buildMap {
        put(Manifest.permission.RECORD_AUDIO, "broadcast your voice")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            put(Manifest.permission.POST_NOTIFICATIONS, "foreground service")
    }
) {

    private var nickname by mutableStateOf("")
    private val nicknameKey = stringPreferencesKey("nickname")

    private var password by mutableStateOf("")
    private val passwordKey = stringPreferencesKey("password")

    // 239.255.0.0/16
    // use can only choose the lower 16 bit -> 0~65535
    private var groupChannel by mutableStateOf(0)
    private val groupChannelKey = intPreferencesKey("groupChannel")

    private var groupPort by mutableStateOf(1024)
    private val groupPortKey = intPreferencesKey("groupPort")

    private var useVoiceCall by mutableStateOf(false)
    private val useVoiceCallKey = booleanPreferencesKey("useVoiceCall")

    // the string buffer for numeric values
    private var channelText by mutableStateOf(groupChannel.toString())
    private var portText by mutableStateOf(groupChannel.toString())

    private lateinit var dataStoreCollectingScope: Job

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStoreCollectingScope = lifecycleScope.launch {
            dataStore.data.collect { p ->
                p[nicknameKey]?.let { nickname = it.take(10) }
                p[passwordKey]?.let { password = it }
                p[groupChannelKey]?.let {
                    groupChannel = it.coerceIn(0, 65535)
                    channelText = groupChannel.toString()
                }
                p[groupPortKey]?.let {
                    groupPort = it.coerceIn(1024, 65535)
                    portText = groupPort.toString()
                }
                p[useVoiceCallKey]?.let { useVoiceCall = it }
            }
        }

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
                        Text(text = "Nickname")
                        TextField(value = nickname, onValueChange = {
                            nickname = it.take(10)
                        }, singleLine = true)
                        Spacer(modifier = Modifier.fillMaxHeight(0.04f))

                        Text(text = "Password")
                        TextField(value = password, onValueChange = {
                            password = it
                        }, singleLine = true)
                        Spacer(modifier = Modifier.fillMaxHeight(0.04f))
                        Text(text = "Channel (0~65535)")
                        TextField(
                            value = channelText,
                            onValueChange = {
                                channelText = it
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.fillMaxHeight(0.04f))

                        Text(text = "Port (1024~65535)")
                        TextField(
                            value = portText,
                            onValueChange = {
                                portText = it
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.fillMaxHeight(0.04f))

                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Use Media")
                            Spacer(modifier = Modifier.fillMaxWidth(0.02f))
                            Switch(checked = useVoiceCall, onCheckedChange = {
                                useVoiceCall = it
                            })
                            Spacer(modifier = Modifier.fillMaxWidth(0.02f))
                            Text(text = "Use Voice")
                        }
                        Spacer(modifier = Modifier.fillMaxHeight(0.04f))

                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                enabled = (channelText.toIntOrNull() ?: -1) in 0..65535
                                        && (portText.toIntOrNull() ?: -1) in 1024..65535,
                                onClick = {
                                    // save options
                                    groupChannel = channelText.toInt()
                                    groupPort = portText.toInt()
                                    lifecycleScope.launch {
                                        dataStore.edit { p ->
                                            p[nicknameKey] = nickname
                                            p[passwordKey] = password
                                            p[groupChannelKey] = groupChannel
                                            p[groupPortKey] = groupPort
                                            p[useVoiceCallKey] = useVoiceCall
                                        }
                                    }
                                    // start service
                                    val service = intent(NojreForegroundService::class)
                                    service.putExtra("nickname", nickname)
                                    service.putExtra("password", password)
                                    service.putExtra(
                                        "group_address",
                                        "239.255.${groupChannel / 256}.${groupChannel % 256}"
                                    )
                                    service.putExtra("group_port", groupPort)
                                    service.putExtra("use_voice", useVoiceCall)
                                    stopService(service)
                                    startForegroundService(service)
                                }) {
                                Text(text = "Start")
                            }
                            Spacer(modifier = Modifier.fillMaxWidth(0.04f))
                            Button(onClick = {
                                startActivity(BroadcastDetailActivity::class)
                            }) {
                                Text(text = "Details")
                            }
                            Spacer(modifier = Modifier.fillMaxWidth(0.04f))
                            Button(onClick = {
                                stopService(intent(NojreForegroundService::class))
                            }) {
                                Text(text = "Stop")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(intent(NojreForegroundService::class))
    }
}
