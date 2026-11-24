package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

class StoredJsonUsesDurableJsonRule(config: Config) : Rule(
    config,
    "Stored and exported JSON goes through DurableJson; kotlinx's Json is used only as a type.",
) {
    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val reference = expression as? KtNameReferenceExpression ?: return
        if (reference.getParentOfType<KtUserType>(true) != null) return
        if (reference.getParentOfType<KtImportDirective>(true) != null) return
        if (reference.getParentOfType<KtPackageDirective>(true) != null) return
        if (!reference.namesKotlinxJson()) return
        report(Finding(Entity.from(reference), "Use DurableJson instead of building or calling kotlinx's Json here."))
    }

    private fun KtNameReferenceExpression.namesKotlinxJson(): Boolean {
        val qualified = parent as? KtDotQualifiedExpression
        if (qualified != null && qualified.selectorExpression == this) return qualified.receiverExpression.text == KOTLINX_JSON_PACKAGE
        val name = getReferencedName()
        val file = containingKtFile
        return file.importedAs(name) || name == JSON && file.importsKotlinxJsonPackage()
    }

    private fun KtFile.importedAs(name: String): Boolean = importDirectives.any { import ->
        import.importedFqName?.asString() == KOTLINX_JSON && (import.aliasName ?: JSON) == name
    }

    private fun KtFile.importsKotlinxJsonPackage(): Boolean = importDirectives.any { import ->
        import.isAllUnder && import.importedFqName?.asString() == KOTLINX_JSON_PACKAGE
    }

    private companion object {
        const val JSON = "Json"
        const val KOTLINX_JSON_PACKAGE = "kotlinx.serialization.json"
        const val KOTLINX_JSON = "$KOTLINX_JSON_PACKAGE.$JSON"
    }
}
