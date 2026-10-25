package com.fsm.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.fsm.FsmApp
import com.fsm.MainActivity
import com.fsm.data.*
import com.fsm.sync.SyncGate
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Disposable hosted-emulator tests. Activity recreation is NOT process death. */
@RunWith(AndroidJUnit4::class)
class LifecycleUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FsmApp
    private var scenario:ActivityScenario<MainActivity>?=null
    private var accountId:String?=null
    private var ownedRepo:Repository?=null
    private lateinit var model:FsmModel
    private lateinit var order:WorkOrderDto
    private val gateOwner=Any()
    private var ownsSyncGate=false
    private val executorGates=mutableListOf<ExecutorGate>()

    @Before fun requireEmptyDisposableAccountStorage() {
        val context=instrumentation.targetContext
        // Check BEFORE seed, delete, cancel, open-account, or cleanup registration.
        check(app.repository==null && app.activeId()==null) { "UI tests require an unused disposable emulator" }
        check(context.getSharedPreferences("account",Context.MODE_PRIVATE).all.isEmpty()) { "Existing account preferences; refusing to touch data" }
        check(context.databaseList().none {it.startsWith("fsm_")}) { "Existing account database; refusing to touch data" }
        check(File(context.filesDir,"accounts").listFiles().isNullOrEmpty()) { "Existing account files; refusing to touch data" }
        check(File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().none {it.name.startsWith("tokens_")}) { "Existing token storage; refusing to touch data" }
        // Existing runner/Room tests use their own databases. Block only sync, never UI edits.
        runBlocking {withTimeout(10000) {SyncGate.mutex.lock(gateOwner)}}
        ownsSyncGate=true
    }
    @After fun cleanupOnlyOwnedAccount() {
        try {
            // Always unblock controlled executor tasks, even when the regression assertion is red.
            executorGates.forEach {it.release()}
            executorGates.forEach {it.awaitFinished()}
            scenario?.close()
            if(::model.isInitialized) runBlocking {withTimeout(10000) {model.viewModelScope.coroutineContext[Job]?.join()}}
            val id=accountId
            if(id!=null) {
                check(app.activeId()==id && app.repository===ownedRepo) { "Account changed; refusing foreign cleanup" }
                WorkManager.getInstance(app).cancelUniqueWork("sync_$id").result.get(10,TimeUnit.SECONDS)
                app.clearActive()
                ownedRepo?.db?.close()
                app.deleteDatabase("fsm_$id.db")
                File(app.filesDir,"accounts/$id").deleteRecursively()
                app.deleteSharedPreferences("tokens_$id")
            }
        } finally {
            if(ownsSyncGate) {SyncGate.mutex.unlock(gateOwner); ownsSyncGate=false}
        }
    }
    private fun seedAccount(validDraft:Boolean=false) {
        val fixture=wire.parseToJsonElement(instrumentation.context.assets.open("contract-fixtures.json").bufferedReader().use {it.readText()}).jsonObject
        val snapshot=wire.decodeFromJsonElement<SnapshotDto>(fixture.getValue("snapshotBeforeClose"))
        val id=uuid()
        accountId=id
        val repo=app.open(id); ownedRepo=repo
        order=snapshot.workOrders.single().copy(assigneeId=id)
        runBlocking {
            repo.db.withTransaction {
                repo.store.employee(Employee(id,wire.encodeToString(snapshot.employee.copy(id=id))))
                snapshot.sites.forEach {repo.store.site(Site(it.id,wire.encodeToString(it)))}
                snapshot.equipment.forEach {repo.store.equipment(Equipment(it.id,it.siteId,wire.encodeToString(it)))}
                repo.applyCanonical(order)
            }
            if(validDraft) {
                repo.create(order.id)
                val draft=wire.decodeFromString<ResultWriteDto>(requireNotNull(repo.store.result(order.id)).json)
                repo.save(order.id,draft.copy(answers=order.checklistItems.map {AnswerDto(it.id,true)},readings=order.measurementSpecs.map {ReadingDto(it.key,"2.40")},comment="synthetic saved draft"))
            }
        }
    }
    private fun launch() {
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {model=ViewModelProvider(it)[FsmModel::class.java]}
        if(ownedRepo!=null) compose.waitUntil(10000) {model.orders.any {it.id==order.id}}
    }
    private fun go(route:String) {scenario!!.onActivity {model.go(route,order.id)}}
    private fun landscape() {
        scenario!!.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000) {app.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        scenario!!.onActivity {model=ViewModelProvider(it)[FsmModel::class.java]}
    }
    private fun recreate() {
        scenario!!.recreate()
        scenario!!.onActivity {model=ViewModelProvider(it)[FsmModel::class.java]}
    }
    private fun showIme(editorLabel:String) {
        // One shared 10-second budget: rotation may finish configuration before window/editor setup.
        val deadline=SystemClock.elapsedRealtime()+10000
        fun remaining():Long {
            val millis=deadline-SystemClock.elapsedRealtime()
            assertTrue("IME readiness/visibility exceeded 10 seconds",millis>0)
            return millis
        }
        fun windowReady(activity:MainActivity):Boolean {
            val decor=activity.window.decorView
            return activity.lifecycle.currentState==Lifecycle.State.RESUMED &&
                decor.isAttachedToWindow && decor.hasWindowFocus() && decor.rootWindowInsets!=null
        }
        compose.waitUntil(remaining()) {
            var ready=false
            scenario!!.onActivity {ready=windowReady(it)}
            ready
        }
        val editor=compose.onNode(hasSetTextAction() and hasText(editorLabel))
        editor.performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        editor.assertIsFocused()
        compose.waitUntil(remaining()) {
            var ready=false
            scenario!!.onActivity { activity ->
                val focused=activity.currentFocus
                val imm=activity.getSystemService(InputMethodManager::class.java)
                ready=windowReady(activity) && focused!=null && focused.isAttachedToWindow &&
                    focused.hasFocus() && focused.windowToken==activity.window.decorView.windowToken &&
                    imm.isActive(focused) && imm.isAcceptingText
            }
            ready
        }
        editor.assertIsFocused()
        scenario!!.onActivity {it.window.insetsController?.show(WindowInsets.Type.ime())}
        compose.waitUntil(remaining()) {
            var visible=false
            scenario!!.onActivity {visible=it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
            visible
        }
    }
    private fun savedDraft():ResultWriteDto=runBlocking {
        wire.decodeFromString(requireNotNull(ownedRepo!!.store.result(order.id)).json)
    }
    private fun awaitSaved(comment:String) {
        compose.waitUntil(10000) {!model.saving && savedDraft().comment==comment}
    }
    private fun enterForm() {
        go("detail")
        compose.onNodeWithText("Начать осмотр").performScrollTo().performClick()
        compose.waitUntil(10000) {model.route=="form" && !model.busy}
    }
    @Test fun loginLandscapeRetainsLoginAndKeepsPasswordEphemeralWithIme() {
        launch()
        compose.onNodeWithText("Логин").performClick().performTextInput("synthetic.engineer")
        showIme("Логин")
        compose.onNodeWithText("Пароль").performScrollTo().assertIsDisplayed()
        landscape()
        compose.onNodeWithText("Логин").performScrollTo().assertTextContains("synthetic.engineer")
        compose.onNodeWithText("Пароль").performClick().performTextInput("synthetic-only-not-a-credential")
        showIme("Пароль")
        compose.onNodeWithText("Войти").performScrollTo().assertIsDisplayed().assertIsEnabled()
        recreate()
        compose.onNodeWithText("Логин").performScrollTo().assertTextContains("synthetic.engineer")
        assertEquals("Password must not be saved in instance state","",compose.onNodeWithText("Пароль").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    @Test fun listAndDetailStayReachableInLandscapeAndAfterRecreation() {
        seedAccount(); launch()
        compose.onNodeWithText("FSM • Мои заявки").assertIsDisplayed()
        compose.onNodeWithText(order.title,substring=true).performScrollTo().performClick()
        compose.onNodeWithText("Начать осмотр").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Назад").performScrollTo().performClick()
        landscape()
        compose.onNodeWithText(order.title,substring=true).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Начать осмотр").performScrollTo().assertIsDisplayed()
        recreate()
        assertEquals("detail",model.route); assertEquals(order.id,model.selected)
        compose.onNodeWithText("Начать осмотр").performScrollTo().assertIsDisplayed()
    }
    @Test fun unsavedFormSurvivesRotationThenConfirmedSaveSurvivesNavigationAndRecreation() {
        seedAccount(); launch(); enterForm()
        val before=savedDraft()
        val blocked=ExecutorGate(ownedRepo!!.db.transactionExecutor).also {executorGates.add(it); it.awaitEntered()}
        try {
            compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText(order.measurementSpecs.first().label,substring=true)).performScrollTo().performTextReplacement("2.40")
            compose.onNodeWithText("Комментарий").performScrollTo().performClick().performTextInput("synthetic unsaved edit")
            showIme("Комментарий")
            assertTrue("Pending local writes are not confirmed saves",model.saving)
            assertEquals(before,savedDraft())
            landscape()
            compose.onNodeWithText("Комментарий").performScrollTo().assertTextContains("synthetic unsaved edit")
            compose.onNode(hasSetTextAction() and hasText(order.measurementSpecs.first().label,substring=true)).performScrollTo().assertTextContains("2.40")
            assertTrue(requireNotNull(model.edits).answers.any {it.checked})
            compose.onNodeWithText("Комментарий").performScrollTo().performClick()
            showIme("Комментарий")
            compose.onNodeWithText("К фото и закрытию").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Назад").performScrollTo().performClick()
            compose.onNodeWithText("Изменить черновик").performScrollTo().performClick()
            assertTrue("Reopening waits for pending local saves",model.busy)
        } finally {blocked.release(); blocked.awaitFinished()}
        compose.waitUntil(10000) {model.route=="form" && !model.busy}
        awaitSaved("synthetic unsaved edit")
        val confirmed=savedDraft()
        assertEquals("2.40",confirmed.readings.first {it.key==order.measurementSpecs.first().key}.value)
        assertTrue(confirmed.answers.any {it.checked})
        compose.onNodeWithText("К фото и закрытию").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Выбрать фото").performScrollTo().assertIsDisplayed()
        recreate()
        assertEquals("close",model.route); assertEquals(confirmed,savedDraft())
        compose.onNodeWithText("Назад").performScrollTo().performClick()
        compose.onNodeWithText("Изменить черновик").performScrollTo().performClick()
        compose.waitUntil(10000) {model.route=="form" && !model.busy}
        compose.onNodeWithText("Комментарий").performScrollTo().assertTextContains("synthetic unsaved edit")
    }
    @Test fun savingRemainsTrueUntilEveryQueuedLocalWriteFinishes() {
        seedAccount(); launch(); enterForm()
        val first=savedDraft().copy(comment="first queued write")
        val latest=first.copy(comment="latest queued write")
        // FIFO Room transaction-executor barriers, not sleeps or probabilistic racing.
        val before=ExecutorGate(ownedRepo!!.db.transactionExecutor).also {executorGates.add(it); it.awaitEntered()}
        var between:ExecutorGate?=null
        try {
            scenario!!.onActivity {model.edit(first)} // First save enqueued behind 'before'.
            between=ExecutorGate(ownedRepo!!.db.transactionExecutor).also {executorGates.add(it)}
            scenario!!.onActivity {model.edit(latest)} // Second edit waits for first editLock.
            before.release()
            between.awaitEntered() // First transaction finished; second cannot execute yet.
            instrumentation.runOnMainSync {} // Drain first save's dispatched UI completion.
            assertEquals("first queued write",savedDraft().comment)
            assertEquals("latest queued write",model.edits?.comment)
            assertTrue("Saving must remain true while the latest local write is blocked",model.saving)
        } finally {before.release(); between?.release(); before.awaitFinished(); between?.awaitFinished()}
        awaitSaved("latest queued write")
    }
    @Test fun queuedRecreationKeepsLocksAndShowsLiveRoomAcknowledgement() {
        seedAccount(validDraft=true); launch(); go("close")
        compose.onNodeWithText("Выбрать фото").performScrollTo().assertIsDisplayed()
        runBlocking {ownedRepo!!.enqueue(order.id,LocationDto.missing("DENIED"))}
        compose.waitUntil(10000) {model.results.any {it.workOrderId==order.id && it.lifecycle=="QUEUED"}}
        val op=runBlocking {ownedRepo!!.store.operations().single()}
        landscape(); recreate()
        assertEquals("close",model.route)
        compose.onNodeWithText("Сохранить закрытие и поставить в очередь").assertDoesNotExist()
        compose.onNodeWithText("Выбрать фото").assertDoesNotExist()
        assertEquals(op.id,runBlocking {ownedRepo!!.store.operations().single().id})
        assertNull(UiText.closedNotice(model.results.single().lifecycle,order.status))
        // Seeded Room -> UI transition ONLY, not a Service/network end-to-end test.
        val result=savedDraft()
        runBlocking {
            ownedRepo!!.db.withTransaction {
                ownedRepo!!.applyCanonical(order.copy(status="CLOSED",revision=order.revision+1,result=ResultDto(result.id,result.answers,result.readings,result.comment,result.location,result.capturedAt,emptyList())))
                ownedRepo!!.store.operation(op.copy(state="ACKED"))
            }
        }
        compose.waitUntil(10000) {model.operations.any {it.id==op.id && it.state=="ACKED"} && model.results.single().lifecycle=="CLOSED"}
        compose.onNodeWithText("Заявка закрыта, результат отправлен",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FSM • Фото и результат закрытия").performScrollTo().assertIsDisplayed()
        assertEquals(op.id,model.operations.single().id)
    }
    @Test fun recreatedUiObservesRoomRetryAndAuthErrorsWithoutRawCodes() {
        seedAccount(validDraft=true)
        runBlocking {ownedRepo!!.enqueue(order.id,LocationDto.missing("DENIED"))}
        launch(); go("detail"); landscape(); recreate()
        val op=runBlocking {ownedRepo!!.store.operations().single()}
        runBlocking {ownedRepo!!.store.operation(op.copy(state="RETRY_WAIT",error="UNKNOWN_OUTCOME"))}
        compose.waitUntil(10000) {model.operations.any {it.error=="UNKNOWN_OUTCOME"}}
        compose.onNodeWithText(UiText.error("UNKNOWN_OUTCOME")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("UNKNOWN_OUTCOME",substring=true).assertDoesNotExist()
        runBlocking {ownedRepo!!.store.operation(op.copy(state="AUTH_REQUIRED",error="AUTH_REQUIRED"))}
        compose.waitUntil(10000) {model.operations.any {it.state=="AUTH_REQUIRED"}}
        compose.onNodeWithText(UiText.error("AUTH_REQUIRED")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Требует внимания: 1.",substring=true).performScrollTo().assertIsDisplayed()
        assertEquals(op.id,model.operations.single().id)
        assertEquals("QUEUED",model.results.single().lifecycle)
    }
    /** One bounded FIFO executor barrier; always released from test/cleanup finally paths. */
    private class ExecutorGate(executor:Executor) {
        private val entered=CountDownLatch(1)
        private val released=CountDownLatch(1)
        private val finished=CountDownLatch(1)
        @Volatile private var timedOut=false
        init {
            executor.execute {
                entered.countDown()
                try {timedOut=!released.await(60,TimeUnit.SECONDS)} finally {finished.countDown()}
            }
        }
        fun awaitEntered() {assertTrue("Room executor barrier entered",entered.await(10,TimeUnit.SECONDS))}
        fun release() {released.countDown()}
        fun awaitFinished() {
            assertTrue("Room executor barrier released",finished.await(10,TimeUnit.SECONDS))
            assertFalse("Controlled executor barrier must not expire",timedOut)
        }
    }
}
