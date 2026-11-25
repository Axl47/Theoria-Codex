package com.theoriacodex.app.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.creator.creatorProfileActions
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.search.retriesOnReconnect
import com.theoriacodex.app.trails.TrailSpec
import com.theoriacodex.app.trails.asTriageFeed
import com.theoriacodex.app.trails.nextOrNull
import com.theoriacodex.app.triage.TriageDecisionEvent
import com.theoriacodex.app.triage.TriageDeck
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.triage.TriageNotice
import com.theoriacodex.app.triage.hiddenBy
import com.theoriacodex.app.triage.needsPictureCheck
import com.theoriacodex.app.triage.noticeLabel
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.SoleJob
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PictureLook
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouDeckViewModel(
    private val engine: ForYouRouteEngine,
    private val wiring: ForYouDeckWiring,
) : ViewModel(), ViewerFeedOwner {
    private val ui = MutableStateFlow(ForYouDeckUiState())
    val state: StateFlow<ForYouDeckUiState> = ui.asStateFlow()

    private val stack = MutableStateFlow(emptyList<ForYouLevel>())

    val levels: StateFlow<List<ForYouLevel>> = stack.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var settings: AppSettings? = null
    private var inputs: MixInputs? = null
    private var hidden: List<ForYouBlacklistEntry> = emptyList()

    private var plannedUnder: List<ForYouBlacklistEntry> = emptyList()

    private val hiddenTokens = MutableStateFlow(emptyMap<SourceKey, Set<String>>())
    private var kept: Set<PostId> = emptySet()
    private var creatorSources: Set<SourceKey> = emptySet()
    private var grid = false

    private var mix: ForYouMix? = null
    private var mixFeed = MutableStateFlow(LOADING_FEED)

    private var dealtBefore = false
    private var lastKept: Pair<String, List<Post>>? = null
    private val deckWrites = Channel<KeptDeck>(Channel.CONFLATED)
    private val heldAsks = LinkedHashMap<PostId, Post>()

    private val session = UUID.randomUUID().toString().take(8)

    private var sequence = 0
    private var trailCount = 0

    private val work = SupervisorJob(viewModelScope.coroutineContext.job)
    private val workScope = CoroutineScope(viewModelScope.coroutineContext + work)

    private val seen = mutableSetOf<PostId>()

    private val verdictLog = ForYouVerdictLog(wiring.verdicts, wiring.writeScope, workScope, wiring.clock)

    private val steeredFrom = mutableMapOf<PostId, Pair<DeckSteering, DeckSteering>>()
    private val asking = mutableMapOf<PostId, Job>()

    private var opening = false
    private val forking = SoleJob(workScope)

    private val vetting = SoleJob(workScope)

    private val relatedState = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
    private val relatedPosts = RelatedPostsRouteController(
        scope = viewModelScope,
        loader = wiring.relatedLoader,
        currentState = { relatedState.value },
        canonicalPosts = { top.feed.value.posts },
        updateState = { relatedState.value = it },
    )

    private val top: ForYouLevel get() = stack.value.last()

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
        wiring.writeScope.launch {
            for (deck in deckWrites) runCatchingPreservingCancellation { engine.rememberDeck(deck.request, deck.posts) }
        }
        viewModelScope.launch { wiring.reconnections.collect { retryAfterReconnect() } }
        viewModelScope.launch {
            var wasReady = wiring.pictures.ready.value
            wiring.pictures.ready.collect { ready ->
                val optedOut = wasReady && !ready
                wasReady = ready
                if (optedOut) return@collect rebuild()
                val mix = mix ?: return@collect
                if (vetsPictures) {
                    mix.revet(::needsLook)
                    vet(mix)
                }
            }
        }
    }

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
            val vetted = vetsPictures
            hidden = blacklist
            hiddenTokens.value = blacklist.filter { it.tags.size == 1 }
                .groupBy({ it.source }) { normalizeMatchToken(it.tags.single()) }.mapValues { it.value.toSet() }
            if (!vetted && vetsPictures) mix?.revet(::needsLook)
        }
        when {
            previous != next -> {
                if (previous?.profileId != next.profileId) {
                    seen.clear()
                    verdictLog.reset(readFor = null)
                }
                rebuild()
            }
            hiddenChanged -> {
                dropHidden()
                if (unhidden.any { it in plannedUnder }) restoreLanes(unhidden)
            }
        }
        keepUpcoming()
    }

    fun startOver() {
        seen.clear()
        verdictLog.reset(readFor = inputs?.profileId)
        rebuild()
    }

    fun rebuild() {
        val request = request() ?: return
        val profileId = request.settings.activeProfileId
        work.cancelChildren()
        asking.clear()
        mix = null
        steeredFrom.clear()
        plannedUnder = hidden
        heldAsks.clear()
        resetLevels()
        ui.update { it.copy(mix = ForYouMixState.Dealing, failure = null, lanes = emptyList(), failures = emptyList()) }
        if (request.availableSources.isEmpty()) return settle(ForYouMixState.NoSources)
        val restoring = !dealtBefore
        dealtBefore = true
        if (vetsPictures) workScope.launch { wiring.pictures.prepare() }
        workScope.launch {
            val skips = async { verdictLog.readSkips(profileId) }
            val painted = if (restoring) async { paintLastDeck(request, skips) } else null
            val lanes = runCatchingPreservingCancellation { engine.lanes(request) }.getOrElse { error ->
                painted?.await()
                Diagnostics.report("for-you", "For You could not build its lanes", error)
                return@launch settle(ForYouMixState.Failed, error.message)
            }
            skips.await()
            if (lanes.isEmpty()) {
                painted?.await()
                return@launch settle(ForYouMixState.NoLanes)
            }
            val leading = painted?.await().orEmpty()
            val built = ForYouMix(lanes, wiring.copiesOf, leading)
            mix = built
            lastKept = request.deckKey() to leading
            opening = true
            refill(built)
            opening = false
            if (built.wanted) makeUp(built)
            heldAsks.values.forEach { post -> askForMore(built, post) }
            heldAsks.clear()
            vet(built)
        }
    }

    private suspend fun paintLastDeck(request: ForYouFeedRequest, skips: Deferred<Unit>): List<Post> {
        val saved = runCatchingPreservingCancellation { engine.lastDeck(request) }.getOrDefault(emptyList())
        skips.await()
        if (!verdictLog.hasSkipsFor(request.settings.activeProfileId)) return emptyList()
        val shown = saved.filter { post -> request.offers(post) && showsAgain(post) && !needsLook(post) }
        if (shown.isNotEmpty()) mixFeed.value = TriageFeedSnapshot(shown, canLoadMore = true, loadingMore = true)
        return shown
    }

    private fun keepUpcoming() {
        val mix = mix ?: return
        val request = request() ?: return
        val kept = request.deckKey() to mix.freshFirst().filter(::showsAgain).take(KEPT_DECK_SIZE)
        if (kept == lastKept) return
        lastKept = kept
        deckWrites.trySend(KeptDeck(request, kept.second))
    }

    private fun request(): ForYouFeedRequest? {
        val inputs = inputs ?: return null
        val settings = settings ?: return null
        return ForYouFeedRequest(settings, inputs.sources, inputs.source, inputs.sort, inputs.animatedOnly)
    }

    fun retrySource(source: SourceKey? = null) {
        val mix = mix ?: return
        mix.failedLanes(source).forEach { lane ->
            lane.clearFailure()
            load(mix, lane)
        }
        publish()
    }

    fun fork(request: TrailRequest, label: String? = null, carry: Post? = request.from) {
        val from = top
        val animatedOnly = inputs?.animatedOnly == true
        forking.launchIfIdle {
            val spec = runCatchingPreservingCancellation { wiring.trails(request, animatedOnly) }.getOrNull()
            when {
                top !== from -> Unit
                spec == null -> messageChannel.trySend("Couldn't open ${label ?: request.name()}")
                else -> push(request, spec, label ?: spec.label, carried = from.deck.state.value.current?.takeIf { !grid && it.id == carry?.id })
            }
        }
    }

    fun focusLane(laneId: String, from: Post? = null) {
        val lane = mix?.lanes?.firstOrNull { it.origin.laneId == laneId } ?: return
        val tag = lane.lane.tags.lastOrNull() ?: return
        val current = top.deck.state.value.current
        val post = from ?: current ?: top.feed.value.posts.firstOrNull() ?: return
        val carry = from ?: current?.takeIf { originOf(it.id) == lane.origin }
        fork(TrailRequest.Tag(tag, lane.lane.source, lane.lane.cursor.queriesBySource[lane.lane.source], post), lane.origin.label, carry)
    }

    fun warmLanes() {
        val mix = mix ?: return
        mix.steering = mix.steering.warmed()
        steeredFrom.clear()
        publish()
    }

    fun popTo(depth: Int) {
        val levels = stack.value
        if (depth !in 0 until levels.lastIndex) return
        levels.drop(depth + 1).forEach(ForYouLevel::close)
        relatedPosts.clear("level left")
        resume(levels[depth])
        stack.value = levels.take(depth + 1)
    }

    private fun resume(level: ForYouLevel) {
        val deck = level.deck.state.value
        val decided = (seen - level.leftWith) + deck.posts.drop(deck.index).filter { it.copyOfAny(seen::contains) }.map(Post::id)
        level.deck.passOver(decided, forgetUndo = seen != level.leftWith)
    }

    fun originOf(post: PostId): DeckOrigin? = mix?.origins?.get(post)

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
        deckWrites.close()
        stack.value.forEach(ForYouLevel::close)
    }

    private fun retryAfterReconnect() {
        if (ui.value.mix == ForYouMixState.Failed) {
            if (mixFeed.value.posts.isEmpty()) rebuild()
            return
        }
        mix?.let { mix ->
            mix.lanes.filter { it.status?.retriesOnReconnect() == true }.forEach { lane ->
                lane.clearFailure()
                load(mix, lane)
            }
            publish()
        }
        if (stack.value.size > 1 && top.feed.value.pageFailed) top.retryPage()
    }

    private fun settle(state: ForYouMixState, failure: String? = null) {
        ui.update { it.copy(mix = state, failure = failure) }
        mixFeed.value = TriageFeedSnapshot(mixFeed.value.posts, canLoadMore = false, pageFailed = state == ForYouMixState.Failed)
    }

    private fun load(mix: ForYouMix, lane: MixLane) {
        val cursor = lane.cursor ?: return
        lane.loading = true
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val page = runCatchingPreservingCancellation { engine.loadPage(cursor, setOf(lane.lane.source)) }
            lane.loading = false
            page.onSuccess { lane.accept(cursor, it, it.items.filter { post -> shows(post) }, ::needsLook) }.onFailure { error ->
                Diagnostics.report("for-you", "A For You lane failed to load", error)
                lane.fail(error.message)
            }
            when {
                opening -> Unit
                mix.wanted -> makeUp(mix)
                else -> refill(mix)
            }
            if (!opening) vet(mix)
        }
    }

    private val vetsPictures: Boolean get() = wiring.pictures.ready.value && hiddenTokens.value.values.any { it.isNotEmpty() }

    private fun vet(mix: ForYouMix) {
        vetting.launchIfIdle {
            while (true) {
                val (lane, post) = mix.nextToVet(VET_AHEAD) ?: break
                vetOne(mix, lane, post)
                if (mix.wanted) makeUp(mix) else publish()
            }
        }
    }

    private suspend fun vetOne(mix: ForYouMix, lane: MixLane, post: Post) {
        val passed = mix.passesOver(post, ::isKnown)
        if (passed || !vetsPictures) return lane.vetted(post, !passed && shows(post))
        when (wiring.pictures.look(post)) {
            is PictureLook.Seen -> lane.vetted(post, shows(post))
            PictureLook.Unseen -> lane.vetted(post, shows = false)
            PictureLook.Blind -> delay(BLIND_RETRY_MS)
        }
    }

    private fun deal() {
        val mix = mix ?: return
        mix.deal(if (mix.opening) DECK_DEAL else dealSize, ::isKnown)
        refill(mix)
    }

    private fun makeUp(mix: ForYouMix) {
        mix.deal(mix.owed(if (mix.opening) DECK_DEAL else dealSize), ::isKnown)
        refill(mix)
    }

    private fun refill(mix: ForYouMix) {
        while (true) load(mix, mix.lanesToRefill().firstOrNull() ?: break)
        publish()
        if (!opening) vet(mix)
    }

    private fun publish() {
        val mix = mix ?: return
        mixFeed.value = mix.snapshot()
        keepUpcoming()
        ui.update { state ->
            val lanes = if (mix.released.isEmpty()) emptyList() else mix.lanes.map { ForYouLaneChoice(it.origin.laneId, it.origin.label, mix.steering.cooled(it.origin.laneId)) }
            state.copy(lanes = lanes, failures = mix.failures())
        }
    }

    private fun shows(post: Post): Boolean =
        (inputs?.animatedOnly != true || isAnimatedPost(post)) && !post.hiddenBy(hiddenTokens.value, wiring.pictures)

    private fun showsAgain(post: Post): Boolean = !post.copyOfAny(::isKnown) && shows(post)

    private fun needsLook(post: Post): Boolean = vetsPictures && post.needsPictureCheck(wiring.pictures.library.value.baseRates.keys)

    private fun dropHidden() {
        val mix = mix ?: return
        mix.hideLanes { hidden.hidesSeed(it.source, it.tags) }
        mix.keepWaiting { shows(it) }
        if (mix.wanted) makeUp(mix) else refill(mix)
    }

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
        return level("$LEVEL_ID_PREFIX$session:mix:$level", "Mix", fork = null, query = null, source, DECK_PAGE_AHEAD) { event ->
            record(event)
            if (level == sequence) steer(event)
        }
    }

    private fun push(request: TrailRequest, spec: TrailSpec, label: String, carried: Post?) {
        val scope = levelScope()
        val known = seen + kept + carried?.let { wiring.copiesOf(it) + it.id }.orEmpty()
        val trail = spec.pager.asTriageFeed(scope) { posts -> posts.filter { !it.copyOfAny(known::contains) && shows(it) } }
        val source = carried?.let { trail.leadingWith(it) } ?: trail
        top.leftWith = seen.toSet()
        relatedPosts.clear("level opened")
        val id = "$LEVEL_ID_PREFIX$session:trail:${++trailCount}"
        val level = level(id, label, request, spec.query, source, scope = scope, lead = carried) { event ->
            record(event)
            steer(event)
            if (event is TriageDecisionEvent.Reverted && stack.value.none { it.id == id }) {
                noticeLabel(TriageNotice(event.decision, failed = true))?.let(messageChannel::trySend)
            }
        }
        stack.update { it + level }
    }

    private fun level(
        id: String,
        label: String,
        fork: TrailRequest?,
        query: Query?,
        source: TriageFeed,
        pageAhead: Int = TRAIL_PAGE_AHEAD,
        scope: CoroutineScope = levelScope(),
        lead: Post? = null,
        onDecision: (TriageDecisionEvent) -> Unit,
    ): ForYouLevel {
        val resolved = MutableStateFlow(emptyMap<PostId, Post>())
        val fuller = combine(source.snapshots, resolved) { snapshot, fuller ->
            if (fuller.isEmpty()) snapshot else snapshot.copy(posts = snapshot.posts.map { fuller[it.id] ?: it })
        }
        val feed = combine(fuller, hiddenTokens, wiring.pictures.library) { snapshot, hidden, _ ->
            snapshot.copy(posts = snapshot.posts.filterNot { it.hiddenBy(hidden, wiring.pictures) })
        }.stateIn(scope, SharingStarted.Eagerly, LOADING_FEED)
        val shared = paging(feed, source)
        val deck = TriageDeck(
            scope, paging(fuller, source), wiring.actions, wiring.quickSave, query, fromForYou = true, wiring.writeScope,
            pageAhead = pageAhead, onDecision = onDecision, ownerHidden = hiddenTokens, pictures = wiring.pictures, lead = lead,
        )
        return ForYouLevel(id, label, fork, query, deck, feed, shared, scope) { post -> resolved.update { it + (post.id to post) } }
    }

    private fun paging(snapshots: Flow<TriageFeedSnapshot>, source: TriageFeed) = object : TriageFeed {
        override val snapshots = snapshots
        override fun loadNextPage() = source.loadNextPage()
        override fun retryPage() = source.retryPage()
    }

    private fun levelScope(): CoroutineScope {
        val context = viewModelScope.coroutineContext
        return CoroutineScope(context + SupervisorJob(context.job))
    }

    private fun record(event: TriageDecisionEvent) {
        val post = event.decision.post
        when (event) {
            is TriageDecisionEvent.Decided -> {
                seen += post.id
                keepUpcoming()
                verdictLog.record(event.decision, inputs?.profileId ?: return)
            }
            is TriageDecisionEvent.Reverted -> {
                seen -= post.id
                keepUpcoming()
                verdictLog.revert(event.decision)
            }
        }
    }

    private fun Post.copyOfAny(ids: (PostId) -> Boolean): Boolean = ids(id) || wiring.copiesOf(this).any(ids)

    private fun isKnown(id: PostId): Boolean = id in seen || id in verdictLog || id in kept

    private fun steer(event: TriageDecisionEvent) {
        val mix = mix ?: return holdAsk(event)
        val post = event.decision.post
        when (event) {
            is TriageDecisionEvent.Decided -> {
                val signal = event.decision.verdictKind() ?: return
                mix.origins[post.id]?.let { origin ->
                    val before = mix.steering
                    mix.steering = before.after(signal, origin)
                    steeredFrom[post.id] = before.signals() to mix.steering.signals()
                    publish()
                }
                if (signal == ForYouVerdictKind.LIKED || signal == ForYouVerdictKind.SAVED) askForMore(mix, post)
            }
            is TriageDecisionEvent.Reverted -> {
                asking.remove(post.id)?.cancel()
                val before = steeredFrom.remove(post.id)?.takeIf { (_, after) -> after == mix.steering.signals() }?.first
                mix.steering = mix.steering.undone(before ?: mix.steering, post.id)
                publish()
            }
        }
    }

    private fun holdAsk(event: TriageDecisionEvent) {
        val post = event.decision.post
        when (event) {
            is TriageDecisionEvent.Decided -> when (event.decision.verdictKind()) {
                ForYouVerdictKind.LIKED, ForYouVerdictKind.SAVED -> heldAsks[post.id] = post
                else -> Unit
            }
            is TriageDecisionEvent.Reverted -> heldAsks.remove(post.id)
        }
    }

    private fun askForMore(mix: ForYouMix, post: Post) {
        val animatedOnly = inputs?.animatedOnly == true
        val creator = creatorProfileActions(post, creatorSources).firstNotNullOfOrNull { it.profile }
        val job = workScope.launch(start = CoroutineStart.LAZY) {
            val similar = wiring.trails(TrailRequest.Similar(post), animatedOnly)
            val (spec, origin) = when {
                similar != null -> similar to DeckOrigin.Similar(post.id)
                creator != null -> wiring.trails(TrailRequest.Creator(creator, post), animatedOnly) to DeckOrigin.Creator(post.id, creator)
                else -> null to null
            }
            val page = withContext(wiring.fetchContext) { spec?.pager?.nextOrNull() }
            val found = if (origin == null) emptyList() else page?.posts.orEmpty().map { DeckCard(it, origin) }
            val fresh = vetted(found.filter { card ->
                !card.post.copyOfAny { it == post.id || isKnown(it) } && !mix.isDealt(card.post.id) && shows(card.post)
            }.distinctBy { it.post.id })
            if (fresh.isEmpty()) return@launch
            mix.steering = mix.steering.inject(fresh)
            if (mix.wanted) makeUp(mix) else publish()
        }
        asking[post.id] = job
        job.invokeOnCompletion { asking.remove(post.id, job) }
        job.start()
    }

    private suspend fun vetted(candidates: List<DeckCard>): List<DeckCard> {
        if (!vetsPictures) return candidates.take(INJECTED)
        val fresh = mutableListOf<DeckCard>()
        for (card in candidates) {
            if (fresh.size == INJECTED) break
            if (!needsLook(card.post) || wiring.pictures.look(card.post) is PictureLook.Seen && shows(card.post)) fresh += card
        }
        return fresh
    }

    private companion object {
        const val DECK_DEAL = 4

        const val BLIND_RETRY_MS = 5_000L
        const val GRID_DEAL = 12

        const val DECK_PAGE_AHEAD = 2
        const val TRAIL_PAGE_AHEAD = 5
        const val INJECTED = 2

        const val VET_AHEAD = 3

        const val KEPT_DECK_SIZE = 12
    }
}

private class KeptDeck(val request: ForYouFeedRequest, val posts: List<Post>)

private fun ForYouFeedRequest.offers(post: Post): Boolean =
    post.id.source in availableSources && (selectedSource == null || post.id.source == selectedSource)

private fun TriageFeed.leadingWith(lead: Post): TriageFeed {
    val trail = this
    return object : TriageFeed {
        override val snapshots = trail.snapshots.map { it.copy(posts = listOf(lead) + it.posts) }
        override fun loadNextPage() = trail.loadNextPage()
        override fun retryPage() = trail.retryPage()
    }
}

private fun TrailRequest.name(): String = when (this) {
    is TrailRequest.Tag -> tag.replace('_', ' ')
    is TrailRequest.Creator -> creator.displayName
    is TrailRequest.Similar -> "similar posts"
}

internal const val LEVEL_ID_PREFIX = "for_you:"
