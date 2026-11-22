package com.example.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ai.ApiKeyFailoverManager
import com.example.ai.GeminiAiPlanner
import com.example.game.BoardCalibrationProfile
import com.example.game.BoardPieceCounts
import com.example.game.BoardProfileMatcher
import com.example.game.CarromCoachHelper
import com.example.game.CarromGeometrySolver
import com.example.game.CarromMemoryData
import com.example.game.CarromPowerStyle
import com.example.game.CarromShotMemoryStore
import com.example.game.CarromShotOutcomeType
import com.example.game.CarromTurnHudState
import com.example.game.CarromTurnMemory
import com.example.game.CarromVisionDetector
import com.example.game.CarromVisionOutcome
import com.example.game.CarromWatchTrainer
import com.example.game.SelfPlayConfig
import com.example.game.CoinColor
import com.example.game.MissCauseInput
import com.example.game.PendingCarromShotRecord
import com.example.game.Point2D
import com.example.game.TurnProbeStage
import com.example.service.AgentKeepAliveService
import com.example.service.LiveMediaProjectionCapturer
import com.example.service.TapPilotAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import java.lang.ref.WeakReference

private const val TAG = "AgentCoordinator"
private const val MAX_TRACE_ENTRIES = 80
private const val MAX_NOTIF_BUFFER = 10

object AgentCoordinator {

    private data class PendingSliderVerify(
        val targetStrikerX: Float,
        val preStrikerX: Float,
        val commandedDeltaX: Float,
        val correctionAttempts: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var settingsStore: AgentSettingsStore? = null
    private var knowledgeStore: AgentKnowledgeStore? = null
    private var actionCacheStore: ActionCacheStore? = null
    private var contactProfileStore: ContactProfileStore? = null
    private var carromShotMemoryStore: CarromShotMemoryStore? = null
    // Self-play trainer: at most one idle-triggered sweep per app session
    // (the settings toggle can still trigger a fresh sweep on demand).
    private var selfPlayRanThisSession = false
    private var settingsCollectJob: Job? = null
    private var playbooksCollectJob: Job? = null
    private var cacheCollectJob: Job? = null
    private var contactsCollectJob: Job? = null
    private var carromMemoryCollectJob: Job? = null
    private val aiPlanner = GeminiAiPlanner()

    private var serviceRef: WeakReference<TapPilotAccessibilityService>? = null
    private var activeRunJob: Job? = null
    private var pauseGate: CompletableDeferred<Unit>? = null
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private var notificationWakeSignal: CompletableDeferred<Unit>? = null

    private val recentNotifications = ArrayDeque<NotificationSignal>()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _settings = MutableStateFlow(AgentSettings())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val _playbooks = MutableStateFlow<List<TaskPlaybook>>(emptyList())
    val playbooks: StateFlow<List<TaskPlaybook>> = _playbooks.asStateFlow()

    private val _cachedActions = MutableStateFlow<List<CachedActionEntry>>(emptyList())
    val cachedActions: StateFlow<List<CachedActionEntry>> = _cachedActions.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactProfile>>(emptyList())
    val contacts: StateFlow<List<ContactProfile>> = _contacts.asStateFlow()

    private val _carromMemory = MutableStateFlow(CarromMemoryData())
    val carromMemory: StateFlow<CarromMemoryData> = _carromMemory.asStateFlow()

    private val _carromTurnHud = MutableStateFlow<CarromTurnHudState?>(null)
    val carromTurnHud: StateFlow<CarromTurnHudState?> = _carromTurnHud.asStateFlow()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    fun initialize(context: Context) {
        if (settingsStore != null) return
        val app = context.applicationContext
        appContext = app
        val store = AgentSettingsStore(app)
        settingsStore = store
        settingsCollectJob = scope.launch {
            store.settingsFlow.collect { latest ->
                _settings.value = latest
            }
        }
        val kStore = AgentKnowledgeStore(app)
        knowledgeStore = kStore
        playbooksCollectJob = scope.launch {
            kStore.playbooks.collect { list ->
                _playbooks.value = list
            }
        }
        val cStore = ActionCacheStore(app)
        actionCacheStore = cStore
        cacheCollectJob = scope.launch {
            cStore.cachedActions.collect { list ->
                _cachedActions.value = list
            }
        }
        val contactStore = ContactProfileStore(app)
        contactProfileStore = contactStore
        contactsCollectJob = scope.launch {
            contactStore.contacts.collect { list ->
                _contacts.value = list
            }
        }
        val carromStore = CarromShotMemoryStore(app)
        carromShotMemoryStore = carromStore
        carromMemoryCollectJob = scope.launch {
            carromStore.memory.collect { mem ->
                _carromMemory.value = mem
            }
        }
        scope.launch {
            kStore.loadInitial()
            cStore.loadInitial()
            contactStore.loadInitial()
            carromStore.loadInitial()
        }
    }

    fun attachService(service: TapPilotAccessibilityService) {
        serviceRef = WeakReference(service)
        _isServiceConnected.value = true
        Log.d(TAG, "AccessibilityService attached.")
    }

    fun detachService(service: TapPilotAccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            _isServiceConnected.value = false
            stopTask("Accessibility Service disconnected.")
        }
    }

    fun onNotificationReceived(signal: NotificationSignal) {
        synchronized(recentNotifications) {
            if (recentNotifications.size >= MAX_NOTIF_BUFFER) {
                recentNotifications.removeFirst()
            }
            recentNotifications.addLast(signal)
        }
        // Wake up background monitor sleep early so it can inspect new message/comment!
        notificationWakeSignal?.complete(Unit)
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val store = settingsStore ?: return
        val before = _settings.value
        val after = transform(before)
        scope.launch {
            store.updateSettings(transform)
            // Self-play trainer (settings toggle): the user just turned
            // training on — run one sweep in the background now, while the
            // app is idle on the settings screen, so the learned table is
            // ready before the next match.
            if (!before.carromSelfPlayEnabled && after.carromSelfPlayEnabled) {
                carromShotMemoryStore?.runSelfPlayTraining(
                    SelfPlayConfig(maxShots = after.carromSelfPlayShots)
                )
            }
        }
    }

    fun deletePlaybook(topicKey: String) {
        val store = knowledgeStore ?: return
        scope.launch {
            store.deletePlaybook(topicKey)
        }
    }

    fun deleteCachedAction(cacheKey: String) {
        val store = actionCacheStore ?: return
        scope.launch {
            store.removeEntry(cacheKey)
        }
    }

    fun clearActionCache() {
        val store = actionCacheStore ?: return
        scope.launch {
            store.clearAll()
        }
    }

    fun upsertContact(profile: ContactProfile) {
        val store = contactProfileStore ?: return
        scope.launch {
            store.upsertContact(profile)
        }
    }

    fun deleteContact(id: String) {
        val store = contactProfileStore ?: return
        scope.launch {
            store.deleteContact(id)
        }
    }

    fun resetKeyFailoverHealth() {
        ApiKeyFailoverManager.resetHealth()
    }

    fun exportCarromTrainingJson(): String {
        val currentMem = _carromMemory.value
        val carromPb = _playbooks.value.firstOrNull {
            AgentKnowledgeStore.normalizeKey(it.topicKey) == "carrom_board_game"
        } ?: knowledgeStore?.findMatchingPlaybook("carrom")
        return CarromShotMemoryStore.exportTrainingBundleJson(
            memory = currentMem,
            carromPlaybook = carromPb
        )
    }

    suspend fun importCarromTrainingJson(raw: String): Result<String> {
        return runCatching {
            val (importedMem, importedPb) = CarromShotMemoryStore.parseTrainingBundleJson(raw)
            val memStore = carromShotMemoryStore
                ?: error("Carrom memory store is not initialized yet.")
            memStore.replaceWithImported(importedMem)
            if (importedPb != null) {
                knowledgeStore?.saveOrMergePlaybook(importedPb)
            }
            val totalShots = importedMem.totalLearnedAttempts()
            val totalPots = importedMem.totalLearnedPots()
            val blockedFouls = importedMem.blockedFoulCount()
            "Imported $totalShots shots ($totalPots pots, $blockedFouls blocked fouls)!"
        }
    }

    fun saveBoardCalibration(profile: BoardCalibrationProfile) {
        val store = carromShotMemoryStore ?: return
        scope.launch {
            // Named-profile save: upserts into the profile book and
            // activates it (solver + learned tables switch to this board).
            store.saveNamedBoardProfile(profile)
        }
    }

    // Per-run state shared by the startTask() phases. Splitting the former
    // 2,329-line startTask() into small functions (Kotlin back-end
    // StackOverflowError fix) needs the run locals somewhere shared; they
    // live here instead of as 40+ parameters on every phase function.
    // `cacheTrail` is assigned in prepareTaskRun (it needs actionCacheStore).
    private class TaskRunState(
        val goal: String,
        val mode: AgentExecutionMode,
        val service: TapPilotAccessibilityService,
        val initialSettings: AgentSettings,
        val maxStepsLimit: Int?
    ) {
        val trace = mutableListOf<AgentStepLog>()
        var workingMemory = ""
        var activePlaybook: TaskPlaybook? = null
        var lastOutcomeHint = ""
        var step = 1
        val runStartedMs = System.currentTimeMillis()
        val pendingReplyQueue = ArrayDeque<NotificationReplyTarget>()
        val handledNotificationKeys = mutableSetOf<String>()
        var activeReplyTarget: NotificationReplyTarget? = null
        lateinit var cacheTrail: VerifiedCacheTrail
        var pendingCacheCandidate: PendingCacheCandidate? = null
        var lastReplayEntry: Pair<String, String>? = null
        var launcherOpenedDrawer = false
        var launcherTappedSearch = false
        var launcherTypedSearch = false
        var pendingLauncherStageTag: String? = null
        var carromOfflineWaits = 0
        var didSlideCarromThisStep = false
        var isCarromOfflineWait = false
        var movedStrikerThisTurn = false
        var pendingSliderVerify: PendingSliderVerify? = null
        var pendingCarromShot: PendingCarromShotRecord? = null
        var consecutiveUnregisteredCarromShots = 0
        var lastShotAttemptStrikerX: Float? = null
        var lastShotPrePieces: List<com.example.game.CarromPiece> = emptyList()
        var queenPendingCover = false
        // Fresh-rack latch: set once the board is seen depleted mid-match.
        var carromSawDepletedBoard = false
        // Striker-sweep cache (try-and-see aim test) for one board layout.
        var carromSweepKey: String? = null
        var carromSweepEntries: List<CarromGeometrySolver.StrikerSweepEntry> = emptyList()
        // Miss-cause vision guard: cycles spent waiting for a 2-frame-stable
        // post-shot detection before recording the outcome.
        var pendingEvalStaleCycles = 0
        val carromTurnMemory = CarromTurnMemory()
        val awaitingRepliesByPeer = mutableMapOf<String, ChatTurnGuard.AwaitingReplyState>()
        var pendingSentTextForPeer: Pair<String, String>? = null
        var prevCompositeScreen: String? = null
        var prevPackageName: String? = null
        var prevNonWaitActionType: AgentActionType? = null
        var prevInputText: String? = null
        var consecutiveNoChangeCount = 0
        var activeSubGoalChecklist = ""
        var completionGuardOverrides = 0
        var directLaunchedTargetKey: String? = null
        val historyScrolledPeers = mutableSetOf<String>()
        val carromWatchTrainer = CarromWatchTrainer()
        var lastWatchUiUpdateMs = 0L
        var lastWatchHudDetail = ""
        var lastStepWasCarromContext = false
        var isCarromTaskGoal = false
        var isCarromWatchTrainMode = false
    }

    // What observeAndIntake() saw this step.
    private data class StepObservation(
        val settings: AgentSettings,
        val snapshot: UiSnapshot,
        val currentSig: String,
        val currentCompositeScreen: String,
        val isCarromContext: Boolean,
        val isOnLauncher: Boolean,
        val isWrongNotifApp: Boolean,
        val wasWrongApp: Boolean,
        val interactiveNodeCount: Int,
        val isNonGameApp: Boolean,
        val isLikelyCarromLobby: Boolean,
        val isActiveCarromMatchScreen: Boolean,
        val notificationsSnapshot: List<NotificationSignal>,
        val currentContacts: List<ContactProfile>,
        val shouldWaitForIncomingNotification: Boolean,
        val targetAppOverride: String?,
        val currentActiveTarget: NotificationReplyTarget?,
        val justEvictedStaleCache: Boolean,
        val effectiveSavedContactsOnly: Boolean
    )

    // What buildStepContext() derived this step.
    private data class StepAnalysis(
        val chatObs: ChatTurnGuard.ChatObservation,
        val cacheMatch: CacheReplayMatch?,
        val matchedContact: ContactProfile?,
        val savedContactsSummary: String,
        val chatGuardHint: String,
        val actionReflectorHint: String,
        val effectiveCarromRule: CarromRuleMode,
        val isFreestyleMode: Boolean,
        val myCarromCoinColor: CoinColor,
        val activeAwaitingReply: ChatTurnGuard.AwaitingReplyState?,
        val shouldZeroApiWaitForReply: Boolean,
        val currentActiveTarget: NotificationReplyTarget?
    )

    // What a planning phase decided for this step.
    private sealed interface ActionDecision {
        data class Action(
            val action: PlannedAction,
            val usedLocalCarromSolver: Boolean,
            val nextLauncherStageTag: String?,
            val nextPendingCarromShotCandidate: PendingCarromShotRecord?,
            val aiPreCarromCounts: BoardPieceCounts?,
            val launcherDecision: LauncherSearchDecision?
        ) : ActionDecision

        object NextStep : ActionDecision
        object EndRun : ActionDecision
    }

    // What the local Carrom solver decided for this step.
    private data class CarromActionResult(
        val action: PlannedAction?,
        val usedLocalCarromSolver: Boolean,
        val nextPendingCarromShotCandidate: PendingCarromShotRecord?,
        val aiPreCarromCounts: BoardPieceCounts?
    )

    // Context the fire phase needs from the probe phase of a solved shot.
    private data class CarromShotProbe(
        val striker: com.example.game.CarromPiece?,
        val inPlaceSol: com.example.game.CarromShotSolution?,
        val isVerifiedSureHere: Boolean,
        val deadlineFire: Boolean,
        val consensusPieces: List<com.example.game.CarromPiece>,
        val fusedPiecesWithStriker: List<com.example.game.CarromPiece>
    )

    private sealed interface CarromShotProbeResult {
        data class Slide(val action: PlannedAction) : CarromShotProbeResult
        data class Shoot(val probe: CarromShotProbe) : CarromShotProbeResult
    }

    private data class CarromShotResult(
        val action: PlannedAction,
        val pendingShot: PendingCarromShotRecord?
    )

    private enum class StepFlow { PROCEED, NEXT_STEP, END_RUN }
    fun startTask(
        goalInput: String,
        mode: AgentExecutionMode = AgentExecutionMode.SINGLE_SESSION
    ) {
        val goal = goalInput.trim()
        if (goal.isEmpty()) {
            _uiState.value = AgentUiState.Error(
                goal = "",
                message = "Please enter an automation goal first.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        val service = serviceRef?.get()
        if (service == null) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "Accessibility Service is not enabled. Enable TapPilot in Android Accessibility Settings first.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        val initialSettings = _settings.value
        val firebaseReady = aiPlanner.isFirebaseConfigured()
        val isCarromGoal = CarromContextHelper.isCarromGoalOrApp(
            goal = goal,
            packageName = "",
            playbookTopicKey = null
        )
        if (!isCarromGoal && !initialSettings.hasAnyRunnableKey(firebaseReady)) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "No active API key configured. Open Settings and paste 1–30 free keys for Google AI Studio, Groq Cloud, or OpenRouter.",
                workingMemory = "",
                trace = emptyList()
            )
            return
        }

        activeRunJob?.cancel()
        pauseGate = null
        confirmationDeferred = null
        notificationWakeSignal = null
        synchronized(recentNotifications) {
            recentNotifications.clear()
        }

        val maxStepsLimit: Int? = if (mode == AgentExecutionMode.SINGLE_SESSION) {
            initialSettings.maxSteps
        } else {
            null
        }

        appContext?.let { AgentKeepAliveService.start(it) }

        activeRunJob = scope.launch {
            runTask(TaskRunState(goal, mode, service, initialSettings, maxStepsLimit))
        }
    }
    // Main run loop, extracted from startTask() so no single function is
    // large enough to overflow the Kotlin back end. The phase order and
    // the continue/return semantics are exactly the original loop's.
    private suspend fun runTask(state: TaskRunState) {
        try {
            prepareTaskRun(state)
            while (state.maxStepsLimit == null || state.step <= state.maxStepsLimit) {
                val obs = observeAndIntake(state) ?: continue
                if (state.isCarromWatchTrainMode) {
                    if (!runWatchTraining(state, obs)) continue
                }
                val analysis = buildStepContext(state, obs)
                when (val decision = decidePlannedAction(state, obs, analysis)) {
                    is ActionDecision.NextStep -> continue
                    is ActionDecision.EndRun -> return
                    is ActionDecision.Action -> {
                        val plannedAction = applyActionGuards(state, obs, analysis, decision.action)
                        when (executePlannedAction(state, obs, analysis, decision, plannedAction)) {
                            StepFlow.PROCEED -> { /* next loop iteration */ }
                            StepFlow.NEXT_STEP -> continue
                            StepFlow.END_RUN -> return
                        }
                    }
                }
            }

            _uiState.value = AgentUiState.Completed(
                goal = state.goal,
                summary = "Reached single-task step limit (${state.maxStepsLimit ?: 0}) without explicit completion.",
                totalSteps = state.maxStepsLimit ?: state.trace.size,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected automation error", e)
            _uiState.value = AgentUiState.Error(
                goal = state.goal,
                message = e.localizedMessage ?: "Unexpected automation failure.",
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
        } finally {
            notificationWakeSignal = null
            appContext?.let { AgentKeepAliveService.stop(it) }
        }
    }
    // One-time per-run setup: playbook lookup, go-home, direct Carrom
    // launch, research, and the watch-train trace line. (Formerly the
    // top of startTask's launch body.)
    private suspend fun prepareTaskRun(state: TaskRunState) {
        val goal = state.goal
        val mode = state.mode
        val service = state.service
        val initialSettings = state.initialSettings
        val maxStepsLimit = state.maxStepsLimit
        state.cacheTrail = VerifiedCacheTrail(actionCacheStore)
        state.activePlaybook = knowledgeStore?.findMatchingPlaybook(goal)
        state.lastOutcomeHint = ""
        state.isCarromTaskGoal = CarromContextHelper.isCarromGoalOrApp(
            goal = goal,
            packageName = "",
            playbookTopicKey = state.activePlaybook?.topicKey
        )
        state.isCarromWatchTrainMode = CarromCoachHelper.isCarromWatchTrainGoal(goal)

        val isNotifAutoReplyAtStart = NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
            goal = goal,
            mode = mode,
            settings = initialSettings
        ) || NotificationReplyHelper.isAllPlatformAutoReplyGoal(goal)

        if (state.activePlaybook == null && state.isCarromTaskGoal) {
            val effectiveRuleAtStart = resolveCarromRuleMode(goal, initialSettings.carromRuleMode)
            val builtIn = CarromContextHelper.buildDefaultCarromPlaybook(effectiveRuleAtStart)
            val loadedPlaybook = knowledgeStore?.saveOrMergePlaybook(builtIn) ?: builtIn
            state.activePlaybook = loadedPlaybook
            state.workingMemory = "Loaded built-in Carrom Playbook [${loadedPlaybook.topicTitle}] (${loadedPlaybook.winningStrategies.size} tactics, ${loadedPlaybook.foulLessons.size} foul guards)."
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = 0,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.WAIT,
                    targetSummary = "Playbook: ${loadedPlaybook.topicTitle}",
                    reason = "Loaded built-in Carrom Playbook instantly (0ms, no research stall).",
                    status = StepStatus.COMPLETED,
                    requiresConfirmation = false
                )
            )
        } else if (isNotifAutoReplyAtStart) {
            state.workingMemory = "Listening in background for incoming message notifications (0 API calls while idle)."
        } else if (state.activePlaybook == null && initialSettings.autoResearchBeforeTask) {
            _uiState.value = AgentUiState.Running(
                goal = goal,
                mode = mode,
                currentStep = 0,
                maxSteps = maxStepsLimit,
                phase = AgentPhase.RESEARCHING,
                lastThought = "Unfamiliar task — researching rules, controls, winning shots & fouls first...",
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            val researched = withTimeoutOrNull(12_000L) {
                withContext(Dispatchers.IO) {
                    aiPlanner.researchTaskPlaybook(goal, initialSettings)
                }.getOrNull()
            }

            if (researched != null) {
                val researchedPlaybook = knowledgeStore?.saveOrMergePlaybook(researched) ?: researched
                state.activePlaybook = researchedPlaybook
                state.workingMemory = "Researched [${researchedPlaybook.topicTitle}]: ${researchedPlaybook.rulesSummary.take(160)}"
                appendTrace(
                    state.trace,
                    AgentStepLog(
                        stepNumber = 0,
                        timestampMs = System.currentTimeMillis(),
                        actionType = AgentActionType.WAIT,
                        targetSummary = "Playbook: ${researchedPlaybook.topicTitle}",
                        reason = "Researched ${researchedPlaybook.winningStrategies.size} strategies & ${researchedPlaybook.foulLessons.size} foul guards.",
                        status = StepStatus.COMPLETED,
                        requiresConfirmation = false
                    )
                )
            }
        } else if (state.activePlaybook != null) {
            val existingPlaybook = state.activePlaybook
            if (existingPlaybook != null) {
                knowledgeStore?.incrementTrainingSession(existingPlaybook.topicKey)
                val refreshedPlaybook = knowledgeStore?.findMatchingPlaybook(goal) ?: existingPlaybook
                state.activePlaybook = refreshedPlaybook
                state.workingMemory = "Loaded trained playbook [${refreshedPlaybook.topicTitle}] (${refreshedPlaybook.winningStrategies.size} tactics, ${refreshedPlaybook.foulLessons.size} foul guards)."
            }
        }

        var launchedCarromDirectly = false
        if (state.isCarromTaskGoal) {
            val launched = appContext?.let { tryLaunchInstalledCarromApp(it) } == true
            if (launched) {
                launchedCarromDirectly = true
                _uiState.value = AgentUiState.Running(
                    goal = goal,
                    mode = mode,
                    currentStep = 0,
                    maxSteps = maxStepsLimit,
                    phase = AgentPhase.ACTING,
                    lastThought = "Opening Carrom game...",
                    workingMemory = state.workingMemory,
                    trace = state.trace.toList()
                )
                delay(1_800L)
            }
        }

        if (!launchedCarromDirectly && initialSettings.goHomeOnLaunch) {
            _uiState.value = AgentUiState.Running(
                goal = goal,
                mode = mode,
                currentStep = 0,
                maxSteps = maxStepsLimit,
                phase = AgentPhase.ACTING,
                lastThought = "Navigating to Home screen...",
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            service.navigateHome()
            delay(900)
        }

        if (state.isCarromWatchTrainMode) {
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = 0,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.WAIT,
                    targetSummary = "Watch & Learn Coach Active",
                    reason = "Observing your live Carrom shots silently (0 touches, 0 API calls) to calibrate angles, power & tactics.",
                    status = StepStatus.COMPLETED,
                    requiresConfirmation = false
                )
            )
        }
        CarromVisionDetector.resetMotionHistory()
        CarromVisionDetector.resetMotionHistory()
        state.lastStepWasCarromContext = state.isCarromTaskGoal
    }
    // Capture the screen, take in notifications, and compute the
    // per-iteration observation. Returns null when the iteration ends
    // early (waiting for an incoming notification). (Formerly the top
    // of startTask's while-loop body.)
    private suspend fun observeAndIntake(state: TaskRunState): StepObservation? {
        val goal = state.goal
        val mode = state.mode
        val service = state.service
        val maxStepsLimit = state.maxStepsLimit
        val currentSettings = _settings.value
        awaitIfPaused(goal, mode, state.step, maxStepsLimit, state.workingMemory, state.trace)

        // Wait cleanly if device screen is currently locked during continuous monitoring
        while (service.isDeviceLocked()) {
            _uiState.value = AgentUiState.Running(
                goal = goal,
                mode = mode,
                currentStep = state.step,
                maxSteps = maxStepsLimit,
                phase = AgentPhase.MONITOR_SLEEPING,
                lastThought = "Screen locked — waiting for device unlock...",
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            delay(4_000L)
            awaitIfPaused(goal, mode, state.step, maxStepsLimit, state.workingMemory, state.trace)
        }

        val notificationsSnapshot = synchronized(recentNotifications) {
            recentNotifications.toList()
        }

        val currentContacts = _contacts.value
        val isAllPlatformBgMode = NotificationReplyHelper.isAllPlatformAutoReplyGoal(goal)
        val effectiveSavedContactsOnly = currentSettings.autoReplySavedContactsOnly && !isAllPlatformBgMode
        val packageLabelResolver: (String) -> String? = { pkgName ->
            runCatching {
                val pm = appContext?.packageManager
                if (pm != null) {
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    pm.getApplicationLabel(appInfo)?.toString()
                } else {
                    null
                }
            }.getOrNull()
        }
        val isNotifDrivenAutoReply = NotificationReplyHelper.isNotificationDrivenAutoReplyGoal(
            goal = goal,
            mode = mode,
            settings = currentSettings
        )
        if (isNotifDrivenAutoReply) {
            val newlyArrived = NotificationReplyHelper.extractNewTargets(
                notifications = notificationsSnapshot,
                minTimestampMs = state.runStartedMs - 30_000L,
                handledNotificationKeys = state.handledNotificationKeys,
                alreadyQueuedOrActive = listOfNotNull(state.activeReplyTarget) + state.pendingReplyQueue,
                allowAnyApp = isAllPlatformBgMode,
                packageLabelResolver = packageLabelResolver
            )
            for (candidateTarget in newlyArrived) {
                if (effectiveSavedContactsOnly) {
                    val savedMatch = ContactProfileStore.matchContactFromList(
                        contacts = currentContacts,
                        peerName = candidateTarget.senderName,
                        packageName = candidateTarget.packageName,
                        goal = goal,
                        recentNotifications = notificationsSnapshot
                    )
                    if (savedMatch == null) {
                        state.handledNotificationKeys.add(candidateTarget.targetKey)
                        state.lastOutcomeHint = "[Saved-Contacts-Only: skipped notification from unsaved sender '${candidateTarget.senderName}'.] ${state.lastOutcomeHint}"
                        continue
                    }
                }
                state.pendingReplyQueue.addLast(candidateTarget)
            }
            if (state.activeReplyTarget == null && state.pendingReplyQueue.isNotEmpty()) {
                val nextTarget = state.pendingReplyQueue.removeFirst()
                state.activeReplyTarget = nextTarget
                val preCheckContact = ContactProfileStore.matchContactFromList(
                    contacts = currentContacts,
                    peerName = nextTarget.senderName,
                    packageName = nextTarget.packageName,
                    goal = goal,
                    recentNotifications = notificationsSnapshot
                )
                val preCheckSummary = NotificationReplyHelper.buildRelationshipCheckSummary(
                    target = nextTarget,
                    matchedContact = preCheckContact
                )
                state.workingMemory = "[Notification: ${nextTarget.senderName} @ ${nextTarget.appDisplayName}] $preCheckSummary"
                state.lastOutcomeHint = "[INCOMING MESSAGE NOTIFICATION from '${nextTarget.senderName}' on ${nextTarget.appDisplayName}: \"${nextTarget.messageText.take(100)}\". $preCheckSummary] ${state.lastOutcomeHint}"
            }
        } else {
            state.activeReplyTarget = null
        }
        val currentActiveTarget = state.activeReplyTarget
        val targetAppOverride = currentActiveTarget?.appDisplayName
        val shouldWaitForIncomingNotification = isNotifDrivenAutoReply && currentActiveTarget == null

        if (shouldWaitForIncomingNotification) {
            val idleReason = "Waiting silently for incoming message notification (0 API calls)..."
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = state.step,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.WAIT,
                    targetSummary = "Wait ${currentSettings.monitorIntervalSec}s (wakes on notification)",
                    reason = idleReason,
                    status = StepStatus.WAITING,
                    requiresConfirmation = false
                )
            )
            sleepMonitorInterval(
                goal = goal,
                mode = mode,
                step = state.step,
                maxSteps = maxStepsLimit,
                waitSec = currentSettings.monitorIntervalSec,
                reason = idleReason,
                workingMemory = state.workingMemory,
                trace = state.trace
            )
            state.step++
            return null
        }

        // Fast direct-launch path: open the exact notification app immediately (< 0.7s) without 4-step launcher search
        if (currentActiveTarget != null && state.directLaunchedTargetKey != currentActiveTarget.targetKey) {
            state.directLaunchedTargetKey = currentActiveTarget.targetKey
            val launchedDirect = appContext?.let { tryLaunchPackage(it, currentActiveTarget.packageName) } == true
            if (launchedDirect) {
                _uiState.value = AgentUiState.Running(
                    goal = goal,
                    mode = mode,
                    currentStep = state.step,
                    maxSteps = maxStepsLimit,
                    phase = AgentPhase.ACTING,
                    lastThought = "Opening ${currentActiveTarget.appDisplayName} for '${currentActiveTarget.senderName}'...",
                    workingMemory = state.workingMemory,
                    trace = state.trace.toList()
                )
                delay(650L)
            }
        }

        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = state.step,
            maxSteps = maxStepsLimit,
            phase = AgentPhase.OBSERVING,
            lastThought = state.trace.lastOrNull()?.reason.orEmpty(),
            workingMemory = state.workingMemory,
            trace = state.trace.toList()
        )

        val shouldCaptureForCarrom = currentSettings.carromPrecisionSolver &&
            (state.isCarromTaskGoal || state.lastStepWasCarromContext)
        val snapshot = service.captureSnapshot(
            currentSettings.useVisionScreenshot ||
                state.isCarromWatchTrainMode ||
                shouldCaptureForCarrom
        )

        val currentSig = ActionCacheStore.computeScreenSignature(snapshot.nodes)
        val currentCompositeScreen = "${snapshot.packageName}::$currentSig"
        val isCarromContext = CarromContextHelper.isCarromGoalOrApp(
            goal = goal,
            packageName = snapshot.packageName,
            playbookTopicKey = state.activePlaybook?.topicKey
        )
        state.lastStepWasCarromContext = isCarromContext
        val selfPkg = appContext?.packageName ?: "com.example"
        val isOnLauncher = LauncherSearchHelper.isLauncherOrSearchScreen(snapshot.packageName, snapshot.nodes)
        val isWrongNotifApp = currentActiveTarget != null &&
            (ChatTurnGuard.isMessagingPackage(snapshot.packageName) ||
                (isAllPlatformBgMode && !NotificationReplyHelper.isSystemOrUtilityPackage(snapshot.packageName))) &&
            !isOnLauncher &&
            snapshot.packageName != selfPkg &&
            !snapshot.packageName.equals(currentActiveTarget.packageName, ignoreCase = true) &&
            !NotificationReplyHelper.appSearchNameForPackage(
                packageName = snapshot.packageName,
                allowAnyApp = isAllPlatformBgMode,
                packageLabelResolver = packageLabelResolver
            ).equals(
                currentActiveTarget.appDisplayName,
                ignoreCase = true
            )
        val wasWrongApp = !isCarromContext && (
            isWrongNotifApp ||
                LauncherSearchHelper.didLandInWrongApp(
                    goal = goal,
                    currentPackage = snapshot.packageName,
                    selfPackage = selfPkg,
                    expectedAppOverride = targetAppOverride
                )
            )

        val prevReplay = state.lastReplayEntry
        val prevCandidate = state.pendingCacheCandidate
        val justEvictedStaleCache = prevReplay != null && prevReplay.second == currentCompositeScreen

        state.cacheTrail.onStepObserved(
            prevCandidate = prevCandidate,
            prevReplayedKey = prevReplay?.first,
            preCompositeScreen = state.prevCompositeScreen,
            currentCompositeScreen = currentCompositeScreen,
            currentPackage = snapshot.packageName,
            currentSignature = currentSig,
            prevActionType = state.prevNonWaitActionType,
            wasWrongPageOrApp = wasWrongApp
        )
        state.lastReplayEntry = null
        state.pendingCacheCandidate = null

        if (wasWrongApp || state.prevNonWaitActionType == AgentActionType.BACK) {
            state.launcherTappedSearch = false
            state.launcherTypedSearch = false
            if (wasWrongApp) {
                state.launcherOpenedDrawer = false
            }
        } else if (!isOnLauncher) {
            state.launcherOpenedDrawer = false
            state.launcherTappedSearch = false
            state.launcherTypedSearch = false
        } else {
            when (state.pendingLauncherStageTag) {
                "OPEN_APP_DRAWER" -> if (state.prevCompositeScreen != currentCompositeScreen || LauncherSearchHelper.isLikelyAppDrawerGrid(snapshot)) {
                    state.launcherOpenedDrawer = true
                }
                "TAP_SEARCH_BAR", "TAP_SEARCH_PILL_FALLBACK" -> if (state.prevCompositeScreen != currentCompositeScreen || LauncherSearchHelper.findEditableSearchNode(snapshot.nodes) != null) {
                    state.launcherTappedSearch = true
                }
                "TYPE_APP_NAME" -> state.launcherTypedSearch = true
            }
        }
        state.pendingLauncherStageTag = null

        // Self-play trainer (idle): the app is sitting on the
        // launcher and the user enabled training — run one sweep
        // in the background so the learned table fills up without
        // ever touching a live match's 5-second budget. Once per
        // app session; the settings toggle can trigger more.
        if (isOnLauncher && currentSettings.carromSelfPlayEnabled && !selfPlayRanThisSession) {
            selfPlayRanThisSession = true
            val store = carromShotMemoryStore
            if (store != null) {
                scope.launch {
                    val report = store.runSelfPlayTraining(
                        SelfPlayConfig(
                            profileName = _carromMemory.value.activeBoardProfileName,
                            maxShots = currentSettings.carromSelfPlayShots
                        )
                    )
                    if (report != null) {
                        state.lastOutcomeHint = "[Self-Play] Trained ${report.shotsRun} shots " +
                            "(${report.sureShotsFound} sure, ${report.bankSureShotsFound} bank, " +
                            "${report.comboSureShotsFound} combo) on '${report.profileName}' " +
                            "in ${report.elapsedMs}ms. ${state.lastOutcomeHint}"
                    }
                }
            }
        }

        val interactiveNodeCount = snapshot.nodes.count { it.isClickable || it.isEditable }
        val isNonGameApp = snapshot.packageName == selfPkg ||
            isOnLauncher ||
            snapshot.packageName == "com.android.systemui"
        val isLikelyCarromLobby = isCarromContext && (isNonGameApp || CarromContextHelper.isLikelyGameLobbyMenu(snapshot.nodes))
        val isActiveCarromMatchScreen = isCarromContext && !isNonGameApp && !isLikelyCarromLobby
        return StepObservation(
            settings = currentSettings,
            snapshot = snapshot,
            currentSig = currentSig,
            currentCompositeScreen = currentCompositeScreen,
            isCarromContext = isCarromContext,
            isOnLauncher = isOnLauncher,
            isWrongNotifApp = isWrongNotifApp,
            wasWrongApp = wasWrongApp,
            interactiveNodeCount = interactiveNodeCount,
            isNonGameApp = isNonGameApp,
            isLikelyCarromLobby = isLikelyCarromLobby,
            isActiveCarromMatchScreen = isActiveCarromMatchScreen,
            notificationsSnapshot = notificationsSnapshot,
            currentContacts = currentContacts,
            shouldWaitForIncomingNotification = shouldWaitForIncomingNotification,
            targetAppOverride = targetAppOverride,
            currentActiveTarget = currentActiveTarget,
            justEvictedStaleCache = justEvictedStaleCache,
            effectiveSavedContactsOnly = effectiveSavedContactsOnly
        )
    }
    // Watch & Learn coach: silently observe the user's live Carrom shots
    // to calibrate angles/power. Returns false when the iteration ends
    // here. (Formerly the isCarromWatchTrainMode block in the loop.)
    private suspend fun runWatchTraining(state: TaskRunState, obs: StepObservation): Boolean {
        val goal = state.goal
        val mode = state.mode
        val maxStepsLimit = state.maxStepsLimit
        val effectiveRuleWatch = resolveCarromRuleMode(goal, obs.settings.carromRuleMode)
        val isFreestyleWatch = effectiveRuleWatch == CarromRuleMode.FREESTYLE
        val watchCoinColor = if (effectiveRuleWatch == CarromRuleMode.BLACK_ONLY) CoinColor.BLACK else CoinColor.WHITE

        if (!obs.isActiveCarromMatchScreen || obs.snapshot.screenshot == null) {
            obs.snapshot.screenshot?.recycle()
            val statusMsg = if (obs.snapshot.screenshot == null) {
                "Watch & Learn waiting for screen capture — enable Live Stream or Screenshot Vision."
            } else {
                "Watch & Learn ready (${state.carromWatchTrainer.shotsObserved} shots learned) — start a Carrom match & play normally!"
            }
            _carromTurnHud.value = CarromTurnHudState(
                isActive = true,
                elapsedSec = 0f,
                maxSec = 0f,
                probeNumber = state.carromWatchTrainer.shotsObserved,
                maxProbes = 99,
                scanCount = state.carromWatchTrainer.potsObserved,
                rejectedCount = state.carromWatchTrainer.foulsObserved,
                stage = TurnProbeStage.WATCH_TRAINING,
                detail = statusMsg.take(64)
            )
            _uiState.value = AgentUiState.Running(
                goal = goal,
                mode = mode,
                currentStep = state.step,
                maxSteps = maxStepsLimit,
                phase = AgentPhase.OBSERVING,
                lastThought = statusMsg,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            delay(550L)
            state.step++
            return false
        }

        val watchObs = CarromVisionDetector.observeBoardForTraining(
            screenshot = obs.snapshot.screenshot,
            screenWidth = obs.snapshot.screenWidth,
            screenHeight = obs.snapshot.screenHeight,
            calibration = _carromMemory.value.boardCalibration
        )
        obs.snapshot.screenshot?.recycle()

        val watchUpdate = state.carromWatchTrainer.processObservation(
            obs = watchObs,
            myCoinColor = watchCoinColor,
            isFreestyle = isFreestyleWatch,
            nowMs = System.currentTimeMillis()
        )
        val completed = watchUpdate.completedShot
        if (completed != null) {
            val eval = carromShotMemoryStore?.recordHumanTrainingShot(
                pendingShot = completed.pendingShot,
                postCounts = completed.postCounts,
                observedPowerHint = completed.observedPowerHint,
                isOpponentSide = completed.shooterSide == com.example.game.WatchTurnSide.OPPONENT_TOP
            )
            if (eval != null) {
                if (eval.learnedTacticLesson != null || eval.learnedFoulLesson != null) {
                    val pbKey = state.activePlaybook?.topicKey ?: "carrom_board_game"
                    val updatedPb = knowledgeStore?.recordStepLearning(
                        topicKey = pbKey,
                        fallbackTopicTitle = state.activePlaybook?.topicTitle ?: "Carrom Board Game",
                        newStrategy = eval.learnedTacticLesson,
                        newFoulLesson = eval.learnedFoulLesson
                    )
                    if (updatedPb != null) {
                        state.activePlaybook = updatedPb
                    }
                }
                state.workingMemory = "Watch & Learn (#${state.carromWatchTrainer.shotsObserved}): ${completed.humanInsightSummary} -> ${eval.outcomeType.name}"
                appendTrace(
                    state.trace,
                    AgentStepLog(
                        stepNumber = state.step,
                        timestampMs = System.currentTimeMillis(),
                        actionType = AgentActionType.WAIT,
                        targetSummary = "Trained Shot #${state.carromWatchTrainer.shotsObserved}: ${eval.outcomeType.name}",
                        reason = "${completed.humanInsightSummary} | ${eval.summaryMessage}",
                        status = StepStatus.COMPLETED,
                        requiresConfirmation = false
                    )
                )
            }
        }

        val nowWatchMs = System.currentTimeMillis()
        val shouldPublishUi = completed != null ||
            watchUpdate.hudStatusText != state.lastWatchHudDetail ||
            (nowWatchMs - state.lastWatchUiUpdateMs >= 650L)

        if (shouldPublishUi) {
            state.lastWatchUiUpdateMs = nowWatchMs
            state.lastWatchHudDetail = watchUpdate.hudStatusText
            val watchPieces = watchObs?.pieces.orEmpty().filter { it.color != CoinColor.STRIKER }
            val watchW = watchPieces.count { it.color == CoinColor.WHITE }
            val watchB = watchPieces.count { it.color == CoinColor.BLACK }
            val watchQ = watchPieces.any { it.color == CoinColor.QUEEN }
            val watchVisual = watchObs?.let { obs ->
                com.example.game.CarromAimVisualData(
                    boardLeft = obs.board.left,
                    boardTop = obs.board.top,
                    boardRight = obs.board.right,
                    boardBottom = obs.board.bottom,
                    strikerCenter = obs.striker?.center ?: obs.topStriker?.center,
                    tracedCoins = watchPieces,
                    targetCoin = null,
                    ghostBall = null,
                    targetPocket = null,
                    dragEnd = null
                )
            }
            _carromTurnHud.value = CarromTurnHudState(
                isActive = true,
                elapsedSec = 0f,
                maxSec = 0f,
                probeNumber = state.carromWatchTrainer.shotsObserved,
                maxProbes = 99,
                scanCount = state.carromWatchTrainer.potsObserved,
                rejectedCount = state.carromWatchTrainer.foulsObserved,
                stage = TurnProbeStage.WATCH_TRAINING,
                detail = watchUpdate.hudStatusText.take(64),
                whiteCount = watchW,
                blackCount = watchB,
                queenVisible = watchQ,
                strikerLocked = watchObs?.striker != null || watchObs?.topStriker != null,
                aimSummary = "Traced W$watchW B$watchB${if (watchQ) " Q1" else ""}",
                aimVisual = watchVisual
            )
            _uiState.value = AgentUiState.Running(
                goal = goal,
                mode = mode,
                currentStep = state.step,
                maxSteps = maxStepsLimit,
                phase = AgentPhase.OBSERVING,
                lastThought = watchUpdate.hudStatusText,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
        }
        val watchDelayMs = if (LiveMediaProjectionCapturer.isLiveActive.value) 100L else 420L
        delay(watchDelayMs)
        state.step++
        return false
        return true
    }
    // Chat observation, zero-API cache lookup, contact matching, and the
    // Carrom rule/colour for this step. (Formerly the middle of the loop
    // body, from extractChatObservation to the Carrom rule vals.)
    private fun buildStepContext(state: TaskRunState, obs: StepObservation): StepAnalysis {
        val goal = state.goal
        val chatObs = ChatTurnGuard.extractChatObservation(
            snapshot = obs.snapshot,
            forceTreatAsMessaging = obs.currentActiveTarget != null &&
                obs.snapshot.packageName.equals(obs.currentActiveTarget.packageName, ignoreCase = true)
        )
        val disableCacheForSpecificSender = obs.currentActiveTarget != null && chatObs.isMessagingApp
        val cacheMatch = if (
            obs.settings.useZeroApiActionCache &&
            !obs.justEvictedStaleCache &&
            !obs.wasWrongApp &&
            !obs.shouldWaitForIncomingNotification &&
            !disableCacheForSpecificSender &&
            !obs.isActiveCarromMatchScreen
        ) {
            actionCacheStore?.findReplayMatch(goal, obs.snapshot)
        } else {
            null
        }

        val lookupPeerName = chatObs.peerName.ifBlank { obs.currentActiveTarget?.senderName.orEmpty() }
        val lookupPkg = if (chatObs.isMessagingApp) {
            obs.snapshot.packageName
        } else {
            obs.currentActiveTarget?.packageName ?: obs.snapshot.packageName
        }
        val matchedContact = ContactProfileStore.matchContactFromList(
            contacts = obs.currentContacts,
            peerName = lookupPeerName,
            packageName = lookupPkg,
            goal = goal,
            recentNotifications = obs.notificationsSnapshot
        )
        val relationshipCheckSummary = NotificationReplyHelper.buildRelationshipCheckSummary(
            target = obs.currentActiveTarget,
            matchedContact = matchedContact
        )
        val savedContactsSummary = ContactProfileStore.formatRosterSummary(obs.currentContacts)

        val activePeerKey = if (chatObs.isMessagingApp) {
            ChatTurnGuard.peerKey(obs.snapshot.packageName, chatObs.peerName)
        } else {
            ""
        }

        // Check if any awaited chat peer replied (via notification or live screen bubble)
        val repliedKeys = state.awaitingRepliesByPeer.entries
            .filter { (_, awaiting) ->
                ChatTurnGuard.didIncomingReplyArrive(chatObs, obs.notificationsSnapshot, awaiting)
            }
            .map { it.key to it.value }
        for ((repliedKey, repliedState) in repliedKeys) {
            state.awaitingRepliesByPeer.remove(repliedKey)
            val who = repliedState.peerName.ifBlank { "contact" }
            state.lastOutcomeHint = "[NEW REPLY DETECTED from $who! Read their new message and respond naturally.] ${state.lastOutcomeHint}"
        }

        var activeAwaitingReply = if (activePeerKey.isNotBlank()) {
            state.awaitingRepliesByPeer[activePeerKey]
                ?: if (chatObs.peerName.isNotBlank()) {
                    state.awaitingRepliesByPeer.values.firstOrNull {
                        it.peerName.equals(chatObs.peerName, ignoreCase = true)
                    }
                } else {
                    null
                }
        } else {
            null
        }

        // Latch the updated transcript signature once our own just-sent bubble renders on screen
        val pendingSentText = state.pendingSentTextForPeer
        if (activeAwaitingReply != null &&
            pendingSentText != null &&
            pendingSentText.first == activePeerKey &&
            chatObs.inChatThread
        ) {
            val latched = activeAwaitingReply.copy(transcriptSignatureAtSend = chatObs.transcriptSignature)
            state.awaitingRepliesByPeer[activePeerKey] = latched
            activeAwaitingReply = latched
            state.pendingSentTextForPeer = null
        }

        // Track consecutive actions that left the UI tree completely unchanged
        if (!obs.isCarromContext &&
            state.prevCompositeScreen != null &&
            state.prevNonWaitActionType != null &&
            state.prevNonWaitActionType != AgentActionType.WAIT
        ) {
            if (state.prevCompositeScreen == obs.currentCompositeScreen) {
                state.consecutiveNoChangeCount++
            } else {
                state.consecutiveNoChangeCount = 0
            }
        } else if (state.prevCompositeScreen != obs.currentCompositeScreen) {
            state.consecutiveNoChangeCount = 0
        }

        if (state.consecutiveNoChangeCount > 0) {
            state.lastOutcomeHint = "[NO SCREEN CHANGE x${state.consecutiveNoChangeCount} after ${state.prevNonWaitActionType?.name} — try another node, scroll, visual coordinates, or BACK!] ${state.lastOutcomeHint}"
        }

        val actionReflectorHint = ActionReflectorHelper.buildReflectionHint(
            prevActionType = state.prevNonWaitActionType,
            prevPackageName = state.prevPackageName,
            prevInputText = state.prevInputText,
            consecutiveNoChangeCount = state.consecutiveNoChangeCount,
            snapshot = obs.snapshot,
            isCarromContext = obs.isCarromContext
        )
        if (actionReflectorHint.contains("REFLECTOR WARNING") &&
            !state.lastOutcomeHint.contains("REFLECTOR WARNING")
        ) {
            state.lastOutcomeHint = "$actionReflectorHint ${state.lastOutcomeHint}"
        }

        val chatGuardHint = buildString {
            if (obs.currentActiveTarget != null) {
                append("ACTIVE NOTIFICATION TARGET LOCK: Open ONLY sender '${obs.currentActiveTarget.senderName}' inside '${obs.currentActiveTarget.appDisplayName}' (incoming message: \"${obs.currentActiveTarget.messageText.take(120)}\"). Do NOT open or reply to any other chat.")
                if (chatObs.isMessagingApp && chatObs.inChatThread && chatObs.peerName.isNotBlank() &&
                    !NotificationReplyHelper.matchesTargetPeer(chatObs.peerName, obs.currentActiveTarget.senderName)
                ) {
                    append(" | WRONG CHAT THREAD OPEN ('${chatObs.peerName}' != '${obs.currentActiveTarget.senderName}'): Press BACK immediately and open '${obs.currentActiveTarget.senderName}'.")
                }
            }
            if (relationshipCheckSummary.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(relationshipCheckSummary)
            }
            if (chatObs.isMessagingApp) {
                if (isNotEmpty()) append(" | ")
                append("App=${obs.snapshot.packageName}, InChatThread=${chatObs.inChatThread}")
                if (chatObs.peerName.isNotBlank()) {
                    append(", ActiveChatPeer='${chatObs.peerName}'")
                }
                if (chatObs.lastMessageText.isNotBlank()) {
                    append(", LatestVisibleBubble='${chatObs.lastMessageText.take(80)}'")
                }
                if (chatObs.inChatThread) {
                    val peerHistoryKey = ChatTurnGuard.peerKey(obs.snapshot.packageName, chatObs.peerName)
                    val historyHint = ChatTurnGuard.buildChatStyleHistoryHint(
                        obs = chatObs,
                        alreadyScrolledHistory = peerHistoryKey in state.historyScrolledPeers
                    )
                    if (historyHint.isNotBlank()) {
                        append(" | $historyHint")
                    }
                }
                if (activeAwaitingReply != null && obs.settings.preventDoubleText) {
                    append(" | ANTI-DOUBLE-TEXT LOCKED: You already sent '${activeAwaitingReply.lastSentText.take(60)}' and NO reply has arrived yet. You MUST return WAIT.")
                }
                if (obs.effectiveSavedContactsOnly && chatObs.inChatThread && matchedContact == null) {
                    append(" | SAVED-CONTACTS-ONLY: '${chatObs.peerName}' is NOT saved; do NOT message them, return WAIT or BACK.")
                }
            }
        }

        val shouldZeroApiWaitForReply = cacheMatch == null &&
            obs.settings.preventDoubleText &&
            activeAwaitingReply != null &&
            chatObs.isMessagingApp &&
            chatObs.inChatThread &&
            (chatObs.transcriptSignature == activeAwaitingReply.transcriptSignatureAtSend ||
                ChatTurnGuard.matchesOwnSentMessage(chatObs.lastMessageText, activeAwaitingReply.lastSentText))

        val effectiveCarromRule = resolveCarromRuleMode(goal, obs.settings.carromRuleMode)
        val isFreestyleMode = effectiveCarromRule == CarromRuleMode.FREESTYLE
        val myCarromCoinColor = if (effectiveCarromRule == CarromRuleMode.BLACK_ONLY) {
            CoinColor.BLACK
        } else {
            CoinColor.WHITE
        }

        return StepAnalysis(
            chatObs = chatObs,
            cacheMatch = cacheMatch,
            matchedContact = matchedContact,
            savedContactsSummary = savedContactsSummary,
            chatGuardHint = chatGuardHint,
            actionReflectorHint = actionReflectorHint,
            effectiveCarromRule = effectiveCarromRule,
            isFreestyleMode = isFreestyleMode,
            myCarromCoinColor = myCarromCoinColor,
            activeAwaitingReply = activeAwaitingReply,
            shouldZeroApiWaitForReply = shouldZeroApiWaitForReply,
            currentActiveTarget = obs.currentActiveTarget
        )
    }
    // Decide what to do this step: the direct branches (wait / back /
    // zero-API wait / cache replay / launcher search) or, when none
    // matches, the Carrom solver / AI planner. (Formerly the
    // launcherDecision + rawPlannedAction if-chain.)
    private suspend fun decidePlannedAction(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis
    ): ActionDecision {
        val cacheMatch = analysis.cacheMatch
        var nextLauncherStageTag: String? = null
        val goal = state.goal
        val launcherDecision = if (
            !obs.shouldWaitForIncomingNotification &&
            !obs.wasWrongApp &&
            !analysis.shouldZeroApiWaitForReply &&
            cacheMatch == null &&
            !obs.isActiveCarromMatchScreen &&
            obs.isOnLauncher
        ) {
            LauncherSearchHelper.planNextLauncherSearchStep(
                goal = goal,
                snapshot = obs.snapshot,
                alreadyOpenedDrawer = state.launcherOpenedDrawer,
                alreadyTappedSearch = state.launcherTappedSearch,
                alreadyTypedSearch = state.launcherTypedSearch,
                targetAppOverride = obs.targetAppOverride
            )
        } else {
            null
        }

        val directAction: PlannedAction? = if (obs.shouldWaitForIncomingNotification) {
            obs.snapshot.screenshot?.recycle()
            PlannedAction(
                actionType = AgentActionType.WAIT,
                targetNodeId = null,
                x = null,
                y = null,
                textToType = null,
                waitSeconds = obs.settings.monitorIntervalSec,
                memoryNote = null,
                reason = "Waiting for incoming message notification (will open specific sender ID when message arrives)...",
                requiresConfirmation = false
            )
        } else if (obs.wasWrongApp) {
            obs.snapshot.screenshot?.recycle()
            state.lastOutcomeHint = "[WRONG APP (${obs.snapshot.packageName}) — purged wrong state.step from cache and pressing BACK to return to App Drawer Search.] ${state.lastOutcomeHint}"
            PlannedAction(
                actionType = AgentActionType.BACK,
                targetNodeId = null,
                x = null,
                y = null,
                textToType = null,
                waitSeconds = null,
                memoryNote = "Opened wrong app (${obs.snapshot.packageName}); backing out to search exact app name.",
                reason = "Wrong app (${obs.snapshot.packageName}) — pressing Back & purging wrong cache entry",
                requiresConfirmation = false
            )
        } else if (analysis.shouldZeroApiWaitForReply) {
            obs.snapshot.screenshot?.recycle()
            val waitTarget = analysis.chatObs.peerName.ifBlank { analysis.activeAwaitingReply?.peerName.orEmpty() }.ifBlank { "recipient" }
            PlannedAction(
                actionType = AgentActionType.WAIT,
                targetNodeId = null,
                x = null,
                y = null,
                textToType = null,
                waitSeconds = obs.settings.monitorIntervalSec,
                memoryNote = null,
                reason = "Anti-Double-Text Guard: waiting for $waitTarget to reply before sending another message.",
                requiresConfirmation = false
            )
        } else if (cacheMatch != null) {
            state.carromOfflineWaits = 0
            state.didSlideCarromThisStep = false
            state.carromTurnMemory.resetTurn()
            obs.snapshot.screenshot?.recycle()
            state.lastReplayEntry = cacheMatch.entry.cacheKey to obs.currentCompositeScreen
            cacheMatch.action
        } else if (launcherDecision != null) {
            state.carromOfflineWaits = 0
            state.didSlideCarromThisStep = false
            state.carromTurnMemory.resetTurn()
            obs.snapshot.screenshot?.recycle()
            nextLauncherStageTag = launcherDecision.stageTag
            launcherDecision.action
        } else {
            null
        }
        if (directAction != null) {
            return ActionDecision.Action(
                action = directAction,
                usedLocalCarromSolver = false,
                nextLauncherStageTag = nextLauncherStageTag,
                nextPendingCarromShotCandidate = null,
                aiPreCarromCounts = null,
                launcherDecision = launcherDecision
            )
        }
        return decideCarromOrAiAction(state, obs, analysis, launcherDecision)
    }
    private suspend fun decideCarromOrAiAction(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        launcherDecision: LauncherSearchDecision?
    ): ActionDecision {
        state.didSlideCarromThisStep = false
        state.isCarromOfflineWait = false
        val carromResult = solveCarromAction(state, obs, analysis)
        val localCarromAction = carromResult.action
        if (localCarromAction != null) {
            obs.snapshot.screenshot?.recycle()
            return ActionDecision.Action(
                action = localCarromAction,
                usedLocalCarromSolver = carromResult.usedLocalCarromSolver,
                nextLauncherStageTag = null,
                nextPendingCarromShotCandidate = carromResult.nextPendingCarromShotCandidate,
                aiPreCarromCounts = carromResult.aiPreCarromCounts,
                launcherDecision = launcherDecision
            )
        }
        return planWithAiAction(state, obs, analysis, carromResult, launcherDecision)
    }
    // Local zero-API Carrom solver for this step. (Formerly the
    // `val localCarromAction` if-expression.)
    private suspend fun solveCarromAction(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis
    ): CarromActionResult {

        val carromSolverActive = obs.settings.carromPrecisionSolver &&
            obs.isActiveCarromMatchScreen &&
            obs.interactiveNodeCount < 50 &&
            obs.snapshot.screenshot != null
        if (!carromSolverActive) {
        state.carromTurnMemory.resetTurn()
        _carromTurnHud.value = null
        if (obs.isCarromContext && obs.snapshot.screenshot == null) {
            state.lastOutcomeHint = "[CARROM SCREENSHOT MISSING (${obs.snapshot.captureNote}) — Turn ON Multimodal Vision Screenshot in Settings and ensure game allows screen capture.] ${state.lastOutcomeHint}"
        }
            return CarromActionResult(null, false, null, null)
        }
        val nowCarromMs = System.currentTimeMillis()
        val isLiveStream = LiveMediaProjectionCapturer.isLiveActive.value
        val currentCarromMem = _carromMemory.value
        val allowRepositionByBudget = !state.carromTurnMemory.isTurnActive ||
            state.carromTurnMemory.canAffordAnotherSliderMove(nowCarromMs, isLiveStream)

        val outcome = CarromVisionDetector.analyzeAndSolve(
            screenshot = obs.snapshot.screenshot,
            screenWidth = obs.snapshot.screenWidth,
            screenHeight = obs.snapshot.screenHeight,
            myCoinColor = analysis.myCarromCoinColor,
            consecutiveOfflineWaits = state.carromOfflineWaits,
            shotMemory = currentCarromMem,
            allowStrikerRepositioning = allowRepositionByBudget,
            usePhysicsRollout = obs.settings.usePhysicsRollout,
            useSubPixelRefinement = obs.settings.useSubPixelCircleRefinement,
            isFreestyle = analysis.isFreestyleMode,
            queenPendingCover = state.queenPendingCover,
            isLiveStream = isLiveStream,
            isTurnActive = state.carromTurnMemory.isTurnActive,
            difficulty = obs.settings.carromDifficulty
        )

        // 5s rule (Fix 1): measure this detect+solve
        // cycle so the probe-affordability reserve
        // grows with the REAL cycle time — a probe is
        // only dispatched when the next cycle can
        // finish inside the 5s budget.
        state.carromTurnMemory.recordCycleDuration(System.currentTimeMillis() - nowCarromMs)
        evaluatePreviousCarromShot(state, obs, outcome, currentCarromMem, nowCarromMs)
        return handleCarromOutcome(
            state, obs, analysis, outcome, currentCarromMem, nowCarromMs,
            isLiveStream, allowRepositionByBudget
        )
    }
    // Fresh-rack detection, previous-shot outcome evaluation (power /
    // deflection / scratch learning), and the queen-cover check.
    // (Formerly the block before `when (outcome)` in the Carrom branch.)
    private suspend fun evaluatePreviousCarromShot(
        state: TaskRunState,
        obs: StepObservation,
        outcome: CarromVisionOutcome,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long
    ) {

        // New-match detection (fresh rack): the board is
        // full again (9+9+1 = 19 coins; >= 18 tolerates
        // one detection miss) after having been seen
        // depleted, and shots were played in the previous
        // match. Two things happen, once per match:
        // 1. Per-match calibration resets (friction /
        //    power / jaw buffer / slider gain back to
        //    neutral) so the previous board's tuning
        //    never leaks into the new board.
        // 2. Auto-Snap profile match: the live board
        //    geometry is matched against the saved named
        //    profiles; a matching board is activated
        //    (its own learned table + calibration).
        //    A board that matches NOTHING keeps
        //    auto-detection — never a wrong profile.
        val outcomePieces = when (outcome) {
            is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
            is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
            is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
        }
        val totalCoinsOnBoard = outcomePieces.count { it.color != CoinColor.STRIKER }
        if (totalCoinsOnBoard >= 18) {
            if (currentCarromMem.matchCalibration.shotsThisMatch > 0 && state.carromSawDepletedBoard) {
                state.carromSawDepletedBoard = false
                state.queenPendingCover = false
                val liveGeometry = obs.snapshot.screenshot?.let { bitmap ->
                    runCatching {
                        CarromVisionDetector.autoDetectCalibrationFromBitmap(bitmap)
                    }.getOrNull()
                }
                val matchedProfile = liveGeometry?.let {
                    BoardProfileMatcher.matchProfile(it, currentCarromMem.boardProfiles)
                }
                val store = carromShotMemoryStore
                if (store != null) {
                    scope.launch {
                        if (matchedProfile != null &&
                            matchedProfile.profile.profileName != currentCarromMem.activeBoardProfileName
                        ) {
                            store.activateBoardProfile(matchedProfile.profile.profileName)
                        } else {
                            store.resetMatchCalibration()
                        }
                    }
                }
            }
        } else if (totalCoinsOnBoard in 1..17) {
            state.carromSawDepletedBoard = true
        }

        // Evaluate the previous Carrom shot once coins have stopped moving
        val isMovingWait = outcome is CarromVisionOutcome.WaitStillnessOrTurn &&
            outcome.reason.contains("motion", ignoreCase = true)
        val prevShot = state.pendingCarromShot
        if (prevShot != null && !isMovingWait) {
            val settledPieces = when (outcome) {
                is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
                is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
                is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
            }
            val postCounts = if (settledPieces.isNotEmpty()) {
                CarromShotMemoryStore.countBoardPieces(settledPieces, prevShot.myCoinColor)
            } else {
                CarromVisionDetector.inspectBoardCounts(
                    screenshot = obs.snapshot.screenshot,
                    screenWidth = obs.snapshot.screenWidth,
                    screenHeight = obs.snapshot.screenHeight,
                    myCoinColor = prevShot.myCoinColor,
                    calibration = currentCarromMem.boardCalibration
                )
            }
            val strikerStayedPut = outcome is CarromVisionOutcome.SolvedShot &&
                outcome.detectedStriker != null &&
                state.lastShotAttemptStrikerX != null &&
                kotlin.math.abs(outcome.detectedStriker.center.x - state.lastShotAttemptStrikerX!!) <= 16f
            val coinsUnmoved = outcome is CarromVisionOutcome.SolvedShot &&
                postCounts == prevShot.preCounts &&
                state.lastShotPrePieces.isNotEmpty() &&
                state.lastShotPrePieces.filter { it.color != CoinColor.STRIKER }.all { pre ->
                    outcome.boardPieces.any { cur ->
                        cur.color == pre.color &&
                            kotlin.math.hypot(cur.center.x - pre.center.x, cur.center.y - pre.center.y) <= outcome.board.coinRadiusPx * 0.55f
                    }
                }
            if (strikerStayedPut && coinsUnmoved && state.consecutiveUnregisteredCarromShots < 3) {
                state.pendingCarromShot = null
                state.consecutiveUnregisteredCarromShots++
                state.lastOutcomeHint = "[Carrom shot drag left board static (attempt #${state.consecutiveUnregisteredCarromShots}/3) — recalibrating striker grab Y/duration] ${state.lastOutcomeHint}"
            } else {
                state.consecutiveUnregisteredCarromShots = 0
                state.lastShotAttemptStrikerX = null
                state.lastShotPrePieces = emptyList()
                if (postCounts != null) {
                    // Vision-error guard (miss-cause
                    // learning): record the outcome only
                    // when the post-shot detection is
                    // stable across 2 frames — an
                    // unstable detection would corrupt
                    // the learned table with wrong
                    // counts. Retried next cycle (off
                    // the turn clock, never delaying
                    // the next shot's 5s window);
                    // dropped unrecorded after 5
                    // unstable cycles.
                    val postStable = CarromVisionDetector.isDetectionStableAcrossTwoFrames
                    if (!postStable) {
                        state.pendingEvalStaleCycles++
                        if (state.pendingEvalStaleCycles > 5) {
                            state.pendingCarromShot = null
                            state.pendingEvalStaleCycles = 0
                            state.lastOutcomeHint = "[Carrom outcome not recorded — vision never stabilised across 2 frames] ${state.lastOutcomeHint}"
                        }
                    } else {
                        state.pendingEvalStaleCycles = 0
                        // Miss-cause geometry: only when
                        // the pre-shot detection was ALSO
                        // stable across 2 frames (frozen
                        // in the record at fire time) and
                        // this outcome carries the board.
                        // Otherwise counts-only recording
                        // (coarse rules, UNKNOWN cause).
                        val missInput: MissCauseInput? =
                            if (prevShot.hasSolverGeometry && prevShot.preDetectionStable &&
                                outcome is CarromVisionOutcome.SolvedShot && settledPieces.isNotEmpty()
                            ) {
                                val board = outcome.board
                                val prePx = Point2D(
                                    board.left + prevShot.coinNormX * board.width,
                                    board.top + prevShot.coinNormY * board.height
                                )
                                val postPiece = settledPieces
                                    .filter { it.color == prevShot.targetCoinColor }
                                    .minByOrNull { kotlin.math.hypot(it.center.x - prePx.x, it.center.y - prePx.y) }
                                    ?.takeIf { kotlin.math.hypot(it.center.x - prePx.x, it.center.y - prePx.y) <= board.width * 0.25f }
                                val postNorm = postPiece?.let {
                                    Point2D(
                                        ((it.center.x - board.left) / board.width).coerceIn(0f, 1f),
                                        ((it.center.y - board.top) / board.height).coerceIn(0f, 1f)
                                    )
                                }
                                val pocketPx = board.pockets[prevShot.pocketIndex.coerceIn(0, 3)]
                                MissCauseInput(
                                    preCoinNorm = Point2D(prevShot.coinNormX, prevShot.coinNormY),
                                    postCoinNorm = postNorm,
                                    pocketNorm = Point2D(
                                        ((pocketPx.x - board.left) / board.width).coerceIn(0f, 1f),
                                        ((pocketPx.y - board.top) / board.height).coerceIn(0f, 1f)
                                    ),
                                    coinRadiusNorm = (board.coinRadiusPx / board.width).coerceAtLeast(0.005f),
                                    pocketRadiusNorm = (board.pocketRadiusPx / board.width).coerceAtLeast(0.005f)
                                )
                            } else null
                        val eval = carromShotMemoryStore?.recordShotOutcome(prevShot, postCounts, missCauseInput = missInput)
                        state.pendingCarromShot = null
                        if (eval?.outcomeType == CarromShotOutcomeType.POCKETED_QUEEN &&
                            !prevShot.isFreestyle &&
                            postCounts.myCount > 0
                        ) {
                            state.queenPendingCover = true
                        } else {
                            state.queenPendingCover = false
                        }
                        if (eval != null) {
                            state.lastOutcomeHint = "${eval.summaryMessage} ${state.lastOutcomeHint}"
                            state.workingMemory = eval.summaryMessage
                            if (eval.learnedTacticLesson != null || eval.learnedFoulLesson != null) {
                                val pbKey = state.activePlaybook?.topicKey ?: "carrom_board_game"
                                val updatedPb = knowledgeStore?.recordStepLearning(
                                    topicKey = pbKey,
                                    fallbackTopicTitle = state.activePlaybook?.topicTitle ?: "Carrom Board Game",
                                    newStrategy = eval.learnedTacticLesson,
                                    newFoulLesson = eval.learnedFoulLesson
                                )
                                if (updatedPb != null) {
                                    state.activePlaybook = updatedPb
                                }
                            }
                        }
                    }
                }
            }
        } else if (prevShot != null && isMovingWait) {
            state.consecutiveUnregisteredCarromShots = 0
        }
        if (state.queenPendingCover) {
            val visiblePieces = when (outcome) {
                is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
                is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
                is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
            }
            if (visiblePieces.any { it.color == CoinColor.QUEEN }) {
                state.queenPendingCover = false
            }
        }
    }
    // Dispatch on the Carrom vision outcome. (Formerly `when (outcome)`
    // in the Carrom branch.)
    private suspend fun handleCarromOutcome(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        outcome: CarromVisionOutcome,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        isLiveStream: Boolean,
        allowRepositionByBudget: Boolean
    ): CarromActionResult {
        var aiPreCarromCounts: BoardPieceCounts? = null
        // Anchor the 5s turn clock at the FIRST sighting of the turn glow,
        // not at the first solved shot: the game clock starts when the turn
        // starts, and motion/turn waits before the first solve used to burn
        // the whole window while our clock still read 0. ensureTurnStarted
        // only sets the clock once, so later cycles keep the first sighting.
        val glowSeen = when (outcome) {
            is CarromVisionOutcome.SolvedShot -> outcome.turnBanner
            is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.turnGlowDetected
            is CarromVisionOutcome.NeedsAiVision -> outcome.turnGlowDetected
        }
        if (glowSeen) {
            state.carromTurnMemory.ensureTurnStarted(nowCarromMs)
        }
        return when (outcome) {
            is CarromVisionOutcome.WaitStillnessOrTurn -> {
        state.carromOfflineWaits++
        state.isCarromOfflineWait = true
        state.pendingSliderVerify = null
        if (!state.carromTurnMemory.isTurnActive) {
            state.carromTurnMemory.resetTurn()
            // Diagnostic HUD: show the wait step on screen instead
            // of hiding the HUD, so a "bot does nothing" report can
            // be diagnosed from the phone (outcome + reason visible).
            _carromTurnHud.value = state.carromTurnMemory.buildHudState(
                nowMs = nowCarromMs,
                stage = TurnProbeStage.WAITING,
                detail = outcome.reason.take(90),
                tracedPieces = outcome.boardPieces,
                forceActive = true
            )
        }
        val waitAction = PlannedAction(
            actionType = AgentActionType.WAIT,
            targetNodeId = null,
            x = null,
            y = null,
            textToType = null,
            waitSeconds = 1,
            memoryNote = null,
            reason = outcome.reason,
            requiresConfirmation = false
        )
                CarromActionResult(waitAction, false, null, null)
            }
            is CarromVisionOutcome.SolvedShot -> {
                handleSolvedShot(
                    state, obs, analysis, outcome, currentCarromMem,
                    nowCarromMs, isLiveStream, allowRepositionByBudget
                )
            }
            is CarromVisionOutcome.NeedsAiVision -> {
        state.carromOfflineWaits = 0
        state.pendingSliderVerify = null
        state.carromTurnMemory.resetTurn()
        // Diagnostic HUD: keep the "needs AI vision" step visible
        // on screen with its diagnostic instead of hiding the HUD.
        _carromTurnHud.value = state.carromTurnMemory.buildHudState(
            nowMs = nowCarromMs,
            stage = TurnProbeStage.NEEDS_AI,
            detail = outcome.diagnostic.take(90),
            tracedPieces = outcome.boardPieces,
            forceActive = true
        )
        state.lastOutcomeHint = "[Carrom Local Vision: ${outcome.diagnostic}] ${state.lastOutcomeHint}"
        if (outcome.boardPieces.isNotEmpty()) {
            aiPreCarromCounts = CarromShotMemoryStore.countBoardPieces(outcome.boardPieces, analysis.myCarromCoinColor)
        }
                CarromActionResult(null, false, null, aiPreCarromCounts)
            }
        }
    }
    private suspend fun handleSolvedShot(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        isLiveStream: Boolean,
        allowRepositionByBudget: Boolean
    ): CarromActionResult {
        return when (val probeResult = probeCarromShot(
            state, obs, analysis, outcome, currentCarromMem, nowCarromMs,
            isLiveStream, allowRepositionByBudget
        )) {
            is CarromShotProbeResult.Slide ->
                CarromActionResult(probeResult.action, true, null, null)
            is CarromShotProbeResult.Shoot -> {
                val shotResult = fireCarromShot(
                    state, obs, analysis, outcome, currentCarromMem,
                    nowCarromMs, probeResult.probe
                )
                CarromActionResult(shotResult.action, true, shotResult.pendingShot, null)
            }
        }
    }
    // Probe phase of a solved Carrom shot: in-place solve, striker sweep,
    // and (when needed) the slider-probe drag. Returns the slide action
    // or the context the fire phase needs. (Formerly the first half of
    // the SolvedShot branch.)
    private suspend fun probeCarromShot(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        isLiveStream: Boolean,
        allowRepositionByBudget: Boolean
    ): CarromShotProbeResult {

        state.carromOfflineWaits = 0
        state.carromTurnMemory.ensureTurnStarted(nowCarromMs)

        val striker = outcome.detectedStriker
        val calProfile = currentCarromMem.boardCalibration.takeIf { it.enabled }
        val sliderKnob = outcome.detectedSliderKnob ?: run {
            if (striker != null) {
                val strikerMargin = outcome.board.strikerRadiusPx * 2.1f
                val playableSpan = (outcome.board.width - strikerMargin * 2f).coerceAtLeast(40f)
                val strikerNorm = ((striker.center.x - (outcome.board.left + strikerMargin)) / playableSpan)
                    .coerceIn(0f, 1f)
                if (calProfile != null) {
                    val sliderLeftPx = calProfile.sliderTrackLeftNorm * obs.snapshot.screenWidth
                    val sliderRightPx = calProfile.sliderTrackRightNorm * obs.snapshot.screenWidth
                    val sliderY = calProfile.sliderTrackYNorm * obs.snapshot.screenHeight
                    com.example.game.Point2D(
                        sliderLeftPx + strikerNorm * (sliderRightPx - sliderLeftPx),
                        sliderY
                    )
                } else {
                    val estLeftPx = outcome.board.left + outcome.board.width * 0.16f
                    val estRightPx = outcome.board.right - outcome.board.width * 0.16f
                    val estSliderY = (outcome.board.bottom + outcome.board.height * 0.125f)
                        .coerceAtMost((obs.snapshot.screenHeight - 48f).coerceAtLeast(outcome.board.bottom + 24f))
                    com.example.game.Point2D(
                        estLeftPx + strikerNorm * (estRightPx - estLeftPx),
                        estSliderY
                    )
                }
            } else {
                null
            }
        }

        // Measure slider gain feedback from previous striker slide
        var observedGainThisStep: Float? = null
        val verifyState = state.pendingSliderVerify
        if (verifyState != null && striker != null) {
            val actualDelta = striker.center.x - verifyState.preStrikerX
            if (kotlin.math.abs(verifyState.commandedDeltaX) > 18f &&
                kotlin.math.abs(actualDelta) > 8f
            ) {
                val observedGain = (actualDelta / verifyState.commandedDeltaX).coerceIn(0.65f, 1.45f)
                observedGainThisStep = observedGain
                carromShotMemoryStore?.updateSliderGain(observedGain)
            }
        }

        // Save pre-slide / post-slide scan into Turn Memory BEFORE selecting next action
        state.carromTurnMemory.recordObservation(
            nowMs = nowCarromMs,
            pieces = outcome.boardPieces,
            striker = striker,
            sliderKnob = sliderKnob,
            evaluatedSolution = outcome.solution,
            observedSliderGain = observedGainThisStep
        )

        // 5s hard rule: once the turn clock passes 4.5s, no more
        // probing or correcting — this cycle MUST fire (best sure
        // shot remembered so far, else a foul-safe safety shot),
        // with no pre-shot pause.
        val deadlineFire = state.carromTurnMemory.mustFireNow(nowCarromMs)

        val consensusPieces = state.carromTurnMemory.buildConsensusPieces(
            latestPieces = outcome.boardPieces,
            coinRadiusPx = outcome.board.coinRadiusPx
        )
        val fusedPiecesWithStriker = if (striker != null) {
            consensusPieces + striker
        } else {
            consensusPieces
        }

        // Step 1 of Turn: Full-board pre-scan builds top-4 physics-ranked baseline X queue
        if (!state.carromTurnMemory.isProbeQueueInitialized) {
            val rankedSpots = CarromGeometrySolver.buildRankedBaselineProbeQueue(
                board = outcome.board,
                pieces = fusedPiecesWithStriker,
                myCoinColor = analysis.myCarromCoinColor,
                existingStriker = striker,
                shotMemory = currentCarromMem,
                maxProbes = CarromTurnMemory.MAX_BASELINE_PROBES,
                isFreestyle = analysis.isFreestyleMode,
                queenPendingCover = state.queenPendingCover
            )
            state.carromTurnMemory.initializeProbeQueueIfNeeded(rankedSpots)
        }

        val turnGain = state.carromTurnMemory.effectiveTurnSliderGain(
            currentCarromMem.matchCalibration.sliderGain
        )
        val canAffordSlide = sliderKnob != null &&
            striker != null &&
            state.carromTurnMemory.canAffordAnotherSliderMove(nowCarromMs, isLiveStream)

        // Step 2: Physics-verify the current striker spot (collision-assisted pots WITH 0 foul count as Sure Shots!)
        val inPlaceSol = if (striker != null) {
            CarromGeometrySolver.solveBestShot(
                board = outcome.board,
                pieces = fusedPiecesWithStriker,
                myCoinColor = analysis.myCarromCoinColor,
                screenWidth = obs.snapshot.screenWidth,
                screenHeight = obs.snapshot.screenHeight,
                existingStriker = striker,
                canRepositionStriker = false,
                shotMemory = currentCarromMem,
                usePhysicsRollout = obs.settings.usePhysicsRollout,
                isFreestyle = analysis.isFreestyleMode,
                queenPendingCover = state.queenPendingCover,
                difficulty = obs.settings.carromDifficulty
            )
        } else {
            null
        }

        val isVerifiedSureHere = inPlaceSol != null && inPlaceSol.isSureShot

        val minCorrectionPx = (outcome.board.width * 0.032f).coerceIn(18f, 34f)
        val needsClosedLoopCorrection = !deadlineFire &&
            !isVerifiedSureHere &&
            canAffordSlide &&
            state.carromTurnMemory.remainingMs(nowCarromMs) > 2_300L &&
            obs.settings.useClosedLoopSliderVerify &&
            verifyState != null &&
            verifyState.correctionAttempts < 1 &&
            striker != null &&
            kotlin.math.abs(verifyState.targetStrikerX - striker.center.x) > minCorrectionPx &&
            !state.carromTurnMemory.isSliderStalledOrOscillating(verifyState.targetStrikerX, striker.center.x)

        // Striker sweep (try-and-see): simulate
        // every baseline position for the current
        // best coin+pocket, once per board layout
        // (key changes when any piece moves).
        // The probe step IS this test — slide to
        // the first position that pots with sure
        // confidence; fall back to the next
        // untested probe spot when none pots.
        var sweepFirstPotsX: Float? = null
        var sweepHudLine: String? = null
        if (!deadlineFire && !isVerifiedSureHere && !needsClosedLoopCorrection && striker != null && outcome.solution != null) {
            val sweepKey = buildString {
                val sol = outcome.solution
                append(sol.targetCoin.center.x.toInt()); append(','); append(sol.targetCoin.center.y.toInt())
                append('|'); append(sol.pocketIndex)
                fusedPiecesWithStriker.sortedBy { it.center.x }.forEach { p ->
                    append('|'); append(p.center.x.toInt()); append(':'); append(p.center.y.toInt())
                }
            }
            if (sweepKey != state.carromSweepKey) {
                state.carromSweepKey = sweepKey
                state.carromSweepEntries = CarromGeometrySolver.sweepStrikerAim(
                    board = outcome.board,
                    coin = outcome.solution.targetCoin,
                    pocketIndex = outcome.solution.pocketIndex,
                    pieces = fusedPiecesWithStriker,
                    myCoinColor = analysis.myCarromCoinColor,
                    screenWidth = obs.snapshot.screenWidth,
                    screenHeight = obs.snapshot.screenHeight,
                    existingStriker = striker,
                    shotMemory = currentCarromMem,
                    usePhysicsRollout = obs.settings.usePhysicsRollout,
                    isFreestyle = analysis.isFreestyleMode,
                    maxPositions = 9
                )
            }
            if (state.carromSweepEntries.isNotEmpty()) {
                val pots = state.carromSweepEntries.filter {
                    it.outcome == CarromGeometrySolver.StrikerSweepOutcome.POTS && it.solution?.isSureShot == true
                }
                sweepFirstPotsX = pots.firstOrNull()?.strikerPos?.x
                val potsXs = pots.joinToString(",") { ((it.strikerNormX * 100).toInt()).toString() }
                val missN = state.carromSweepEntries.count { it.outcome == CarromGeometrySolver.StrikerSweepOutcome.MISSES }
                val foulN = state.carromSweepEntries.count { it.outcome == CarromGeometrySolver.StrikerSweepOutcome.FOUL_RISK }
                sweepHudLine = "সুইপ ${state.carromSweepEntries.size}টি: পকেটে যাবে ${potsXs.ifEmpty { "—" }} • যাবে না $missN • ফাউল-ঝুঁকি $foulN"
            }
        }

        var nextProbeTargetX: Float? = null
        var returningToBestProbe = false
        if (!deadlineFire && !isVerifiedSureHere && !needsClosedLoopCorrection && striker != null) {
            // Reject current spot because collision missed/fouled or no Sure Shot found here
            val rejectWhy = when {
                inPlaceSol == null -> "no-line"
                inPlaceSol.rejectedDueToCollisionOrFoul -> "collision-miss/foul"
                else -> "unverified"
            }
            state.carromTurnMemory.markSpotRejected(striker.center.x, rejectWhy)
            if (canAffordSlide) {
                // Prefer verified sweep spot first, else the full-baseline
                // solver's winning strikerPos.x if not yet tried, else the
                // next ranked untested probe spot.
                val solvedTargetX = outcome.solution
                    ?.takeIf { sol ->
                        !sol.isSafetyShot &&
                            !sol.rejectedDueToCollisionOrFoul &&
                            (sol.isSureShot || sol.confidenceScore >= 0.45f) &&
                            kotlin.math.abs(sol.strikerPos.x - striker.center.x) > minCorrectionPx &&
                            !state.carromTurnMemory.isSpotVisitedOrRejected(sol.strikerPos.x)
                    }
                    ?.strikerPos?.x
                nextProbeTargetX = sweepFirstPotsX
                    ?: solvedTargetX
                    ?: state.carromTurnMemory.nextUntestedProbeSpot(striker.center.x)?.strikerX
                if (nextProbeTargetX == null) {
                    val returnX = state.carromTurnMemory.bestEvaluatedSpotToReturnTo(
                        currentStrikerX = striker.center.x,
                        currentInPlaceConfidence = inPlaceSol?.confidenceScore ?: 0f,
                        minShiftPx = minCorrectionPx
                    )
                    if (returnX != null) {
                        nextProbeTargetX = returnX
                        returningToBestProbe = true
                    }
                }
            }
        }

        val slideTargetX: Float? = when {
            isVerifiedSureHere -> null
            needsClosedLoopCorrection -> verifyState?.targetStrikerX
            nextProbeTargetX != null &&
                striker != null &&
                !state.carromTurnMemory.isSliderStalledOrOscillating(nextProbeTargetX, striker.center.x) ->
                nextProbeTargetX
            else -> null
        }

        val slideAction: PlannedAction? = if (slideTargetX != null && striker != null && sliderKnob != null) {
            if (returningToBestProbe) {
                state.carromTurnMemory.markReturnedToBestSpot()
            }
            val rawDelta = slideTargetX - striker.center.x
            val trackScale = if (calProfile != null &&
                calProfile.sliderTrackRightNorm > calProfile.sliderTrackLeftNorm + 0.1f
            ) {
                val calSliderSpan = (calProfile.sliderTrackRightNorm - calProfile.sliderTrackLeftNorm) * obs.snapshot.screenWidth
                val strikerMargin = outcome.board.strikerRadiusPx * 2.1f
                val baselineSpan = (outcome.board.width - strikerMargin * 2f).coerceAtLeast(40f)
                (calSliderSpan / baselineSpan).coerceIn(0.45f, 1.25f)
            } else {
                0.85f
            }
            val maxSliderDelta = (outcome.board.width * 0.72f)
                .coerceIn(380f, (obs.snapshot.screenWidth * 0.85f).coerceAtLeast(380f))
            val calibratedDelta = ((rawDelta * trackScale) / turnGain).coerceIn(-maxSliderDelta, maxSliderDelta)
            val targetKnobEndX = (sliderKnob.x + calibratedDelta)
                .toInt()
                .coerceIn(28, (obs.snapshot.screenWidth - 28).coerceAtLeast(29))

            state.didSlideCarromThisStep = true
            state.movedStrikerThisTurn = true
            state.carromTurnMemory.recordSliderMoveDispatched(striker.center.x)
            state.pendingSliderVerify = PendingSliderVerify(
                targetStrikerX = slideTargetX,
                preStrikerX = striker.center.x,
                commandedDeltaX = (targetKnobEndX - sliderKnob.x.toInt()).toFloat(),
                correctionAttempts = if (needsClosedLoopCorrection) (verifyState?.correctionAttempts ?: 0) + 1 else 0
            )
            val remSec = state.carromTurnMemory.remainingMs(nowCarromMs) / 1000f
            val previewSol = inPlaceSol ?: outcome.solution
            val previewAim = "${previewSol.targetCoin.color.name} -> ${CarromMemoryData.pocketName(previewSol.pocketIndex)} (${previewSol.cutAngleDeg.toInt()}°)" +
                (sweepHudLine?.let { " • $it" } ?: "")
            _carromTurnHud.value = state.carromTurnMemory.buildHudState(
                nowMs = nowCarromMs,
                stage = TurnProbeStage.PROBING_SLIDER,
                detail = "Probe ${state.carromTurnMemory.sliderMovesThisTurn}/${state.carromTurnMemory.totalPlannedProbes.coerceAtLeast(1)} (${state.carromTurnMemory.rejectedProbeCount} rej)",
                board = outcome.board,
                tracedPieces = fusedPiecesWithStriker,
                strikerPos = striker.center,
                solution = previewSol,
                aimSummary = previewAim
            )
            PlannedAction(
                actionType = AgentActionType.DRAG,
                targetNodeId = null,
                x = sliderKnob.x.toInt(),
                y = sliderKnob.y.toInt(),
                endX = targetKnobEndX,
                endY = sliderKnob.y.toInt(),
                // Human-like timing with reliable slider lock-on (avoid
                // ultra-fast flicks that the game treats as a tap).
                durationMs = (if (kotlin.math.abs(calibratedDelta) < 120f) 260L else 330L) +
                    Random.Default.nextLong(-20L, 26L),
                textToType = null,
                waitSeconds = null,
                memoryNote = "Turn Memory (${state.carromTurnMemory.scanCount} scans, ${state.carromTurnMemory.rejectedProbeCount} rejected, rem=${String.format(java.util.Locale.US, "%.1f", remSec)}s): probing spot #${state.carromTurnMemory.sliderMovesThisTurn}/${CarromTurnMemory.MAX_BASELINE_PROBES}",
                reason = "Probe #${state.carromTurnMemory.sliderMovesThisTurn}/${state.carromTurnMemory.totalPlannedProbes.coerceAtLeast(1)}: sliding striker to find Sure Shot (${String.format(java.util.Locale.US, "%.1f", remSec)}s left)",
                requiresConfirmation = false
            )
        } else {
            null
        }
        if (slideAction != null) {
            return CarromShotProbeResult.Slide(slideAction)
        }
        return CarromShotProbeResult.Shoot(
            CarromShotProbe(
                striker = striker,
                inPlaceSol = inPlaceSol,
                isVerifiedSureHere = isVerifiedSureHere,
                deadlineFire = deadlineFire,
                consensusPieces = consensusPieces,
                fusedPiecesWithStriker = fusedPiecesWithStriker
            )
        )
    }
    // Fire phase of a solved Carrom shot: pick the final solution (sure
    // shot / deadline fire / safety), hold the safety when a sure shot
    // may still arrive, then dispatch the drag. (Formerly the second
    // half of the SolvedShot branch.)
    private suspend fun fireCarromShot(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        probe: CarromShotProbe
    ): CarromShotResult {
        var pendingShot: PendingCarromShotRecord? = null

        // Step 3: Sure Shot LOCKED (including clean carom pots),
        // or the 4.5s hard fire deadline (best sure shot
        // remembered, else a foul-safe safety shot), or the
        // strict deadline fallback (sure / break / safety only).
        state.pendingSliderVerify = null
        val didSlideBeforeThisShot = state.movedStrikerThisTurn
        state.movedStrikerThisTurn = false

        val finalSol = if (probe.isVerifiedSureHere && probe.inPlaceSol != null) {
            probe.inPlaceSol
        } else if (probe.deadlineFire) {
            // Past the 4.5s fire deadline: do NOT re-solve (that
            // costs time) — fire the best sure shot remembered
            // from this turn, else a foul-safe safety shot.
            // Never a low-confidence gamble pot.
            state.carromTurnMemory.bestSureShotThisTurn(
                currentStrikerX = probe.striker?.center?.x,
                maxShiftPx = outcome.board.strikerRadiusPx * 1.6f
            )
                ?: CarromGeometrySolver.solveSafetyShot(
                    board = outcome.board,
                    pieces = outcome.boardPieces,
                    myCoinColor = analysis.myCarromCoinColor,
                    screenWidth = obs.snapshot.screenWidth,
                    screenHeight = obs.snapshot.screenHeight,
                    existingStriker = probe.striker,
                    shotMemory = currentCarromMem,
                    isFreestyle = analysis.isFreestyleMode
                )
                ?: state.carromTurnMemory.solveCombinedFinalShot(
                    board = outcome.board,
                    latestPieces = outcome.boardPieces,
                    striker = probe.striker,
                    myCoinColor = analysis.myCarromCoinColor,
                    screenWidth = obs.snapshot.screenWidth,
                    screenHeight = obs.snapshot.screenHeight,
                    shotMemory = currentCarromMem,
                    usePhysicsRollout = obs.settings.usePhysicsRollout,
                    fallbackSolution = outcome.solution,
                    isFreestyle = analysis.isFreestyleMode,
                    queenPendingCover = state.queenPendingCover,
                    difficulty = obs.settings.carromDifficulty
                ) ?: outcome.solution
        } else {
            state.carromTurnMemory.solveCombinedFinalShot(
                board = outcome.board,
                latestPieces = outcome.boardPieces,
                striker = probe.striker,
                myCoinColor = analysis.myCarromCoinColor,
                screenWidth = obs.snapshot.screenWidth,
                screenHeight = obs.snapshot.screenHeight,
                shotMemory = currentCarromMem,
                usePhysicsRollout = obs.settings.usePhysicsRollout,
                fallbackSolution = outcome.solution,
                isFreestyle = analysis.isFreestyleMode,
                queenPendingCover = state.queenPendingCover,
                difficulty = obs.settings.carromDifficulty
            ) ?: outcome.solution.takeIf {
                it.isSureShot &&
                    (probe.striker == null ||
                        kotlin.math.abs(it.strikerPos.x - probe.striker.center.x) <= outcome.board.strikerRadiusPx * 1.6f)
            }
                ?: CarromGeometrySolver.solveSafetyShot(
                    board = outcome.board,
                    pieces = outcome.boardPieces,
                    myCoinColor = analysis.myCarromCoinColor,
                    screenWidth = obs.snapshot.screenWidth,
                    screenHeight = obs.snapshot.screenHeight,
                    existingStriker = probe.striker,
                    shotMemory = currentCarromMem,
                    isFreestyle = analysis.isFreestyleMode
                ) ?: outcome.solution
        }

        val hudStage = when {
            finalSol.isCollisionAssistedSureShot -> TurnProbeStage.CAROM_SURE_SHOT
            finalSol.isSureShot -> TurnProbeStage.LOCKED_SURE_SHOT
            else -> TurnProbeStage.FALLBACK_FIRE
        }

        val retryYOffset = when (state.consecutiveUnregisteredCarromShots) {
            1 -> -outcome.board.strikerRadiusPx * 0.40f
            2 -> outcome.board.strikerRadiusPx * 0.40f
            else -> 0f
        }
        val shootStartX = probe.striker?.center?.x ?: finalSol.strikerPos.x
        val shootStartY = ((probe.striker?.center?.y ?: finalSol.strikerPos.y) + retryYOffset)
            .coerceIn(outcome.board.top + outcome.board.height * 0.68f, outcome.board.bottom - outcome.board.strikerRadiusPx)
        val strikerNormX = ((shootStartX - outcome.board.left) / outcome.board.width).coerceIn(0f, 1f)
        val (coinRowZone, coinColZone) = CarromMemoryData.coinZoneFor(finalSol.targetCoin.center, outcome.board)

        val countSource = probe.consensusPieces.ifEmpty { outcome.boardPieces }
        state.lastShotPrePieces = countSource
        state.lastShotAttemptStrikerX = shootStartX
        // Record the REAL shot geometry for distance-banded
        // learning: normalized coin position, true total path
        // length (striker -> coin -> pocket) / board width, and
        // the pull multiplier actually used for this shot.
        val shotCoinNormX = ((finalSol.targetCoin.center.x - outcome.board.left) / outcome.board.width).coerceIn(0f, 1f)
        val shotCoinNormY = ((finalSol.targetCoin.center.y - outcome.board.top) / outcome.board.height).coerceIn(0f, 1f)
        val shotDistRatio = if (finalSol.totalDistance > 0f) {
            (finalSol.totalDistance / outcome.board.width).coerceIn(0.15f, 1.8f)
        } else {
            ((kotlin.math.hypot(finalSol.ghostBall.x - shootStartX, finalSol.ghostBall.y - shootStartY) +
                kotlin.math.hypot(finalSol.targetPocket.x - finalSol.targetCoin.center.x, finalSol.targetPocket.y - finalSol.targetCoin.center.y)) / outcome.board.width).coerceIn(0.15f, 1.8f)
        }
        // Safety touches are deliberate non-scoring moves: never
        // record them as pot attempts, or the memory would learn
        // that a soft touch "misses" its pocket.
        if (countSource.isNotEmpty() && !finalSol.isSafetyShot) {
            pendingShot = PendingCarromShotRecord(
                myCoinColor = analysis.myCarromCoinColor,
                targetCoinColor = finalSol.targetCoin.color,
                pocketIndex = finalSol.pocketIndex,
                cutAngleDeg = finalSol.cutAngleDeg,
                strikerNormalizedX = strikerNormX,
                coinRowZone = coinRowZone,
                coinColZone = coinColZone,
                powerStyle = finalSol.powerStyle,
                movedStrikerWithSlider = didSlideBeforeThisShot,
                isBankShot = finalSol.isBankShot,
                isComboShot = finalSol.isComboShot,
                isBreakShot = finalSol.isBreakShot,
                preCounts = CarromShotMemoryStore.countBoardPieces(countSource, analysis.myCarromCoinColor),
                isFreestyle = analysis.isFreestyleMode,
                coinNormX = shotCoinNormX,
                coinNormY = shotCoinNormY,
                distRatio = shotDistRatio,
                observedPullScale = finalSol.pullMultiplier,
                // Vision-error guard: freeze
                // whether the pre-shot
                // detection was stable across
                // 2 frames, so the miss-cause
                // classifier only trusts
                // geometry from a stable
                // pre-shot board.
                preDetectionStable = CarromVisionDetector.isDetectionStableAcrossTwoFrames,
                hasSolverGeometry = true
            )
        }

        val rawDx = finalSol.dragEnd.x - finalSol.strikerPos.x
        val rawDy = finalSol.dragEnd.y - finalSol.strikerPos.y
        // 5s sure-shot / no-foul rule: never rotate a
        // physics-verified shot's aim — even a 1.5-3 deg error
        // can turn a sure shot into a miss or a foul.
        // Human-likeness comes from timing jitter only;
        // difficulty still gates which shots are accepted.
        val aimErrDeg = 0f
        val (dx, dy) = if (aimErrDeg > 0f) {
            val errRad = Math.toRadians((Random.Default.nextFloat() * 2f * aimErrDeg - aimErrDeg).toDouble()).toFloat()
            val c = kotlin.math.cos(errRad)
            val s = kotlin.math.sin(errRad)
            (rawDx * c - rawDy * s) to (rawDx * s + rawDy * c)
        } else {
            rawDx to rawDy
        }
        val minBoundX = 28f
        val maxBoundX = (obs.snapshot.screenWidth - 28f).coerceAtLeast(29f)
        val minBoundY = 24f
        val maxBoundY = (obs.snapshot.screenHeight - maxOf(56f, obs.snapshot.screenHeight * 0.05f)).coerceAtLeast(25f)
        var pullScale = if (state.consecutiveUnregisteredCarromShots >= 3) 0.84f else 1f
        if (dx < -1e-3f && shootStartX + dx * pullScale < minBoundX) {
            pullScale = minOf(pullScale, ((minBoundX - shootStartX) / dx).coerceIn(0.2f, 1f))
        } else if (dx > 1e-3f && shootStartX + dx * pullScale > maxBoundX) {
            pullScale = minOf(pullScale, ((maxBoundX - shootStartX) / dx).coerceIn(0.2f, 1f))
        }
        if (dy < -1e-3f && shootStartY + dy * pullScale < minBoundY) {
            pullScale = minOf(pullScale, ((minBoundY - shootStartY) / dy).coerceIn(0.2f, 1f))
        } else if (dy > 1e-3f && shootStartY + dy * pullScale > maxBoundY) {
            pullScale = minOf(pullScale, ((maxBoundY - shootStartY) / dy).coerceIn(0.2f, 1f))
        }
        val shootEndX = (shootStartX + dx * pullScale).toInt().coerceIn(minBoundX.toInt(), maxBoundX.toInt())
        val shootEndY = (shootStartY + dy * pullScale).toInt().coerceIn(minBoundY.toInt(), maxBoundY.toInt())

        val finalAimSummary = "${finalSol.targetCoin.color.name} -> ${CarromMemoryData.pocketName(finalSol.pocketIndex)} (${finalSol.cutAngleDeg.toInt()}°) • ${finalSol.powerStyle.label} • ${(finalSol.confidenceScore * 100).toInt()}%" +
            if (outcome.turnBanner) " • TURN ✓" else ""
        _carromTurnHud.value = state.carromTurnMemory.buildHudState(
            nowMs = nowCarromMs,
            stage = hudStage,
            detail = finalSol.reason.take(64),
            board = outcome.board,
            tracedPieces = probe.fusedPiecesWithStriker,
            strikerPos = Point2D(shootStartX, shootStartY),
            solution = finalSol,
            dragEndOverride = Point2D(shootEndX.toFloat(), shootEndY.toFloat()),
            aimSummary = finalAimSummary
        )

        // Early-fire rule (user correction 2026-11-21):
        // 5s is only the MAXIMUM deadline, never a
        // waiting period. A physics-verified sure
        // shot fires the moment it is found — no
        // artificial delay. The human-like pre-shot
        // pause (0.4-1.2s) is therefore SKIPPED for
        // sure shots; it only ever applied to
        // fallback shots, which fire at/near the
        // deadline where the pause is 0 anyway.
        // Human-likeness now comes only from the
        // drag-duration jitter below. Bound the
        // pause inside the 5s budget: always leave
        // >= 1500ms for the drag dispatch + gesture start.
        val remainingBeforeShotMs = state.carromTurnMemory.remainingMs(nowCarromMs)
        val humanPauseMs = if (!finalSol.isSureShot && remainingBeforeShotMs > 2_500L && hudStage != TurnProbeStage.FALLBACK_FIRE && !probe.deadlineFire) {
            Random.Default.nextLong(400L, 1_201L)
                .coerceAtMost((remainingBeforeShotMs - 1_500L).coerceAtLeast(0L))
        } else {
            0L
        }
        val telemetry = state.carromTurnMemory.formatTurnTelemetry(nowCarromMs, finalSol)
        // 5s rule (Fix 3, 2026-11-22): resetTurn() used to run here,
        // BEFORE the safety-hold decision below — it zeroed the turn
        // clock, so canAffordToWaitForSureShot() always returned true
        // and the 4.5s deadline never arrived (safety held forever on
        // cluster boards). The turn now resets ONLY when a drag is
        // actually fired (else branch below); the WAIT path keeps the
        // clock running so the held safety fires at the deadline.

        val modeTag = if (analysis.isFreestyleMode) "Freestyle" else analysis.myCarromCoinColor.name
        val pullDistPx = kotlin.math.hypot(
            (shootEndX - shootStartX).toDouble(),
            (shootEndY - shootStartY).toDouble()
        ).toFloat()
        val baseDurationMs = (360L + (pullDistPx * 0.45f).toLong()).coerceIn(370L, 520L)
        // Human-like timing: vary the pull duration slightly so
        // every shot does not take exactly the same time.
        val humanJitterMs = Random.Default.nextLong(-45L, 46L)
        val shotDurationMs = (when (state.consecutiveUnregisteredCarromShots) {
            1 -> (baseDurationMs + 60L).coerceAtMost(580L)
            2 -> (baseDurationMs + 100L).coerceAtMost(620L)
            else -> baseDurationMs
        } + humanJitterMs).coerceIn(340L, 640L)
        val shotAction: PlannedAction = if (!probe.deadlineFire && finalSol.isSafetyShot && state.carromTurnMemory.canAffordToWaitForSureShot(nowCarromMs)) {
            // 5s rule (Fix 2): a safety shot is only
            // the DEADLINE fallback. Before the 4.5s
            // deadline, HOLD it and wait one more
            // cycle when the wait still fits — a sure
            // shot may still arrive inside the
            // budget (sim case D3). No pre-shot
            // pause on the wait path; the deadline
            // path below still fires the safety at
            // 4.5s. The wait is gated: a WAIT costs
            // a fixed 1500ms in GestureExecutor plus
            // one more detect+solve cycle, so on a
            // slow device the safety fires now
            // instead of waiting past 5s (sim D2).
            _carromTurnHud.value = state.carromTurnMemory.buildHudState(
                nowMs = nowCarromMs,
                stage = TurnProbeStage.WAITING,
                detail = "No sure shot yet — waiting for one (safety held for deadline)",
                board = outcome.board,
                tracedPieces = probe.fusedPiecesWithStriker,
                strikerPos = Point2D(shootStartX, shootStartY),
                solution = finalSol,
                aimSummary = finalAimSummary
            )
            PlannedAction(
                actionType = AgentActionType.WAIT,
                targetNodeId = null,
                x = null,
                y = null,
                textToType = null,
                waitSeconds = 1,
                memoryNote = null,
                reason = "No sure shot yet — holding the safety shot for the 4.5s deadline.",
                requiresConfirmation = false
            )
        } else {
            // Firing now (sure shot, deadline fire, or a safety that can
            // no longer wait): the turn ends with this drag, so reset
            // the turn memory here — and only here.
            state.carromTurnMemory.resetTurn()
            if (humanPauseMs > 0L) delay(humanPauseMs)
            PlannedAction(
                actionType = AgentActionType.DRAG,
            targetNodeId = null,
            x = shootStartX.toInt(),
            y = shootStartY.toInt(),
            endX = shootEndX,
            endY = shootEndY,
            durationMs = shotDurationMs,
            textToType = null,
            waitSeconds = null,
            memoryNote = "Carrom ($modeTag) $telemetry: ${finalSol.reason}",
            reason = "Zero-API Carrom $modeTag $telemetry: ${finalSol.reason} (conf=${(finalSol.confidenceScore * 100).toInt()}%)",
            requiresConfirmation = false
        )
        }
        return CarromShotResult(shotAction, pendingShot)
    }
    // AI planning for this step (Gemini / Groq / OpenRouter via
    // aiPlanner). (Formerly the `else { try { ... } }` after the
    // local-Carrom-action check.)
    private suspend fun planWithAiAction(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        carromResult: CarromActionResult,
        launcherDecision: LauncherSearchDecision?
    ): ActionDecision {
        val goal = state.goal
        val mode = state.mode
        val maxStepsLimit = state.maxStepsLimit
        val planned: PlannedAction = try {
        val thinkingNote = if (obs.isCarromContext && obs.snapshot.screenshot == null) {
            "Carrom Screenshot Missing: ${obs.snapshot.captureNote}"
        } else {
            obs.snapshot.captureNote
        }
        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = state.step,
            maxSteps = maxStepsLimit,
            phase = AgentPhase.THINKING,
            lastThought = thinkingNote,
            workingMemory = state.workingMemory,
            trace = state.trace.toList()
        )

        val carromPromptMem = if (obs.isCarromContext) {
            _carromMemory.value.formatForPrompt()
        } else {
            ""
        }
        val planResult = withContext(Dispatchers.IO) {
            aiPlanner.planNextAction(
                goal = goal,
                mode = mode,
                settings = obs.settings,
                snapshot = obs.snapshot,
                recentHistory = state.trace,
                recentNotifications = obs.notificationsSnapshot,
                workingMemory = state.workingMemory,
                playbook = state.activePlaybook,
                lastOutcomeHint = state.lastOutcomeHint,
                stepNumber = state.step,
                maxSteps = maxStepsLimit,
                matchedContact = analysis.matchedContact,
                savedContactsSummary = analysis.savedContactsSummary,
                chatGuardHint = analysis.chatGuardHint,
                carromMemorySummary = carromPromptMem,
                subGoalChecklist = state.activeSubGoalChecklist,
                actionReflectorHint = analysis.actionReflectorHint,
                forceVision = obs.isCarromContext
            )
        }

        planResult.getOrElse { err ->
            val msg = err.localizedMessage ?: "AI planning failed."
            val isConfigOrAuthError = msg.contains("Firebase is not", ignoreCase = true) ||
                msg.contains("Missing API key", ignoreCase = true) ||
                msg.contains("HTTP 401") ||
                msg.contains("HTTP 403")
            val hasKeys = obs.settings.hasAnyRunnableKey(isFirebaseConfigured())
            val canRecoverInCarromZeroApi = obs.isCarromContext && (!hasKeys || !obs.isNonGameApp)
            if (canRecoverInCarromZeroApi && (!obs.isNonGameApp || mode == AgentExecutionMode.CONTINUOUS_MONITOR || state.step <= 3)) {
                val waitReason = when {
                    !hasKeys && obs.isNonGameApp ->
                        "Zero-API Carrom ready (pkg=${obs.snapshot.packageName}) — open Carrom match board on screen..."
                    !hasKeys ->
                        "Waiting for Carrom match board (lobby=${obs.isLikelyCarromLobby}, nodes=${obs.interactiveNodeCount}, screen=${obs.snapshot.screenshot != null}, pkg=${obs.snapshot.packageName})"
                    else ->
                        "Waiting for board settle / AI hiccup: $msg"
                }
                appendTrace(
                    state.trace,
                    AgentStepLog(
                        stepNumber = state.step,
                        timestampMs = System.currentTimeMillis(),
                        actionType = AgentActionType.WAIT,
                        targetSummary = "Carrom standby",
                        reason = waitReason,
                        status = StepStatus.WAITING,
                        requiresConfirmation = false
                    )
                )
                sleepMonitorInterval(
                    goal = goal,
                    mode = mode,
                    step = state.step,
                    maxSteps = maxStepsLimit,
                    waitSec = if (!obs.isNonGameApp) 1 else 2,
                    reason = waitReason,
                    workingMemory = state.workingMemory,
                    trace = state.trace
                )
                state.step++
                return ActionDecision.NextStep
            } else if (mode == AgentExecutionMode.CONTINUOUS_MONITOR && !isConfigOrAuthError) {
                // Recoverable network/transient hiccup in all-day monitor: wait and retry
                appendTrace(
                    state.trace,
                    AgentStepLog(
                        stepNumber = state.step,
                        timestampMs = System.currentTimeMillis(),
                        actionType = AgentActionType.WAIT,
                        targetSummary = "Transient AI error",
                        reason = msg,
                        status = StepStatus.WAITING,
                        requiresConfirmation = false
                    )
                )
                sleepMonitorInterval(
                    goal = goal,
                    mode = mode,
                    step = state.step,
                    maxSteps = maxStepsLimit,
                    waitSec = obs.settings.monitorIntervalSec,
                    reason = "Retrying after transient error: ${err.localizedMessage ?: "network"}",
                    workingMemory = state.workingMemory,
                    trace = state.trace
                )
                state.step++
                return ActionDecision.NextStep
            } else {
                _uiState.value = AgentUiState.Error(
                    goal = goal,
                    message = err.localizedMessage ?: "AI planning failed.",
                    workingMemory = state.workingMemory,
                    trace = state.trace.toList()
                )
                return ActionDecision.EndRun
            }
        }
        } finally {
            obs.snapshot.screenshot?.recycle()
        }
        return ActionDecision.Action(
            action = planned,
            usedLocalCarromSolver = carromResult.usedLocalCarromSolver,
            nextLauncherStageTag = null,
            nextPendingCarromShotCandidate = carromResult.nextPendingCarromShotCandidate,
            aiPreCarromCounts = carromResult.aiPreCarromCounts,
            launcherDecision = launcherDecision
        )
    }
    // Manager / safety / duplicate guards applied to the planned action.
    // (Formerly the rawTargetNode + `when` guard chain after planning.)
    private fun applyActionGuards(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        rawAction: PlannedAction
    ): PlannedAction {
        val goal = state.goal
        val mode = state.mode
        val maxStepsLimit = state.maxStepsLimit
        val rawTargetNode = rawAction.targetNodeId?.let { id ->
            obs.snapshot.nodes.firstOrNull { it.id == id }
        }
        val plannedAction: PlannedAction = when {
            analysis.currentActiveTarget != null &&
                analysis.chatObs.isMessagingApp &&
                analysis.chatObs.inChatThread &&
                analysis.chatObs.peerName.isNotBlank() &&
                !NotificationReplyHelper.matchesTargetPeer(analysis.chatObs.peerName, analysis.currentActiveTarget.senderName) &&
                (ChatTurnGuard.isChatSendAction(rawAction, rawTargetNode, obs.snapshot) ||
                    rawAction.actionType == AgentActionType.INPUT_TEXT) -> {
                PlannedAction(
                    actionType = AgentActionType.BACK,
                    targetNodeId = null,
                    x = null,
                    y = null,
                    textToType = null,
                    waitSeconds = null,
                    memoryNote = rawAction.memoryNote,
                    reason = "Wrong chat thread '${analysis.chatObs.peerName}' — backing out to open notification sender '${analysis.currentActiveTarget.senderName}'.",
                    requiresConfirmation = false
                )
            }
            obs.effectiveSavedContactsOnly &&
                analysis.chatObs.isMessagingApp &&
                analysis.chatObs.inChatThread &&
                analysis.chatObs.peerName.isNotBlank() &&
                analysis.matchedContact == null &&
                ChatTurnGuard.isChatSendAction(rawAction, rawTargetNode, obs.snapshot) -> {
                PlannedAction(
                    actionType = AgentActionType.WAIT,
                    targetNodeId = null,
                    x = null,
                    y = null,
                    textToType = null,
                    waitSeconds = obs.settings.monitorIntervalSec,
                    memoryNote = rawAction.memoryNote,
                    reason = "Saved-Contacts-Only mode: skipped unsaved contact '${analysis.chatObs.peerName}'.",
                    requiresConfirmation = false
                )
            }
            obs.settings.preventDoubleText &&
                ChatTurnGuard.shouldBlockDuplicateSend(
                    action = rawAction,
                    targetNode = rawTargetNode,
                    obs = analysis.chatObs,
                    recentNotifications = obs.notificationsSnapshot,
                    awaiting = analysis.activeAwaitingReply,
                    snapshot = obs.snapshot
                ) -> {
                val waitTarget = analysis.chatObs.peerName.ifBlank { "contact" }
                PlannedAction(
                    actionType = AgentActionType.WAIT,
                    targetNodeId = null,
                    x = null,
                    y = null,
                    textToType = null,
                    waitSeconds = obs.settings.monitorIntervalSec,
                    memoryNote = rawAction.memoryNote,
                    reason = "Anti-Double-Text Guard: blocked repeat message to $waitTarget until they reply.",
                    requiresConfirmation = false
                )
            }
            rawAction.actionType == AgentActionType.COMPLETE &&
                ActionReflectorHelper.hasIncompleteChecklist(
                    rawAction.subGoalChecklist ?: state.activeSubGoalChecklist
                ) &&
                state.completionGuardOverrides < 2 &&
                state.step < (maxStepsLimit ?: 999) - 1 -> {
                state.completionGuardOverrides++
                val remainingList = rawAction.subGoalChecklist ?: state.activeSubGoalChecklist
                state.lastOutcomeHint = "[COMPLETION BLOCKED BY MANAGER GUARD: Sub-goal checklist still has unfinished '[ ]' items: $remainingList. Finish all steps before returning COMPLETE!] ${state.lastOutcomeHint}"
                PlannedAction(
                    actionType = AgentActionType.WAIT,
                    targetNodeId = null,
                    x = null,
                    y = null,
                    textToType = null,
                    waitSeconds = 1,
                    memoryNote = rawAction.memoryNote,
                    subGoalChecklist = remainingList,
                    reflectionStatus = "BLOCKED",
                    reason = "Manager Guard: unfinished checklist items remain ($remainingList) — continuing execution.",
                    requiresConfirmation = false
                )
            }
            else -> rawAction
        }
        return plannedAction
    }
    // Execute one planned action and do the post-execution bookkeeping
    // (memory, checklist, cache trail, Carrom shot registration).
    // Returns what the loop should do next. (Formerly the tail of the
    // loop body, from the memoryNote update to `step++`.)
    private suspend fun executePlannedAction(
        state: TaskRunState,
        obs: StepObservation,
        analysis: StepAnalysis,
        decision: ActionDecision.Action,
        plannedAction: PlannedAction
    ): StepFlow {
        val cacheMatch = analysis.cacheMatch
        val aiPreCarromCounts = decision.aiPreCarromCounts
        val nextPendingCarromShotCandidate = decision.nextPendingCarromShotCandidate
        val goal = state.goal
        val mode = state.mode
        val service = state.service
        val maxStepsLimit = state.maxStepsLimit
        if (!plannedAction.memoryNote.isNullOrBlank()) {
            state.workingMemory = plannedAction.memoryNote
        }
        if (!plannedAction.subGoalChecklist.isNullOrBlank()) {
            state.activeSubGoalChecklist = plannedAction.subGoalChecklist
        }

        if (!plannedAction.foulOrMistakeLesson.isNullOrBlank()) {
            state.cacheTrail.onMistakeDetected()
        }
        if (!plannedAction.learnedRule.isNullOrBlank() || !plannedAction.foulOrMistakeLesson.isNullOrBlank()) {
            val topicKey = state.activePlaybook?.topicKey
                ?: AgentKnowledgeStore.normalizeKey(goal.take(40))
            val updatedPlaybook = knowledgeStore?.recordStepLearning(
                topicKey = topicKey,
                fallbackTopicTitle = state.activePlaybook?.topicTitle ?: goal.take(40),
                newStrategy = plannedAction.learnedRule,
                newFoulLesson = plannedAction.foulOrMistakeLesson
            )
            if (updatedPlaybook != null) {
                state.activePlaybook = updatedPlaybook
            }
        }

        val targetNode = plannedAction.targetNodeId?.let { id ->
            obs.snapshot.nodes.firstOrNull { it.id == id }
        }
        val targetSummary = buildTargetSummary(plannedAction, targetNode)

        if (plannedAction.actionType == AgentActionType.COMPLETE) {
            state.cacheTrail.commitAllOnComplete()
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = state.step,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.COMPLETE,
                    targetSummary = "Goal satisfied",
                    reason = plannedAction.reason,
                    status = StepStatus.COMPLETED,
                    requiresConfirmation = false
                )
            )
            _uiState.value = AgentUiState.Completed(
                goal = goal,
                summary = plannedAction.reason,
                totalSteps = state.step,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            return StepFlow.END_RUN
        }

        if (plannedAction.actionType == AgentActionType.FAIL) {
            state.cacheTrail.onMistakeDetected()
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = state.step,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.FAIL,
                    targetSummary = targetSummary,
                    reason = plannedAction.reason,
                    status = StepStatus.FAILED,
                    requiresConfirmation = false
                )
            )
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = plannedAction.reason,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            return StepFlow.END_RUN
        }

        if (plannedAction.actionType == AgentActionType.WAIT) {
            if (state.isCarromOfflineWait) {
                val fastWaitMs = if (LiveMediaProjectionCapturer.isLiveActive.value) 160L else 380L
                _uiState.value = AgentUiState.Running(
                    goal = goal,
                    mode = mode,
                    currentStep = state.step,
                    maxSteps = maxStepsLimit,
                    phase = AgentPhase.OBSERVING,
                    lastThought = plannedAction.reason,
                    workingMemory = state.workingMemory,
                    trace = state.trace.toList()
                )
                delay(fastWaitMs)
                state.step++
                return StepFlow.NEXT_STEP
            }
            val waitSec = plannedAction.waitSeconds
                ?: if (mode == AgentExecutionMode.CONTINUOUS_MONITOR) {
                    obs.settings.monitorIntervalSec
                } else {
                    3
                }
            appendTrace(
                state.trace,
                AgentStepLog(
                    stepNumber = state.step,
                    timestampMs = System.currentTimeMillis(),
                    actionType = AgentActionType.WAIT,
                    targetSummary = "Wait ${waitSec}s (wakes on notification)",
                    reason = plannedAction.reason,
                    status = StepStatus.WAITING,
                    requiresConfirmation = false
                )
            )
            sleepMonitorInterval(
                goal = goal,
                mode = mode,
                step = state.step,
                maxSteps = maxStepsLimit,
                waitSec = waitSec,
                reason = plannedAction.reason,
                workingMemory = state.workingMemory,
                trace = state.trace
            )
            state.step++
            return StepFlow.NEXT_STEP
        }

        if (!decision.usedLocalCarromSolver && SafetyGuard.isStuckLoop(state.trace, plannedAction, targetSummary)) {
            _uiState.value = AgentUiState.Error(
                goal = goal,
                message = "Stopped to prevent an infinite loop repeating: $targetSummary",
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            return StepFlow.END_RUN
        }

        val safetyCheck = SafetyGuard.evaluate(
            action = plannedAction,
            targetNode = targetNode,
            policyMode = obs.settings.safetyPolicyMode
        )

        var finalStatus = StepStatus.EXECUTED
        if (safetyCheck.needsConfirmation) {
            val deferred = CompletableDeferred<Boolean>()
            confirmationDeferred = deferred
            _uiState.value = AgentUiState.AwaitingConfirmation(
                goal = goal,
                mode = mode,
                currentStep = state.step,
                maxSteps = maxStepsLimit,
                pendingAction = plannedAction,
                targetDescription = targetSummary,
                safetyReason = safetyCheck.reason,
                workingMemory = state.workingMemory,
                trace = state.trace.toList()
            )
            val approved = deferred.await()
            confirmationDeferred = null
            if (!approved) {
                appendTrace(
                    state.trace,
                    AgentStepLog(
                        stepNumber = state.step,
                        timestampMs = System.currentTimeMillis(),
                        actionType = plannedAction.actionType,
                        targetSummary = targetSummary,
                        reason = plannedAction.reason,
                        status = StepStatus.REJECTED,
                        requiresConfirmation = true
                    )
                )
                _uiState.value = AgentUiState.Completed(
                    goal = goal,
                    summary = "Stopped safely: payment/sensitive action declined by user.",
                    totalSteps = state.step,
                    workingMemory = state.workingMemory,
                    trace = state.trace.toList()
                )
                return StepFlow.END_RUN
            }
            finalStatus = StepStatus.APPROVED
        }

        awaitIfPaused(goal, mode, state.step, maxStepsLimit, state.workingMemory, state.trace)

        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = state.step,
            maxSteps = maxStepsLimit,
            phase = AgentPhase.ACTING,
            lastThought = plannedAction.reason,
            workingMemory = state.workingMemory,
            trace = state.trace.toList()
        )

        val execResult = service.executeAction(plannedAction, obs.snapshot)
        val stepResultSummary = execResult.summary.ifBlank { targetSummary }
        state.prevCompositeScreen = obs.currentCompositeScreen
        state.prevPackageName = obs.snapshot.packageName
        state.prevNonWaitActionType = plannedAction.actionType
        state.prevInputText = if (plannedAction.actionType == AgentActionType.INPUT_TEXT) {
            plannedAction.textToType
        } else {
            null
        }

        if (execResult.success) {
            if (nextPendingCarromShotCandidate != null) {
                state.pendingCarromShot = nextPendingCarromShotCandidate
                state.pendingEvalStaleCycles = 0
            } else if (obs.isCarromContext &&
                aiPreCarromCounts != null &&
                plannedAction.actionType == AgentActionType.DRAG &&
                plannedAction.x != null && plannedAction.y != null &&
                plannedAction.endX != null && plannedAction.endY != null
            ) {
                val dragDx = plannedAction.endX - plannedAction.x
                val dragDy = plannedAction.endY - plannedAction.y
                val isHorizontalSlider = kotlin.math.abs(dragDy) < 28 && plannedAction.y > obs.snapshot.screenHeight * 0.78f
                if (isHorizontalSlider) {
                    state.movedStrikerThisTurn = true
                } else {
                    val dragDist = kotlin.math.hypot(dragDx.toDouble(), dragDy.toDouble()).toFloat()
                    val power = when {
                        dragDist < 205f -> CarromPowerStyle.SOFT
                        dragDist > 315f -> CarromPowerStyle.HARD
                        else -> CarromPowerStyle.MEDIUM
                    }
                    // Shot direction is opposite slingshot pull
                    val aimLeft = -dragDx < 0
                    val aimTop = -dragDy <= 0
                    val estPocketIdx = when {
                        aimTop && aimLeft -> 0
                        aimTop && !aimLeft -> 1
                        !aimTop && aimLeft -> 2
                        else -> 3
                    }
                    state.pendingCarromShot = PendingCarromShotRecord(
                        myCoinColor = analysis.myCarromCoinColor,
                        targetCoinColor = analysis.myCarromCoinColor,
                        pocketIndex = estPocketIdx,
                        cutAngleDeg = 18f,
                        powerStyle = power,
                        movedStrikerWithSlider = state.movedStrikerThisTurn,
                        isBankShot = false,
                        isComboShot = false,
                        isBreakShot = false,
                        preCounts = aiPreCarromCounts,
                        isFreestyle = analysis.isFreestyleMode
                    )
                    state.movedStrikerThisTurn = false
                }
            }
        }

        if (execResult.success && analysis.chatObs.isMessagingApp) {
            val peerKeyForAction = ChatTurnGuard.peerKey(obs.snapshot.packageName, analysis.chatObs.peerName)
            if (analysis.chatObs.inChatThread && plannedAction.actionType == AgentActionType.SCROLL_UP) {
                state.historyScrolledPeers.add(peerKeyForAction)
            }
            if (plannedAction.actionType == AgentActionType.INPUT_TEXT && !plannedAction.textToType.isNullOrBlank()) {
                state.pendingSentTextForPeer = peerKeyForAction to plannedAction.textToType.trim()
            } else if (ChatTurnGuard.isChatSendAction(plannedAction, targetNode, obs.snapshot)) {
                val sentText = state.pendingSentTextForPeer
                    ?.takeIf { it.first == peerKeyForAction }
                    ?.second
                    ?: analysis.chatObs.lastMessageText
                state.awaitingRepliesByPeer[peerKeyForAction] = ChatTurnGuard.AwaitingReplyState(
                    packageName = obs.snapshot.packageName,
                    peerName = analysis.chatObs.peerName,
                    lastSentText = sentText,
                    transcriptSignatureAtSend = analysis.chatObs.transcriptSignature,
                    sentAtMs = System.currentTimeMillis()
                )
                if (analysis.currentActiveTarget != null &&
                    (!analysis.chatObs.inChatThread || analysis.chatObs.peerName.isBlank() ||
                        NotificationReplyHelper.matchesTargetPeer(analysis.chatObs.peerName, analysis.currentActiveTarget.senderName))
                ) {
                    state.handledNotificationKeys.add(analysis.currentActiveTarget.targetKey)
                    state.activeReplyTarget = null
                    state.lastOutcomeHint = "[REPLIED to notification from '${analysis.currentActiveTarget.senderName}' — waiting for next incoming message notification.] ${state.lastOutcomeHint}"
                }
            }
        }

        if (execResult.success && decision.nextLauncherStageTag != null) {
            state.pendingLauncherStageTag = decision.nextLauncherStageTag
        }
        if (!decision.usedLocalCarromSolver && execResult.success && obs.settings.useZeroApiActionCache) {
            state.pendingCacheCandidate = PendingCacheCandidate(
                goal = goal,
                packageName = obs.snapshot.packageName,
                screenSignature = obs.currentSig,
                interactiveNodeCount = obs.interactiveNodeCount,
                action = plannedAction,
                targetNode = targetNode,
                hadConfirmedSafety = safetyCheck.needsConfirmation
            )
            if (cacheMatch != null) {
                state.lastReplayEntry = cacheMatch.entry.cacheKey to obs.currentCompositeScreen
            }
        } else if (!execResult.success && cacheMatch != null) {
            actionCacheStore?.removeEntry(cacheMatch.entry.cacheKey)
            state.lastReplayEntry = null
        }
        state.lastOutcomeHint = "Previous state.step executed ${plannedAction.actionType.name}: $stepResultSummary " +
            "[status=${if (execResult.success) finalStatus.name else "FAILED"}]. " +
            "Reason was: ${plannedAction.reason}. Compare the new screenshot carefully: if it succeeded/pocketed, save learnedRule; if foul/missed/wrong, save foulOrMistakeLesson."
        appendTrace(
            state.trace,
            AgentStepLog(
                stepNumber = state.step,
                timestampMs = System.currentTimeMillis(),
                actionType = plannedAction.actionType,
                targetSummary = stepResultSummary,
                reason = if (cacheMatch != null) {
                    "Zero-API Cache Replay (0 API calls) — ${cacheMatch.entry.targetLabel}"
                } else {
                    plannedAction.reason
                },
                status = if (execResult.success) finalStatus else StepStatus.FAILED,
                requiresConfirmation = safetyCheck.needsConfirmation
            )
        )

        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = state.step,
            maxSteps = maxStepsLimit,
            phase = AgentPhase.WAITING_DELAY,
            lastThought = plannedAction.reason,
            workingMemory = state.workingMemory,
            trace = state.trace.toList()
        )
        val effectiveDelayMs = when {
            decision.usedLocalCarromSolver && state.didSlideCarromThisStep && LiveMediaProjectionCapturer.isLiveActive.value -> 65L
            decision.usedLocalCarromSolver && state.didSlideCarromThisStep -> 100L
            LiveMediaProjectionCapturer.isLiveActive.value && decision.usedLocalCarromSolver -> 420L
            cacheMatch != null || decision.launcherDecision != null -> minOf(obs.settings.stepDelayMs, 550L)
            else -> obs.settings.stepDelayMs
        }
        delay(effectiveDelayMs)
        state.step++
        return StepFlow.PROCEED
    }
    fun pauseTask(reason: String = "Automation paused. Tap Resume or Stop.") {
        val current = _uiState.value
        if (current is AgentUiState.Running && pauseGate == null) {
            pauseGate = CompletableDeferred()
            _uiState.value = AgentUiState.Paused(
                goal = current.goal,
                mode = current.mode,
                currentStep = current.currentStep,
                maxSteps = current.maxSteps,
                pauseReason = reason,
                workingMemory = current.workingMemory,
                trace = current.trace
            )
        }
    }

    fun resumeTask() {
        pauseGate?.complete(Unit)
        pauseGate = null
    }

    fun confirmPendingAction(approved: Boolean) {
        confirmationDeferred?.complete(approved)
    }

    fun stopTask(reason: String? = null) {
        activeRunJob?.cancel()
        activeRunJob = null
        pauseGate?.cancel()
        pauseGate = null
        confirmationDeferred?.cancel()
        confirmationDeferred = null
        notificationWakeSignal?.cancel()
        notificationWakeSignal = null
        appContext?.let { AgentKeepAliveService.stop(it) }
        _carromTurnHud.value = null

        val currentTrace = extractTrace(_uiState.value)
        val currentGoal = extractGoal(_uiState.value)
        val currentMemory = extractMemory(_uiState.value)
        _uiState.value = if (reason != null) {
            AgentUiState.Error(
                goal = currentGoal,
                message = reason,
                workingMemory = currentMemory,
                trace = currentTrace
            )
        } else if (currentTrace.isNotEmpty()) {
            AgentUiState.Completed(
                goal = currentGoal,
                summary = "Automation stopped by user.",
                totalSteps = currentTrace.size,
                workingMemory = currentMemory,
                trace = currentTrace
            )
        } else {
            AgentUiState.Idle
        }
    }

    fun dismissFinishedState() {
        val current = _uiState.value
        if (current is AgentUiState.Completed || current is AgentUiState.Error) {
            _uiState.value = AgentUiState.Idle
        }
    }

    private suspend fun sleepMonitorInterval(
        goal: String,
        mode: AgentExecutionMode,
        step: Int,
        maxSteps: Int?,
        waitSec: Int,
        reason: String,
        workingMemory: String,
        trace: List<AgentStepLog>
    ) {
        val wakeSignal = CompletableDeferred<Unit>()
        notificationWakeSignal = wakeSignal
        _uiState.value = AgentUiState.Running(
            goal = goal,
            mode = mode,
            currentStep = step,
            maxSteps = maxSteps,
            phase = AgentPhase.MONITOR_SLEEPING,
            lastThought = "Waiting up to ${waitSec}s • $reason",
            workingMemory = workingMemory,
            trace = trace.toList()
        )
        try {
            withTimeoutOrNull(waitSec * 1000L) {
                wakeSignal.await()
            }
        } finally {
            if (notificationWakeSignal === wakeSignal) {
                notificationWakeSignal = null
            }
        }
    }

    private suspend fun awaitIfPaused(
        goal: String,
        mode: AgentExecutionMode,
        step: Int,
        maxSteps: Int?,
        workingMemory: String,
        trace: List<AgentStepLog>
    ) {
        val gate = pauseGate ?: return
        _uiState.value = AgentUiState.Paused(
            goal = goal,
            mode = mode,
            currentStep = step,
            maxSteps = maxSteps,
            pauseReason = "Automation paused. Tap Resume or Stop.",
            workingMemory = workingMemory,
            trace = trace.toList()
        )
        gate.await()
    }

    private fun appendTrace(trace: MutableList<AgentStepLog>, entry: AgentStepLog) {
        if (trace.size >= MAX_TRACE_ENTRIES) {
            trace.removeAt(0)
        }
        trace.add(entry)
    }

    private fun buildTargetSummary(action: PlannedAction, node: ScreenNode?): String {
        return when {
            action.actionType == AgentActionType.DRAG &&
                action.x != null && action.y != null &&
                action.endX != null && action.endY != null ->
                "Drag (${action.x},${action.y}) -> (${action.endX},${action.endY})"
            node != null -> "#${node.id} ${node.displayLabel()} (${node.bounds.centerX},${node.bounds.centerY})"
            action.x != null && action.y != null -> "(${action.x}, ${action.y})"
            !action.textToType.isNullOrBlank() -> "\"${action.textToType}\""
            else -> action.actionType.name
        }
    }

    private fun extractTrace(state: AgentUiState): List<AgentStepLog> = when (state) {
        is AgentUiState.Idle -> emptyList()
        is AgentUiState.Running -> state.trace
        is AgentUiState.Paused -> state.trace
        is AgentUiState.AwaitingConfirmation -> state.trace
        is AgentUiState.Completed -> state.trace
        is AgentUiState.Error -> state.trace
    }

    private fun extractGoal(state: AgentUiState): String = when (state) {
        is AgentUiState.Idle -> ""
        is AgentUiState.Running -> state.goal
        is AgentUiState.Paused -> state.goal
        is AgentUiState.AwaitingConfirmation -> state.goal
        is AgentUiState.Completed -> state.goal
        is AgentUiState.Error -> state.goal
    }

    private fun isFirebaseConfigured(): Boolean {
        return runCatching { com.google.firebase.FirebaseApp.getInstance() }.isSuccess
    }

    private fun resolveCarromRuleMode(
        goal: String,
        configured: CarromRuleMode
    ): CarromRuleMode {
        return when {
            goal.contains("freestyle", ignoreCase = true) ||
                goal.contains("ফ্রি স্টাইল") ||
                goal.contains("ফ্রিস্টাইল") ||
                goal.contains("সব গুটি") -> CarromRuleMode.FREESTYLE
            goal.contains("only black", ignoreCase = true) ||
                goal.contains("disc pool black", ignoreCase = true) ||
                goal.contains("শুধু কালো") -> CarromRuleMode.BLACK_ONLY
            goal.contains("only white", ignoreCase = true) ||
                goal.contains("disc pool white", ignoreCase = true) ||
                goal.contains("শুধু সাদা") -> CarromRuleMode.WHITE_ONLY
            else -> configured
        }
    }

    private fun tryLaunchPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pm = context.packageManager
        val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(launchIntent)
            Log.i(TAG, "Direct-launched package: $packageName")
            true
        }.getOrDefault(false)
    }

    private fun tryLaunchInstalledCarromApp(context: Context): Boolean {
        val candidatePackages = listOf(
            "com.miniclip.carrom",
            "com.miniclip.carrom.freestyle",
            "com.moonfrog.carromgold",
            "com.gametion.carromking",
            "com.appsoleutgames.carrom"
        )
        for (pkg in candidatePackages) {
            if (tryLaunchPackage(context, pkg)) {
                return true
            }
        }
        return false
    }

    private fun extractMemory(state: AgentUiState): String = when (state) {
        is AgentUiState.Idle -> ""
        is AgentUiState.Running -> state.workingMemory
        is AgentUiState.Paused -> state.workingMemory
        is AgentUiState.AwaitingConfirmation -> state.workingMemory
        is AgentUiState.Completed -> state.workingMemory
        is AgentUiState.Error -> state.workingMemory
    }
}
