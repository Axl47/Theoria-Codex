package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/** What a picture model read from one picture: its embedding and one score per vocabulary tag. */
class PictureAnalysis(val embedding: FloatArray, val scores: FloatArray)

/**
 * A loaded picture model. Not thread-safe: [PictureModelHolder] gives it one picture at a time. Today's only
 * implementation runs on the CPU ([WdTaggerRunner]); an accelerator would be another implementation, checked against
 * the CPU's outputs before it is trusted, since phase 0 found the phone's GPU wrong at fp16 precision.
 */
interface PictureModel : AutoCloseable {
    fun analyze(picture: Bitmap): PictureAnalysis
}

/**
 * WD ViT tagger v3, int8 weights, on LiteRT's CPU path only (no automatic fallback between accelerators to second-guess).
 * The model returns a 768-d L2-normalised embedding and [vocabularySize] sigmoid scores; they are told apart by length.
 */
class WdTaggerRunner(modelFile: File, private val vocabularySize: Int, threads: Int = DEFAULT_THREADS) : PictureModel {
    private val environment = Environment.create()
    private val model: CompiledModel
    private val inputs: List<TensorBuffer>
    private val outputs: List<TensorBuffer>
    private val preprocessor = PicturePreprocessor()

    init {
        val options = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(threads, null, null) }
        model = try {
            CompiledModel.create(modelFile.path, options, environment)
        } catch (error: Exception) {
            environment.close()
            throw error
        }
        inputs = model.createInputBuffers()
        outputs = model.createOutputBuffers()
    }

    override fun analyze(picture: Bitmap): PictureAnalysis {
        inputs.single().writeFloat(preprocessor.input(picture))
        model.run(inputs, outputs)
        val read = outputs.map(TensorBuffer::readFloat)
        val scores = read.single { it.size == vocabularySize }
        val embedding = read.single { it !== scores }
        return PictureAnalysis(embedding, scores)
    }

    override fun close() {
        (inputs + outputs).forEach(TensorBuffer::close)
        model.close()
        environment.close()
    }

    companion object {
        /** Phase 0 on the Galaxy S24+: 389 ms cold, 449 ms sustained per picture with 4 threads; 2 threads take 525 ms. */
        const val DEFAULT_THREADS = 4
    }
}
