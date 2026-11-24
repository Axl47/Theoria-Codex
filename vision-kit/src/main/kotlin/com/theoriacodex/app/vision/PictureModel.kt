package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

class PictureAnalysis(val embedding: FloatArray, val scores: FloatArray)

interface PictureModel : AutoCloseable {
    fun analyze(picture: Bitmap): PictureAnalysis
}

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
        inputs = try {
            model.createInputBuffers()
        } catch (error: Exception) {
            model.close()
            environment.close()
            throw error
        }
        try {
            outputs = model.createOutputBuffers()
        } catch (error: Exception) {
            inputs.forEach(TensorBuffer::close)
            model.close()
            environment.close()
            throw error
        }
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
        const val DEFAULT_THREADS = 4
    }
}
