package com.theoriacodex.app.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.search.retriesOnReconnect
import com.theoriacodex.app.trails.TrailSpec
import com.theoriacodex.app.trails.asTriageFeed
import com.theoriacodex.app.trails.nextOrNull
import com.theoriacodex.app.triage.TriageDecision
import com.theoriacodex.app.triage.TriageDecisionEvent
import com.theoriacodex.app.triage.TriageDeck
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.triage.carriesAnyTag
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns For You: the Mix, dealt in small batches from the profile's lanes, and the trails forked from its cards, as a
 * stack of [levels] with one deck each. The screen shows the top level as a deck or a grid over the same posts, and
 * the Viewer follows that level as stream [ViewerStreamSource.FOR_YOU].
 *
 * Swipes on the Mix steer what it deals next ([DeckSteering]): a run of skips lowers a lane, and a like or save asks
 * for two posts like it, which arrive a few cards later. Steering lives in memory, so a recreated owner starts from a
 * fresh Mix. Trails do not steer, but a post decided on any level, or a copy of it on another site, is never dealt
 * again this session. Every level's like, save, skip and hide is also logged per profile ([ForYouDeckWiring.verdicts])
 * as a label for a future taste model; Undo removes it. Everything here runs on the main thread, which is also where
 * the decks report their decisions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForYouDeckViewModel(
    private val engine: ForYouRouteEngine,
    private val wiring: ForYouDeckWiring,
) : ViewModel(), ViewerFeedOwner {
    private val ui = MutableStateFlow(ForYouDeckUiState())
    val state: StateFlow<ForYouDeckUiState> = ui.asStateFlow()

    private val stack = MutableStateFlow(emptyList<ForYouLevel>())

    /** The Mix first, then each trail forked since; never empty. */
    val levels: StateFlow<List<ForYouLevel>> = stack.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Things to tell the user in passing, such as a fork that could not open. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var settings: AppSettings? = null
    private var inputs: MixInputs? = null
    private var hidden: List<ForYouBlacklistEntry> = emptyList()

    /** The hides the Mix's lanes were planned under: when one of them goes, lanes it kept out of the plan may return. */
    private var plannedUnder: List<ForYouBlacklistEntry> = emptyList()

    /** The profile's single hidden tags by source, as [normalizeMatchToken] spells them; no level shows a post carrying one. */
    private val hiddenTokens = MutableStateFlow(emptyMap<SourceKey, Set<String>>())
    private var kept: Set<PostId> = emptySet()
    private var creatorSources: Set<SourceKey> = emptySet()
    private var grid = false

    /** The Mix once its lanes are known, and the feed its level reads; both are replaced by a fresh Mix. */
    private var mix: ForYouMix? = null
    private var mixFeed = MutableStateFlow(LOADING_FEED)

    /** Names this owner's levels apart from an earlier owner's, whose Viewer may still be on the back stack. */
    private val session = UUID.randomUUID().toString().take(8)

    /** Counts Mix levels, so a deck that a chip or a fresh Mix replaced no longer deals or steers. */
    private var sequence = 0
    private var trailCount = 0

    /** Every request of the current Mix: its lanes, their pages, the posts likes ask for, and forks. */
    private val work = SupervisorJob(viewModelScope.coroutineContext.job)
    private val workScope = CoroutineScope(viewModelScope.coroutineContext + work)

    /** Posts decided on any level this session. */
    private val seen = mutableSetOf<PostId>()

    /** The profile each logged verdict was recorded under, so Undo removes it there; writes run in order. */
    private val loggedUnder = mutableMapOf<PostId, String>()
    private val verdictWrites = Mutex()

    /** The Mix's latest decision and the steering before it, for Undo. */
    private var lastDecision: Pair<PostId, DeckSteering>? = null
    private val asking = mutableMapOf<PostId, Job>()

    /** A fresh Mix is asking its lanes for their first pages, and deals once it has asked them all. */
    private var opening = false
    private var forking: Job? = null

    private val relatedState = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
    private val relatedPosts = RelatedPostsRouteController(
        scope = viewModelScope,
        loader = wiring.relatedLoader,
        currentState = { relatedState.value },
        canonicalPosts = { top.feed.value.posts },
        updateState = { relatedState.value = it },
    )

    private val top: ForYouLevel get() = stack.value.last()

    /** Cards per deal: few in the deck, so what a like asks for lands a few cards later; a screenful in the grid. */
    private val dealSize: Int get() = if (grid) GRID_DEAL else DECK_DEAL

    override val streamSource = ViewerStreamSource.FOR_YOU
    override val viewerFeed: ViewerFeedSnapshot get() = top.viewerFeed(top.feed.value, relatedState.value)
    override val viewerFeedChanges: Flow<ViewerFeedSnapshot> = stack.flatMapLatest { levels ->
        val level = levels.last()
        level.feed.combine(relatedState) { feed, related -> level.viewerFeed(feed, related) }
    }.distinctUntilChanged()
    override val related: RelatedPostsRouteController get() = relatedPosts

    init {
        stack.value = listOf(mixLevel())
        viewModelScope.launch { wiring.reconnections.collect { retryAfterReconnect() } }
    }

    /**
     * Takes the route's current [environment]. A new profile, source, sort, media filter or set of runnable sources
     * starts a fresh Mix; a change to the hidden tags only leaves out what they now hide and brings back what they no
     * longer do.
     */
    fun synchronize(environment: ForYouEnvironment) {
        val settings = environment.settings
        val available = engine.availableSources(settings)
        val next = MixInputs(
            settings.activeProfileId, available, environment.selectedSource?.takeIf { it in available },
            environment.sortMode, environment.animatedOnly,
        )
        val blacklist = settings.forYouBlacklistByProfile[settings.activeProfileId].orEmpty()
        this.settings = settings
        kept = environment.kept
        grid = environment.grid
        creatorSources = environment.creatorBrowsingSources
        ui.update {
            it.copy(
                profiles = settings.recommendationProfiles, activeProfileId = settings.activeProfileId,
                availableSources = available, selectedSource = next.source,
            )
        }
        val previous = inputs
        inputs = next
        val hiddenChanged = blacklist != hidden
        val unhidden = hidden - blacklist.toSet()
        if (hiddenChanged) {
            hidden = blacklist
            hiddenTokens.value = blacklist.filter { it.tags.size == 1 }
                .groupBy({ it.source }) { normalizeMatchToken(it.tags.single()) }.mapValues { it.value.toSet() }
        }
        when {
            previous != next -> {
                if (previous?.profileId != next.profileId) seen.clear()
                rebuild()
            }
            hiddenChanged -> {
                dropHidden()
                if (unhidden.any { it in plannedUnder }) restoreLanes(unhidden)
            }
        }
    }

    /** Starts a fresh Mix for the current inputs: every level closes, and steering starts over. */
    fun rebuild() {
        val request = request() ?: return
        work.cancelChildren()
        asking.clear()
        mix = null
        lastDecision = null
        plannedUnder = hidden
        resetLevels()
        ui.update { it.copy(mix = ForYouMixState.Dealing, failure = null, chips = emptyList(), only = null, failures = emptyList()) }
        if (request.availableSources.isEmpty()) return settle(ForYouMixState.NoSources)
        workScope.launch {
            val lanes = runCatchingPreservingCancellation { engine.lanes(request) }.getOrElse { error ->
                Diagnostics.report("for-you", "For You could not build its lanes", error)
                return@launch settle(ForYouMixState.Failed, error.message)
            }
            if (lanes.isEmpty()) return@launch settle(ForYouMixState.NoLanes)
            val built = ForYouMix(lanes, wiring.copiesOf)
            mix = built
            // Every lane is asked before the first deal, so lanes that answer without waiting are dealt in turn; after
            // that the first lane to answer deals, and a slow source does not hold the first card back.
            opening = true
            refill(built)
            opening = false
            if (built.wanted) deal()
        }
    }

    /** What the lanes are planned for now; null before the route has synchronized. */
    private fun request(): ForYouFeedRequest? {
        val inputs = inputs ?: return null
        val settings = settings ?: return null
        return ForYouFeedRequest(settings, inputs.sources, inputs.source, inputs.sort, inputs.animatedOnly)
    }

    /** Narrows the Mix to the lane [laneId], or widens it again for null. The dealt cards nobody decided are dealt again. */
    fun selectChip(laneId: String?) {
        val mix = mix ?: return
        if (mix.only == laneId || stack.value.size > 1) return
        mix.only = laneId
        restartMixLevel(mix)
    }

    /** Asks again for what [source]'s lanes failed to load, or every source's for null. */
    fun retrySource(source: SourceKey? = null) {
        val mix = mix ?: return
        mix.failedLanes(source).forEach { lane ->
            lane.clearFailure()
            load(mix, lane)
        }
        publish()
    }

    /**
     * Opens the trail [request] asks for as a new level on top. A second tap while it resolves does nothing, and a
     * trail that resolves after its level was left, or covered, is dropped.
     */
    fun fork(request: TrailRequest) {
        if (forking?.isActive == true) return
        val from = top
        val animatedOnly = inputs?.animatedOnly == true
        forking = workScope.launch {
            val spec = runCatchingPreservingCancellation { wiring.trails(request, animatedOnly) }.getOrNull()
            when {
                top !== from -> Unit
                spec == null -> messageChannel.trySend("Couldn't open ${request.name()}")
                else -> push(request, spec)
            }
        }
    }

    /** Leaves every level above [depth], where 0 is the Mix. */
    fun popTo(depth: Int) {
        val levels = stack.value
        if (depth !in 0 until levels.lastIndex) return
        levels.drop(depth + 1).forEach(ForYouLevel::close)
        relatedPosts.clear("level left")
        stack.value = levels.take(depth + 1)
    }

    /** What brought [post] to the Mix; null for a trail's posts. */
    fun originOf(post: PostId): DeckOrigin? = mix?.origins?.get(post)

    /** The tags of the lane that dealt [post], which its card does not offer as forks again. */
    fun laneTags(post: PostId): List<String> {
        val origin = originOf(post) as? DeckOrigin.Lane ?: return emptyList()
        return mix?.lanes?.firstOrNull { it.origin == origin }?.lane?.tags.orEmpty()
    }

    suspend fun resolvePost(postId: PostId): Post? = engine.resolvePost(postId)?.also(::rememberResolvedPost)

    override fun loadNextPage() = top.loadNextPage()

    override fun retryPage() = top.retryPage()

    override fun rememberResolvedPost(post: Post) {
        top.remember(post)
    }

    override fun onCleared() {
        stack.value.forEach(ForYouLevel::close)
    }

    /** A network that returned retries what a lost one failed: the lanes, or a trail's page. */
    private fun retryAfterReconnect() {
        if (ui.value.mix == ForYouMixState.Failed) return rebuild()
        mix?.let { mix ->
            mix.lanes.filter { it.status?.retriesOnReconnect() == true }.forEach { lane ->
                lane.clearFailure()
                load(mix, lane)
            }
            publish()
        }
        if (stack.value.size > 1 && top.feed.value.pageFailed) top.retryPage()
    }

    /** Ends the Mix without lanes: nothing to deal, or, when [state] is a failure, something to retry. */
    private fun settle(state: ForYouMixState, failure: String? = null) {
        ui.update { it.copy(mix = state, failure = failure) }
        mixFeed.value = TriageFeedSnapshot(emptyList(), canLoadMore = false, pageFailed = state == ForYouMixState.Failed)
    }

    /**
     * Asks [lane] for its next page. The request starts in place, so a lane that answers without waiting has answered
     * by the time this returns.
     */
    private fun load(mix: ForYouMix, lane: MixLane) {
        val cursor = lane.cursor ?: return
        lane.loading = true
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val page = runCatchingPreservingCancellation { engine.loadPage(cursor, setOf(lane.lane.source)) }
            lane.loading = false
            page.onSuccess { lane.accept(cursor, it, it.items.filter(::shows)) }.onFailure { error ->
                Diagnostics.report("for-you", "A For You lane failed to load", error)
                lane.fail(error.message)
            }
            when {
                opening -> Unit
                mix.wanted -> deal()
                else -> refill(mix)
            }
        }
    }

    /**
     * Deals the next batch, then asks the lanes that ran low for more. While lanes are still opening a batch is small
     * even in the grid, so the lanes that answer next get their turn soon.
     */
    private fun deal() {
        val mix = mix ?: return
        mix.deal(if (mix.opening) DECK_DEAL else dealSize, seen + kept)
        refill(mix)
    }

    /** Asks every lane that runs low for its next page. One at a time, since a lane that answers at once refills too. */
    private fun refill(mix: ForYouMix) {
        while (true) load(mix, mix.lanesToRefill().firstOrNull() ?: break)
        publish()
    }

    private fun publish() {
        val mix = mix ?: return
        mixFeed.value = mix.snapshot()
        ui.update { state ->
            state.copy(chips = mix.lanes.map { ForYouChip(it.origin.laneId, it.origin.label) }, only = mix.only, failures = mix.failures())
        }
    }

    /** Whether the deck may show [post]: it passes the media filter and carries none of the profile's hidden tags. */
    private fun shows(post: Post): Boolean =
        (inputs?.animatedOnly != true || isAnimatedPost(post)) && !post.carriesAnyTag(hiddenTokens.value[post.id.source].orEmpty())

    /**
     * Leaves out the lanes and drops the waiting posts the hidden tags now hide, and brings back the lanes they no
     * longer hide. A chip whose lane went widens to the Mix in place: the levels, the deck and its Undo stay, and the
     * decks pass over the cards the tag hides.
     */
    private fun dropHidden() {
        val mix = mix ?: return
        mix.hideLanes { hidden.hidesSeed(it.source, it.tags) }
        mix.keepWaiting(::shows)
        if (mix.wanted) deal() else refill(mix)
    }

    /**
     * Plans the lanes again after the hides [removed] went, and adds the lanes they had kept out of this Mix; the
     * levels stay. A hide that came back while the plan ran keeps its lanes out, and is planned again the next time
     * it goes. Without a Mix to add to, it starts a fresh one.
     */
    private fun restoreLanes(removed: List<ForYouBlacklistEntry>) {
        val mix = mix ?: return rebuild()
        val request = request() ?: return
        workScope.launch {
            val plan = runCatchingPreservingCancellation { engine.lanes(request) }.getOrNull() ?: return@launch
            val gone = removed.filterNot { it in hidden }
            plannedUnder = plannedUnder - gone.toSet()
            if (mix.addLanes(plan.filter { gone.hidesSeed(it.source, it.tags) && !hidden.hidesSeed(it.source, it.tags) })) refill(mix)
        }
    }

    /** Replaces the Mix's deck after its chip changed. The new deck opens on its first batch, so it does not ask for one. */
    private fun restartMixLevel(mix: ForYouMix) {
        mix.takeBack(seen)
        lastDecision = null
        mix.deal(dealSize, seen + kept)
        resetLevels()
        refill(mix)
    }

    /** Closes every level and opens an empty Mix level. */
    private fun resetLevels() {
        stack.value.forEach(ForYouLevel::close)
        relatedPosts.clear("new mix level")
        mixFeed = MutableStateFlow(mix?.snapshot() ?: LOADING_FEED)
        stack.value = listOf(mixLevel())
    }

    private fun mixLevel(): ForYouLevel {
        val level = ++sequence
        val feed = mixFeed
        val source = object : TriageFeed {
            override val snapshots = feed
            override fun loadNextPage() {
                if (level == sequence) deal()
            }
            override fun retryPage() {
                if (level != sequence) return
                if (ui.value.mix == ForYouMixState.Failed) rebuild() else retrySource()
            }
        }
        return level("for_you:$session:mix:$level", "Mix", fork = null, query = null, source, DECK_PAGE_AHEAD) { event -> onMixDecision(level, event) }
    }

    /** Opens [spec] as a level. It leaves out what was decided, liked or saved before it opened, and what is hidden. */
    private fun push(request: TrailRequest, spec: TrailSpec) {
        val scope = levelScope()
        // Not read live: a card decided on this trail must stay in its deck for Undo.
        val known = seen + kept
        val source = spec.pager.asTriageFeed(scope) { posts -> posts.filter { !it.copyOfAny(known) && shows(it) } }
        relatedPosts.clear("level opened")
        stack.update { it + level("for_you:$session:trail:${++trailCount}", spec.label, request, spec.query, source, scope = scope, onDecision = ::onTrailDecision) }
    }

    /**
     * A level over [source]: its feed, shared by the deck, the grid and the Viewer, without the posts a hidden tag now
     * hides and with the fuller copies of posts the Viewer resolved, and its deck, which passes over the same posts
     * ([hiddenTokens] is the one list for all three).
     */
    private fun level(
        id: String,
        label: String,
        fork: TrailRequest?,
        query: Query?,
        source: TriageFeed,
        pageAhead: Int = TRAIL_PAGE_AHEAD,
        scope: CoroutineScope = levelScope(),
        onDecision: (TriageDecisionEvent) -> Unit,
    ): ForYouLevel {
        val resolved = MutableStateFlow(emptyMap<PostId, Post>())
        // The fuller copy first: a source whose listings carry no tags shows a hidden one only once resolved.
        val fuller = combine(source.snapshots, resolved) { snapshot, fuller ->
            if (fuller.isEmpty()) snapshot else snapshot.copy(posts = snapshot.posts.map { fuller[it.id] ?: it })
        }
        val feed = combine(fuller, hiddenTokens) { snapshot, hidden ->
            snapshot.copy(posts = snapshot.posts.filterNot { it.carriesAnyTag(hidden[it.id.source].orEmpty()) })
        }.stateIn(scope, SharingStarted.Eagerly, LOADING_FEED)
        val shared = paging(feed, source)
        // The deck passes over hidden posts itself, so it takes the fuller copies unfiltered and keeps its place.
        val deck = TriageDeck(
            scope, paging(fuller, source), wiring.actions, wiring.quickSave, query, fromForYou = true, wiring.writeScope,
            pageAhead = pageAhead, onDecision = onDecision, ownerHidden = hiddenTokens,
        )
        return ForYouLevel(id, label, fork, query, deck, feed, shared, scope) { post -> resolved.update { it + (post.id to post) } }
    }

    /** [snapshots] as a feed that pages through [source]. */
    private fun paging(snapshots: Flow<TriageFeedSnapshot>, source: TriageFeed) = object : TriageFeed {
        override val snapshots = snapshots
        override fun loadNextPage() = source.loadNextPage()
        override fun retryPage() = source.retryPage()
    }

    private fun levelScope(): CoroutineScope {
        val context = viewModelScope.coroutineContext
        return CoroutineScope(context + SupervisorJob(context.job))
    }

    /**
     * A trail's decisions only mark their posts decided and log their verdicts; a decision that no longer stands
     * unmarks its post and removes its verdict.
     */
    private fun onTrailDecision(event: TriageDecisionEvent) {
        val post = event.decision.post
        when (event) {
            is TriageDecisionEvent.Decided -> {
                seen += post.id
                val kind = event.decision.signal()?.verdictKind() ?: return
                val profileId = inputs?.profileId ?: return
                loggedUnder[post.id] = profileId
                val verdict = ForYouVerdict(profileId, post.id, kind, wiring.clock(), postPreviewImageCandidate(post)?.url)
                writeVerdict { record(verdict) }
            }
            is TriageDecisionEvent.Reverted -> {
                seen -= post.id
                val profileId = loggedUnder.remove(post.id) ?: return
                writeVerdict { remove(profileId, post.id) }
            }
        }
    }

    /** Runs [write] after the verdict writes asked for before it, so an Undo never lands ahead of its swipe. */
    private fun writeVerdict(write: suspend ForYouVerdictRepository.() -> Unit) {
        wiring.writeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            verdictWrites.withLock {
                runCatchingPreservingCancellation { wiring.verdicts.write() }
                    .onFailure { Diagnostics.report("for-you", "A For You verdict could not be logged", it) }
            }
        }
    }

    /** Whether [this] post, or a copy of it on another site, is one of [ids]. */
    private fun Post.copyOfAny(ids: Set<PostId>): Boolean = id in ids || wiring.copiesOf(this).any(ids::contains)

    /**
     * Steers the Mix by a decision of the Mix deck numbered [level]. A deck that was replaced since (its writes may
     * still report) only marks and unmarks its posts.
     */
    private fun onMixDecision(level: Int, event: TriageDecisionEvent) {
        onTrailDecision(event)
        val mix = mix?.takeIf { level == sequence } ?: return
        val post = event.decision.post
        when (event) {
            is TriageDecisionEvent.Decided -> {
                val signal = event.decision.signal() ?: return
                val origin = mix.origins[post.id] ?: return
                lastDecision = post.id to mix.steering
                mix.steering = mix.steering.after(signal, origin)
                if (signal == DeckSignal.Liked || signal == DeckSignal.Saved) askForMore(mix, post)
            }
            is TriageDecisionEvent.Reverted -> {
                asking.remove(post.id)?.cancel()
                // Undo reverses the latest decision exactly; a write that failed later only loses the cards it asked for.
                val before = lastDecision?.takeIf { it.first == post.id }?.second
                if (before != null) lastDecision = null
                mix.steering = mix.steering.undone(before ?: mix.steering, post.id)
                publish()
            }
        }
    }

    /** Queues up to [INJECTED] posts like the liked or saved [post] ahead of the lanes' next cards. */
    private fun askForMore(mix: ForYouMix, post: Post) {
        val animatedOnly = inputs?.animatedOnly == true
        val creator = creatorProfileActions(post, creatorSources).firstNotNullOfOrNull { it.profile }
        asking[post.id] = workScope.launch {
            val similar = wiring.trails(TrailRequest.Similar(post), animatedOnly)
            val (spec, origin) = when {
                similar != null -> similar to DeckOrigin.Similar(post.id)
                creator != null -> wiring.trails(TrailRequest.Creator(creator, post), animatedOnly) to DeckOrigin.Creator(post.id, creator.displayName)
                else -> null to null
            }
            val page = withContext(wiring.fetchContext) { spec?.pager?.nextOrNull() }
            val found = if (origin == null) emptyList() else page?.posts.orEmpty().map { DeckCard(it, origin) }
            asking.remove(post.id)
            val fresh = found.filter { card ->
                !card.post.copyOfAny(seen + kept + post.id) && !mix.isDealt(card.post.id) && shows(card.post)
            }.distinctBy { it.post.id }.take(INJECTED)
            if (fresh.isEmpty()) return@launch
            mix.steering = mix.steering.inject(fresh)
            if (mix.wanted) deal() else publish()
        }
    }

    private companion object {
        const val DECK_DEAL = 4
        const val GRID_DEAL = 12

        /** The Mix deck asks for the next deal with this many cards left. */
        const val DECK_PAGE_AHEAD = 2
        const val TRAIL_PAGE_AHEAD = 5
        const val INJECTED = 2
    }
}

private fun TriageDecision.signal(): DeckSignal? = when (this) {
    is TriageDecision.Liked -> DeckSignal.Liked
    is TriageDecision.Saved -> DeckSignal.Saved
    is TriageDecision.Skipped -> DeckSignal.Skipped
    is TriageDecision.Hid -> DeckSignal.Hid
    is TriageDecision.Kept, is TriageDecision.Removed -> null
}

private fun DeckSignal.verdictKind(): ForYouVerdictKind = when (this) {
    DeckSignal.Liked -> ForYouVerdictKind.LIKED
    DeckSignal.Saved -> ForYouVerdictKind.SAVED
    DeckSignal.Skipped -> ForYouVerdictKind.SKIPPED
    DeckSignal.Hid -> ForYouVerdictKind.HID
}

private fun TrailRequest.name(): String = when (this) {
    is TrailRequest.Tag -> tag.replace('_', ' ')
    is TrailRequest.Creator -> creator.displayName
    is TrailRequest.Similar -> "similar posts"
}
