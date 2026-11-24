package com.theoriacodex.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class TheoriaRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("theoria")

    override fun instance(): RuleSet = RuleSet(ruleSetId, listOf(::CancellationCatchRule, ::StoredJsonUsesDurableJsonRule))
}
