package com.theoriacodex.visionbench

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * Runs one [RunSpec] from `am start` extras on a background thread, then finishes.
 * The window keeps the screen on and shows over the lock screen for the run only, so no device
 * setting is changed. Logs `DONE <run>` and exits the process when the run ends, so the next run
 * starts cold and bench.py sees the end even if results could not be written.
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val spec = runCatching { RunSpec.from(intent) }.getOrElse {
            Log.e(TAG, "DONE invalid spec: ${it.message}")
            finish()
            return
        }
        setContentView(TextView(this).apply { text = spec.run })
        if (savedInstanceState != null) return // a recreated window must not start a second run
        Thread({
            val result = runCatching { Bench(applicationContext, spec).run() }
            val status = result.fold({ if (it.has("error")) "error" else "ok" }, { "crash: ${it.message}" })
            Log.i(TAG, "DONE ${spec.run} $status")
            runOnUiThread {
                finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            }
        }, "bench").start()
    }
}
