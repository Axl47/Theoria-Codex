package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

class OffMainNoteRule(config: Config) : Rule(
    config,
    "OffMainWork.note takes a constant label and runs first in the non-suspend function that does the work.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.notesOffMainWork()) return
        val statement = (expression.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == expression } ?: expression
        if (!expression.passesConstantLabel()) {
            report(Finding(Entity.from(expression.valueArgumentList ?: expression), "Note a constant OffMainWork label, never data."))
        }
        if (!statement.opensNonSuspendFunction()) {
            report(Finding(Entity.from(statement), "Call OffMainWork.note first in the non-suspend function that does the work."))
        }
    }

    private fun KtCallExpression.notesOffMainWork(): Boolean {
        if ((calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != NOTE) return false
        val qualified = parent as? KtQualifiedExpression
        if (qualified != null && qualified.selectorExpression == this) {
            return qualified is KtDotQualifiedExpression && qualified.receiverExpression.endsWithOffMainWork()
        }
        return containingKtFile.importDirectives.any { !it.isAllUnder && it.aliasName == null && it.importedFqName?.asString() == IMPORTED_NOTE }
    }

    private fun KtCallExpression.passesConstantLabel(): Boolean {
        val argument = valueArguments.singleOrNull()?.takeIf { it !is KtLambdaArgument } ?: return false
        val name = argument.getArgumentName()?.asName?.asString()
        if (name != null && name != LABEL) return false
        val label = argument.getArgumentExpression() as? KtDotQualifiedExpression ?: return false
        val constant = (label.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        return CONSTANT.matches(constant) && label.receiverExpression.endsWithOffMainWork() && label.receiverExpression.isNameChain()
    }

    private fun KtExpression.endsWithOffMainWork(): Boolean = when (this) {
        is KtNameReferenceExpression -> getReferencedName() == OFF_MAIN_WORK
        is KtDotQualifiedExpression -> selectorExpression?.endsWithOffMainWork() == true
        else -> false
    }

    private fun KtExpression.isNameChain(): Boolean = this is KtNameReferenceExpression ||
        this is KtDotQualifiedExpression && selectorExpression is KtNameReferenceExpression && receiverExpression.isNameChain()

    private fun KtExpression.opensNonSuspendFunction(): Boolean {
        val block = parent as? KtBlockExpression ?: return false
        if (block.statements.firstOrNull() != this) return false
        val owner = generateSequence(block.parent) { it.parent }.firstOrNull {
            it is KtNamedFunction || it is KtBlockExpression || it is KtWhenExpression || it is KtClassBody || it is KtParameter || it is KtFile
        }
        return owner is KtNamedFunction && owner.name != null && !owner.hasModifier(KtTokens.SUSPEND_KEYWORD)
    }

    private companion object {
        const val NOTE = "note"
        const val LABEL = "label"
        const val OFF_MAIN_WORK = "OffMainWork"
        const val IMPORTED_NOTE = "com.theoriacodex.domain.diagnostics.OffMainWork.note"
        val CONSTANT = Regex("[A-Z][A-Z0-9_]*")
    }
}
