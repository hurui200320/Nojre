package info.skyblond.nojre.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlin.reflect.KClass

fun Context.intent(clazz: KClass<*>): Intent = Intent(this, clazz.java)

fun Context.startActivity(clazz: KClass<*>): Unit = this.startActivity(intent(clazz))

fun Context.showToast(content: String, duration: Int = Toast.LENGTH_LONG): Unit =
    Toast.makeText(this, content, duration).show()

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
