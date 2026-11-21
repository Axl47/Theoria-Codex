package com.theoriacodex.app.lift

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Immutable
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.navigation.LiftLike

/** The targets one lift shows: quick actions on the start edge, save targets on the end edge. */
@Immutable
data class LiftTargets(val quick: List<CollectTarget<LiftAction>>, val save: List<CollectTarget<LiftAction>>)

/** How many suggested Codices the end edge shows above New. */
const val LIFT_SAVE_SUGGESTIONS = 3

/**
 * A lifted post's targets: its top suggested Codices (ones it is not in yet) plus New, and on the other edge
 * Like or Unlike when the card can like and Similar when [similarAvailable].
 */
fun liftTargets(suggestions: SaveSuggestions, like: LiftLike?, similarAvailable: Boolean): LiftTargets {
    val codices = suggestions.targets.take(LIFT_SAVE_SUGGESTIONS).map { suggestion ->
        CollectTarget<LiftAction>(
            key = "codex:${suggestion.codexId}",
            label = suggestion.name,
            visual = CollectTargetVisual.Cover(suggestion.cover, Icons.Default.Collections),
            payload = LiftAction.Save(suggestion.codexId, suggestion.name),
        )
    }
    val newCodex = CollectTarget<LiftAction>("new", "New", CollectTargetVisual.Icon(Icons.Default.Add), LiftAction.NewCodex)
    val likeTarget = like?.let {
        val (label, icon) = if (it.liked) "Unlike" to Icons.Default.Favorite else "Like" to Icons.Default.FavoriteBorder
        CollectTarget<LiftAction>("like", label, CollectTargetVisual.Icon(icon), LiftAction.ToggleLike)
    }
    val similar = CollectTarget<LiftAction>("similar", "Similar", CollectTargetVisual.Icon(Icons.Default.AutoAwesome), LiftAction.Similar)
    return LiftTargets(
        quick = listOfNotNull(likeTarget, similar.takeIf { similarAvailable }),
        save = codices + newCodex,
    )
}
