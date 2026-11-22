package com.theoriacodex.app.lift

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Immutable
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.saveMenuTargets
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.navigation.LiftLike

/** The targets one lift shows: quick actions on the start edge, save targets on the end edge. */
@Immutable
data class LiftTargets(val quick: List<CollectTarget<LiftAction>>, val save: List<CollectTarget<LiftAction>>)

/**
 * A lifted post's targets: the shared save menu ([saveMenuTargets]) over its suggested Codices (ones it is not in
 * yet) on one edge, and on the other Like or Unlike when the card can like and Similar when [similarAvailable].
 */
fun liftTargets(suggestions: SaveSuggestions, like: LiftLike?, similarAvailable: Boolean): LiftTargets {
    val save = saveMenuTargets(suggestions.targets) { target ->
        when (target) {
            is SaveTarget.Codex -> LiftAction.Save(target.suggestion.codexId, target.suggestion.name)
            SaveTarget.New -> LiftAction.NewCodex
            SaveTarget.More -> LiftAction.OpenPicker
        }
    }
    val likeTarget = like?.let {
        val (label, icon) = if (it.liked) "Unlike" to Icons.Default.Favorite else "Like" to Icons.Default.FavoriteBorder
        CollectTarget<LiftAction>("like", label, CollectTargetVisual.Icon(icon), LiftAction.ToggleLike)
    }
    val similar = CollectTarget<LiftAction>("similar", "Similar", CollectTargetVisual.Icon(Icons.Default.AutoAwesome), LiftAction.Similar)
    return LiftTargets(
        quick = listOfNotNull(likeTarget, similar.takeIf { similarAvailable }),
        save = save,
    )
}
