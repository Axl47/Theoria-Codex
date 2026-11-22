package com.theoriacodex.visionbench

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val TAG = "VisionBench"

/**
 * One benchmark run, read from `am start` extras (see tools/phone-bench/bench.py):
 * `matrix` times load, first inference, a host-preprocessed parity pass and a capped end-to-end
 * loop; `soak` runs the end-to-end loop for [seconds] while sampling thermals.
 */
data class RunSpec(
    val run: String,
    val model: String,
    val kind: ModelKind,
    val accelerator: Accelerator,
    val threads: Int,
    val precision: String,
    val strict: Boolean,
    val soak: Boolean,
    val seconds: Int,
    val minImages: Int,
    val parity: Boolean,
    /** GPU: let fully-connected and conv ops read int8 weights directly (allowSrcQuantizedFcConvOps). */
    val gpuQuantOps: Boolean,
    /** NPU: Qualcomm HTP performance mode name (e.g. BURST, SUSTAINED_HIGH_PERFORMANCE), or null for LiteRT's default. */
    val htpMode: String?,
) {
    fun json(): JSONObject = JSONObject().put("run", run).put("model", model).put("kind", kind.name)
        .put("accelerator", accelerator.name).put("threads", threads).put("precision", precision)
        .put("strict", strict).put("mode", if (soak) "soak" else "matrix").put("seconds", seconds)
        .put("min_images", minImages).put("parity", parity).put("gpu_quant_ops", gpuQuantOps).put("htp_mode", htpMode)

    companion object {
        fun from(intent: Intent) = RunSpec(
            run = requireNotNull(intent.getStringExtra("run")) { "missing run" },
            model = requireNotNull(intent.getStringExtra("model")) { "missing model" },
            kind = ModelKind.valueOf(intent.getStringExtra("kind")?.uppercase() ?: "WD"),
            accelerator = Accelerator.valueOf(intent.getStringExtra("acc")?.uppercase() ?: "CPU"),
            threads = intent.getIntExtra("threads", 4),
            precision = intent.getStringExtra("precision") ?: "default",
            strict = intent.getBooleanExtra("strict", false),
            soak = intent.getStringExtra("mode") == "soak",
            seconds = intent.getIntExtra("seconds", 90),
            minImages = intent.getIntExtra("min_images", 64),
            parity = intent.getBooleanExtra("parity", true),
            gpuQuantOps = intent.getBooleanExtra("gpu_quant_ops", false),
            htpMode = intent.getStringExtra("htp"),
        )
    }
}

/** Per-image timings of the end-to-end path, in milliseconds since each step started. */
private class Timings {
    val atMs = JSONArray()
    val decode = JSONArray()
    val prep = JSONArray()
    val infer = JSONArray()
    val native = JSONArray()
    val read = JSONArray()
    val total = JSONArray()
    val inferValues = ArrayList<Double>()
    val totalValues = ArrayList<Double>()

    fun json(): JSONObject = JSONObject().put("at_ms", atMs).put("decode_ms", decode).put("prep_ms", prep)
        .put("infer_ms", infer).put("native_ms", native).put("read_ms", read).put("total_ms", total)
        .put("summary", JSONObject().put("n", totalValues.size)
            .put("infer", stats(inferValues)).put("total", stats(totalValues)))
}

/** Median, p90, mean and max of [values]. */
fun stats(values: List<Double>): JSONObject {
    if (values.isEmpty()) return JSONObject().put("n", 0)
    val sorted = values.sorted()
    fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    return JSONObject().put("n", sorted.size).put("median", pct(0.5)).put("p90", pct(0.9))
        .put("mean", sorted.average()).put("max", sorted.last())
}

private fun msSince(startNs: Long) = (System.nanoTime() - startNs) / 1e6

class Bench(private val context: Context, private val spec: RunSpec) {
    /** App-internal files/: bench.py streams models and inputs in with `run-as` and pulls results the same way. */
    private val root = context.filesDir
    private val results = File(root, "results").apply { mkdirs() }
    private val telemetry = Telemetry(context)
    private val preprocessor = Preprocessor(spec.kind)

    /** Runs the spec and writes results/<run>.json (plus parity .bin files); returns the JSON. */
    fun run(): JSONObject {
        val out = JSONObject().put("spec", spec.json()).put("device", telemetry.device())
            .put("before", telemetry.sample()).put("memory_start", telemetry.memory())
        val sampler = TelemetrySampler(telemetry, if (spec.soak) 5_000 else 2_000)
        val startMs = SystemClock.elapsedRealtime()
        try {
            execute(out)
        } catch (t: Throwable) {
            Log.e(TAG, "run ${spec.run} failed", t)
            out.put("error", Log.getStackTraceString(t).take(4_000))
        } finally {
            out.put("telemetry", sampler.stop()).put("peak_pss_kb", sampler.peakPssKb)
                .put("after", telemetry.sample()).put("memory_end", telemetry.memory())
                .put("wall_s", (SystemClock.elapsedRealtime() - startMs) / 1000.0)
        }
        val tmp = File(results, "${spec.run}.json.tmp")
        tmp.writeText(out.toString())
        check(tmp.renameTo(File(results, "${spec.run}.json"))) { "could not publish results" }
        return out
    }

    private fun execute(out: JSONObject) {
        val environment = if (spec.accelerator == Accelerator.NPU) {
            val provider = BuiltinNpuAcceleratorProvider(context)
            out.put("npu_device_supported", provider.isDeviceSupported()).put("npu_library_ready", provider.isLibraryReady())
            Environment.create(context, provider)
        } else {
            Environment.create(context)
        }
        out.put("available_accelerators", JSONArray(environment.getAvailableAccelerators().map { it.name }))

        val loadStart = System.nanoTime()
        val model = CompiledModel.create(File(root, "models/${spec.model}").path, options(), environment)
        out.put("load_ms", msSince(loadStart)).put("memory_after_load", telemetry.memory())
        Log.i(TAG, "${spec.run} loaded in ${out.getDouble("load_ms")} ms")

        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        val jpegs = loadJpegs()

        val firstStart = System.nanoTime()
        val first = infer(model, inputs, outputs, decode(jpegs.first().second))
        out.put("first_inference_ms", msSince(firstStart))
            .put("output_sizes", JSONArray(first.map { it.size }))
        Log.i(TAG, "${spec.run} first inference ${out.getDouble("first_inference_ms")} ms")

        if (spec.parity && !spec.soak) out.put("parity_exact", parityExact(model, inputs, outputs))
        out.put("e2e", endToEnd(model, inputs, outputs, jpegs))
        out.put("memory_after_runs", telemetry.memory())
        model.close()
        environment.close()
    }

    private fun options(): CompiledModel.Options {
        val options = CompiledModel.Options(spec.accelerator)
        options.cpuOptions = CompiledModel.CpuOptions(spec.threads, null, null)
        if (spec.accelerator == Accelerator.GPU) {
            options.gpuOptions = CompiledModel.GpuOptions(
                precision = when (spec.precision) {
                    "fp16" -> CompiledModel.GpuOptions.Precision.FP16
                    "fp32" -> CompiledModel.GpuOptions.Precision.FP32
                    "fp16acc" -> CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM
                    else -> CompiledModel.GpuOptions.Precision.DEFAULT
                },
                allowSrcQuantizedFcConvOps = spec.gpuQuantOps.takeIf { it },
            )
        }
        spec.htpMode?.let { mode ->
            options.qualcommOptions = CompiledModel.QualcommOptions(
                htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.valueOf(mode),
            )
        }
        return if (spec.strict) options.disableAutoFallbackToCpu() else options
    }

    /** Thumbnails in memory, interleaving synthetic and photo inputs so any prefix covers both kinds. */
    private fun loadJpegs(): List<Pair<String, ByteArray>> {
        val byKind = File(root, "inputs/jpeg").listFiles().orEmpty().sortedBy { it.name }.groupBy { it.name.substringBefore('_') }
        val lists = byKind.values.toList()
        val interleaved = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        check(interleaved.isNotEmpty()) { "no inputs pushed" }
        return interleaved.map { it.nameWithoutExtension to it.readBytes() }
    }

    private fun decode(bytes: ByteArray) = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "decode failed" }

    private fun infer(model: CompiledModel, inputs: List<TensorBuffer>, outputs: List<TensorBuffer>, bitmap: android.graphics.Bitmap): List<FloatArray> {
        inputs[0].writeFloat(preprocessor.fromThumbnail(bitmap))
        model.run(inputs, outputs)
        return outputs.map { it.readFloat() }
    }

    /** Host-preprocessed squares through the model only; outputs go to <run>.exact.bin for host comparison. */
    private fun parityExact(model: CompiledModel, inputs: List<TensorBuffer>, outputs: List<TensorBuffer>): JSONObject {
        val dir = File(root, if (spec.kind == ModelKind.WD) "inputs/wd448" else "inputs/dino224")
        val files = dir.listFiles().orEmpty().sortedBy { it.name }
        val dump = ParityDump(File(results, "${spec.run}.exact.bin"))
        for (file in files) {
            val bitmap = decode(file.readBytes())
            inputs[0].writeFloat(preprocessor.fromPrepared(bitmap))
            bitmap.recycle()
            model.run(inputs, outputs)
            dump.add(file.nameWithoutExtension, outputs.map { it.readFloat() })
        }
        return dump.close()
    }

    /** Decode, preprocess, infer and read outputs per thumbnail; matrix stops at the time cap after [RunSpec.minImages]. */
    private fun endToEnd(model: CompiledModel, inputs: List<TensorBuffer>, outputs: List<TensorBuffer>, jpegs: List<Pair<String, ByteArray>>): JSONObject {
        val timings = Timings()
        val dump = ParityDump(File(results, "${spec.run}.e2e.bin"))
        val parityNames = jpegs.map { it.first }.filter { it.substringAfter('_').toInt() < PARITY_PER_KIND }.toSet()
        val start = SystemClock.elapsedRealtime()
        val deadline = start + spec.seconds * 1000L
        var i = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val done = if (spec.soak) now >= deadline else (i >= spec.minImages && (now >= deadline || i >= jpegs.size))
            if (done) break
            val (name, bytes) = jpegs[i % jpegs.size]
            val t0 = System.nanoTime()
            val bitmap = decode(bytes)
            val t1 = System.nanoTime()
            inputs[0].writeFloat(preprocessor.fromThumbnail(bitmap))
            val t2 = System.nanoTime()
            model.run(inputs, outputs)
            val t3 = System.nanoTime()
            val values = outputs.map { it.readFloat() }
            val t4 = System.nanoTime()
            bitmap.recycle()
            val inferMs = (t3 - t2) / 1e6
            val totalMs = (t4 - t0) / 1e6
            timings.atMs.put(now - start)
            timings.decode.put((t1 - t0) / 1e6)
            timings.prep.put((t2 - t1) / 1e6)
            timings.infer.put(inferMs)
            timings.native.put(model.lastNativeInferenceDurationNanoseconds / 1e6)
            timings.read.put((t4 - t3) / 1e6)
            timings.total.put(totalMs)
            timings.inferValues += inferMs
            timings.totalValues += totalMs
            if (i < jpegs.size && name in parityNames) dump.add(name, values)
            i++
            if (i % 50 == 0) Log.i(TAG, "${spec.run} $i images, last total ${"%.1f".format(totalMs)} ms")
        }
        return timings.json().put("parity", dump.close())
    }

    private companion object {
        const val PARITY_PER_KIND = 32
    }
}

/** Raw little-endian float32 outputs, one record per image: every output tensor in model order. */
private class ParityDump(private val file: File) {
    private val stream = DataOutputStream(file.outputStream().buffered())
    private val names = JSONArray()
    private var sizes: List<Int> = emptyList()

    fun add(name: String, values: List<FloatArray>) {
        sizes = values.map { it.size }
        names.put(name)
        for (array in values) {
            val bytes = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(array)
            stream.write(bytes.array())
        }
    }

    fun close(): JSONObject {
        stream.close()
        return JSONObject().put("file", file.name).put("names", names).put("sizes", JSONArray(sizes))
    }
}
