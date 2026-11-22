package com.theoriacodex.visionbench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads thermal, battery, clock and memory state. All reads are passive; nothing is changed. */
class Telemetry(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val cpuFreqFiles = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        .orEmpty().sortedBy { it.name.removePrefix("cpu").toInt() }
        .map { File(it, "cpufreq/scaling_cur_freq") }.filter { it.canRead() }

    fun device(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
        .put("soc_manufacturer", Build.SOC_MANUFACTURER).put("soc_model", Build.SOC_MODEL)
        .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("cores", Runtime.getRuntime().availableProcessors())

    /** One sample of the conditions that explain latency drift. Call at most once per second (headroom limit). */
    fun sample(): JSONObject {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject()
            .put("t_ms", SystemClock.elapsedRealtime())
            .put("thermal_status", power.currentThermalStatus)
            .put("headroom_0s", power.getThermalHeadroom(0).toDouble().finiteOrNull())
            .put("headroom_10s", power.getThermalHeadroom(10).toDouble().finiteOrNull())
            .put("battery_temp_c", sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0))
            .put("battery_level", sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("plugged", sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("battery_status", sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            .put("current_now_ua", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("charge_counter_uah", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("cpu_khz", JSONArray(cpuFreqFiles.map { runCatching { it.readText().trim().toLong() }.getOrNull() }))
            .put("pss_kb", Debug.getPss())
            .put("native_heap_kb", Debug.getNativeHeapAllocatedSize() / 1024)
    }

    /** Peak and current resident memory of this process from /proc (VmHWM is the kernel's high-water mark). */
    fun memory(): JSONObject {
        val status = File("/proc/self/status").readLines().associate { line ->
            line.substringBefore(':') to line.substringAfter(':').trim()
        }
        fun kb(key: String) = status[key]?.removeSuffix(" kB")?.trim()?.toLongOrNull()
        return JSONObject().put("vm_hwm_kb", kb("VmHWM")).put("vm_rss_kb", kb("VmRSS"))
            .put("pss_kb", Debug.getPss()).put("native_heap_kb", Debug.getNativeHeapAllocatedSize() / 1024)
    }

    private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }
}

/** Samples [Telemetry] on its own thread every [periodMs] until [stop]; keeps the peak PSS. */
class TelemetrySampler(private val telemetry: Telemetry, private val periodMs: Long) {
    val samples = JSONArray()
    @Volatile var peakPssKb = 0L
        private set
    @Volatile private var running = true
    private val thread = Thread({
        while (running) {
            val sample = telemetry.sample()
            synchronized(samples) { samples.put(sample) }
            peakPssKb = maxOf(peakPssKb, sample.getLong("pss_kb"))
            try {
                Thread.sleep(periodMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }, "telemetry").apply { isDaemon = true; start() }

    fun stop(): JSONArray {
        running = false
        thread.interrupt()
        thread.join()
        return samples
    }
}
