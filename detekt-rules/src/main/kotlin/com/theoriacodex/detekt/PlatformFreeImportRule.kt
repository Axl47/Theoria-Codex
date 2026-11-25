package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

class PlatformFreeImportRule(config: Config) : Rule(
    config,
    "Platform-free code imports no Android, Compose, lifecycle, Room, Media3, Coil or source adapter types.",
) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        val forbidden = FORBIDDEN_PACKAGES.firstOrNull { imported == it || imported.startsWith("$it.") } ?: return
        report(Finding(Entity.from(importDirective), "Keep $forbidden out of platform-free code."))
    }

    private companion object {
        val FORBIDDEN_PACKAGES = listOf(
            "android",
            "androidx.compose",
            "androidx.lifecycle",
            "androidx.room",
            "androidx.media3",
            "coil",
            "com.theoriacodex.sources",
        )
    }
}
