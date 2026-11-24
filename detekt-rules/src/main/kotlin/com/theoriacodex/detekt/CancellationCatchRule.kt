package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression

class CancellationCatchRule(config: Config) : Rule(
    config,
    "A catch that only rethrows CancellationException before a catch-all should use a runCatching helper.",
) {
    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        if (expression.finallyBlock != null) return
        val (cancellation, catchAll) = expression.catchClauses.takeIf { it.size == 2 } ?: return
        val file = expression.containingKtFile
        if (!cancellation.rethrowsCancellation(file)) return
        val helper = when (catchAll.resolvedType(file)) {
            "kotlin.Throwable", "java.lang.Throwable" -> "runCatchingPreservingCancellation"
            "kotlin.Exception", "java.lang.Exception" -> "runCatchingExceptionsPreservingCancellation"
            else -> return
        }
        report(Finding(Entity.from(expression.tryKeyword ?: expression), "Use $helper instead of rethrowing CancellationException by hand."))
    }

    private fun KtCatchClause.rethrowsCancellation(file: KtFile): Boolean {
        if (resolvedType(file) !in CANCELLATION_EXCEPTIONS) return false
        val thrown = (catchBody as? KtBlockExpression)?.statements?.singleOrNull() as? KtThrowExpression
        var thrownExpression = thrown?.thrownExpression
        while (thrownExpression is KtParenthesizedExpression) thrownExpression = thrownExpression.expression
        val name = (thrownExpression as? KtNameReferenceExpression)?.getReferencedName()
        return name != null && name == catchParameter?.name
    }

    private fun KtCatchClause.resolvedType(file: KtFile): String? {
        val written = catchParameter?.typeReference?.text ?: return null
        if ('.' in written) return written
        val import = file.importDirectives.firstOrNull { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == written }
        if (import != null) return import.importedFqName?.asString()
        val shadowed = file.importDirectives.any { it.isAllUnder && it.importedFqName?.asString() !in STANDARD_PACKAGES }
        return if (shadowed) null else "kotlin.$written"
    }

    private companion object {
        val STANDARD_PACKAGES = setOf(
            "kotlin",
            "kotlinx.coroutines",
            "java.lang",
            "java.util.concurrent",
            "kotlin.coroutines.cancellation",
        )
        val CANCELLATION_EXCEPTIONS = setOf(
            "kotlin.CancellationException",
            "kotlinx.coroutines.CancellationException",
            "java.util.concurrent.CancellationException",
            "kotlin.coroutines.cancellation.CancellationException",
        )
    }
}
