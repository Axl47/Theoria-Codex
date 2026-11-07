package com.theoriacodex.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import java.util.regex.PatternSyntaxException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android compiles regexes with ICU, which rejects some patterns the JVM accepts (an unescaped `}` or `]`),
 * so host tests cannot catch them. Initializing every app class compiles each regex held at file, object or
 * companion level on the device; only a regex syntax error fails, other initialization failures are ignored.
 */
@RunWith(AndroidJUnit4::class)
class RegexCompatibilityDeviceTest {
    @Test
    fun everyStaticRegexCompilesOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION") // DexFile is the only way to list the installed APK's classes.
        val classNames = DexFile(context.packageCodePath).entries().toList().filter { it.startsWith("com.theoriacodex.") }
        assertTrue("no app classes found", classNames.isNotEmpty())
        val failures = classNames.mapNotNull { name ->
            val error = runCatching { Class.forName(name, true, context.classLoader) }.exceptionOrNull()
            generateSequence(error) { it.cause }.filterIsInstance<PatternSyntaxException>().firstOrNull()
                ?.let { "$name: ${it.description} in ${it.pattern}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
