package se.sodapop.youpicwallpaper

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.content.WakefulBroadcastReceiver
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.widget.Button
import android.widget.Toast
import com.android.volley.RequestQueue
import com.android.volley.Response.ErrorListener
import com.android.volley.Response.Listener
import com.android.volley.toolbox.ImageRequest
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.pawegio.kandroid.find
import com.pawegio.kandroid.toast
import org.json.JSONObject
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    val alarm = AlarmReceiver()

    fun isAlarmSet(): Boolean {
        val intent = Intent(applicationContext, AlarmReceiver::class.java)
        intent.action = AlarmReceiver.ACTION_ALARM_RECEIVER

        return (PendingIntent.getBroadcast(applicationContext, 0, intent,
                PendingIntent.FLAG_NO_CREATE) != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val toolbar = findViewById(R.id.toolbar) as Toolbar?
        setSupportActionBar(toolbar)

        val start = find<Button>(R.id.startService)
        val stop = find<Button>(R.id.stopService)
        val manual = find<Button>(R.id.manual)

        manual.setOnClickListener {
            manual.isEnabled = false
            manual.text = "Downloading..."

            fetchWallpaper({ result ->
                if (result == WallpaperResult.ALREADY_DOWNLOADED) {
                    toast("Already downloaded")
                }

                manual.isEnabled = true
                manual.text = "Download wallpaper"
            })
        }

        val isSet = isAlarmSet()

        Timber.i("onCreate. Alarm is $isSet")

        start.isEnabled = !isSet
        stop.isEnabled = isSet

        start.setOnClickListener {
            alarm.setAlarm(applicationContext)

            start.isEnabled = false
            stop.isEnabled = true

            toast("Service started")
        }

        stop.setOnClickListener {
            alarm.cancelAlarm(applicationContext)

            stop.isEnabled = false
            start.isEnabled = true

            toast("Service stopped")
        }
    }
}

open class AlarmReceiver() : WakefulBroadcastReceiver() {

    companion object {
        val ACTION_ALARM_RECEIVER = "YouPicWallpaperAlarmReceiver"
    }

    private var alarmManager: AlarmManager? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        val service = Intent(context, DownloadService::class.java)
        startWakefulService(context, service)

        Timber.i("Alarm received")
    }

    fun setAlarm(context: Context) {
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // val interval = AlarmManager.INTERVAL_DAY
        val interval = 3 * 60 * 60 * 1000L // 3 hours

        val intent = Intent(context, AlarmReceiver::class.java)
        intent.action = AlarmReceiver.ACTION_ALARM_RECEIVER

        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT)
        alarmManager!!.setRepeating(AlarmManager.RTC_WAKEUP, 0, interval, pendingIntent)

        Timber.i("Alarm set")

        val receiver = ComponentName(context, OnBootBroadcastReceiver::class.java)
        context.packageManager.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    fun cancelAlarm(context: Context) {

        val intent = Intent(context, AlarmReceiver::class.java)
        intent.action = AlarmReceiver.ACTION_ALARM_RECEIVER

        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT)
        alarmManager?.cancel(pendingIntent)
        pendingIntent?.cancel()

        val receiver = ComponentName(context, OnBootBroadcastReceiver::class.java)
        context.packageManager.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

}

open class DownloadService : IntentService("YouPicWallpaperDownloadService") {

    override fun onHandleIntent(intent: Intent?) {
        Timber.i("Intent handled")

        fetchWallpaper()

        WakefulBroadcastReceiver.completeWakefulIntent(intent)
    }
}

open class OnBootBroadcastReceiver : BroadcastReceiver() {

    private val alarm: AlarmReceiver = AlarmReceiver()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action.equals("android.intent.action.BOOT_COMPLETED")) {
            alarm.setAlarm(context)

            Timber.i("Alarm set on boot")
        }
    }
}

class VolleyHandler(val context: Context) {
    private var storedQueue: RequestQueue? = null

    val requestQueue: RequestQueue
        get() {
            if (storedQueue == null) {
                storedQueue = Volley.newRequestQueue(context.applicationContext)
            }

            return storedQueue!!
        }

    fun <T> downloadBitmap(url: String, callback: (Bitmap) -> T) {
        Timber.i("Download url $url")

        requestQueue.add(ImageRequest(url, Listener<Bitmap> {
            callback(it)
        }, 0, 0, null, Bitmap.Config.RGB_565, ErrorListener { Timber.w(it.toString()) }))
    }

    fun <T> fetchURL(callback: (String) -> T) {
        requestQueue.add(JsonObjectRequest(API_URL, null, Listener<JSONObject> {
            val hugeURL = it.getJSONObject("image_urls").getString("huge")

            Timber.i("Huge url $hugeURL")

            callback(hugeURL)
        }, ErrorListener { Timber.w(it.toString()) }))
    }
}

const val API_URL = "https://api.youpic.com/web_image"

enum class WallpaperResult {
    ALREADY_DOWNLOADED, WALLPAPER_SET
}

fun Context.fetchWallpaper(callback: ((WallpaperResult) -> Unit)? = null) {
    val pref = this.getSharedPreferences(
            this.getString(R.string.preference_file_key), Context.MODE_PRIVATE)

    val wh = VolleyHandler(this)

    wh.fetchURL { url ->
        if (pref.getString("LAST_URL", "").equals(url)) {
            Timber.i("Already downloaded")

            callback?.invoke(WallpaperResult.ALREADY_DOWNLOADED)
        } else {
            wh.downloadBitmap(url, { bmp ->
                pref.edit().putString("LAST_URL", url).apply()

                val wallpaperManager = WallpaperManager.getInstance(this)
                wallpaperManager.setBitmap(bmp)

                toast(this, "Successfully set wallpaper")

                callback?.invoke(WallpaperResult.WALLPAPER_SET)
            })
        }
    }
}

fun toast(context: Context, text: CharSequence): Unit =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()