package com.fsm.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.fsm.service.SyncService
import com.fsm.service.SyncWorker
import com.fsm.sync.SyncGate
import com.fsm.sync.sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in fresh API36 emulator, manually installed WITHOUT -g. Exclude from ordinary21.
 * Real system DENY / native picker Back, then committed local DAO + file assertions.
 * No backend, Service, seeded permission result, Room reopen, process-death or GPS accuracy claim.
 */
@RunWith(AndroidJUnit4::class)
class PermissionUiTest {
    private val compose=createEmptyComposeRule()
    // Include rule setup/teardown: neither Compose trees nor system UI exceptions go to XML/logs.
    private val sanitized=TestRule { base,_ -> object:Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch(failure:Throwable) { throw safeFailure(failure) }
        }
    }}
    // V1 diagnostic: closed type only; NEVER copy message, cause, suppressed, class or method.
    private fun safeFailure(original:Throwable):AssertionError {
        val kind=when(original) {
            is ComposeTimeoutException, is TimeoutCancellationException, is java.util.concurrent.TimeoutException -> "TIMEOUT"
            is AssertionError -> "ASSERTION"
            is SecurityException -> "SECURITY"
            is IllegalStateException -> "STATE"
            else -> "OTHER"
        }
        val lines=original.stackTrace.asSequence().filter {
            it.fileName=="PermissionUiTest.kt" &&
                (it.className=="com.fsm.ui.PermissionUiTest" || it.className.startsWith("com.fsm.ui.PermissionUiTest\$")) &&
                it.lineNumber in 1..SOURCE_LINE_LIMIT
        }.map { it.lineNumber }.distinct().take(3).toList()
        return AssertionError("PERMISSION_UI_FAILED_V1_TYPE_$kind").also { safe ->
            safe.stackTrace=lines.map {
                StackTraceElement("com.fsm.ui.PermissionUiTest","checkpoint","PermissionUiTest.kt",it)
            }.toTypedArray()
        }
    }
    private val SOURCE_LINE_LIMIT=361
    @get:Rule val rules:RuleChain=RuleChain.outerRule(sanitized).around(compose)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FsmApp
    private val automation get()=instrumentation.uiAutomation
    private val wm get()=WorkManager.getInstance(app)
    private val gateOwner=Any()
    private var ownsGate=false
    private var originalAutomationFlags:Int?=null
    private var deadline=0L
    private var scenario:ActivityScenario<MainActivity>?=null
    private lateinit var activity:MainActivity
    private lateinit var model:FsmModel
    private lateinit var order:WorkOrderDto
    private var accountId:String?=null
    private var repo:Repository?=null
    private var photoFile:File?=null

    private data class DiskPhoto(val name:String,val size:Long,val hash:String)
    private data class Snapshot(val order:WorkOrder,val result:InspectionResult,
        val photos:List<Photo>,val operations:List<Operation>,val disk:List<DiskPhoto>)

    private fun remaining(cap:Long=10000):Long {
        val left=deadline-SystemClock.elapsedRealtime()
        check(left>0)
        return minOf(left,cap)
    }
    private fun <T> local(block:suspend ()->T):T=runBlocking {
        withTimeout(remaining()) { block() }
    }
    private fun main(block:()->Unit) { remaining(); instrumentation.runOnMainSync(block); remaining() }
    private fun waitFor(condition:()->Boolean) { compose.waitUntil(remaining(),condition); remaining() }
    private fun idle() { remaining(); instrumentation.waitForIdleSync(); compose.waitForIdle(); remaining() }
    private fun denied():Boolean=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        .all { app.checkSelfPermission(it)==PackageManager.PERMISSION_DENIED }
    @Suppress("DEPRECATION")
    private fun noService():Boolean=app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .none { it.service.packageName==app.packageName && it.service.className==SyncService::class.java.name }

    private fun runCase(body:()->Unit) {
        deadline=SystemClock.elapsedRealtime()+90000
        var primary:Throwable?=null
        try {
            preflight()
            seedAndOpen()
            body()
            check(noService() && model.error==null && !model.busy && !model.saving)
            remaining()
        } catch(failure:Throwable) {
            primary=failure
            throw failure
        } finally {
            try {
                // Separate finite 20s cleanup. Host watchdog is 240s for both cases; abort is not PASS.
                deadline=SystemClock.elapsedRealtime()+20000
                try { cleanup() } finally {
                    try {
                        originalAutomationFlags?.let { flags ->
                            val info=requireNotNull(automation.serviceInfo)
                            info.flags=flags
                            automation.serviceInfo=info
                        }
                    } finally {
                        if(ownsGate) { SyncGate.mutex.unlock(gateOwner); ownsGate=false }
                    }
                }
                remaining(20000)
            } catch(cleanupFailure:Throwable) {
                // Both paths still FAIL. Keep the first body failure if cleanup also fails;
                // attach neither Throwable as cause/suppressed to the public sanitized error.
                throw primary ?: cleanupFailure
            }
        }
    }

    private fun preflight() {
        check(Build.VERSION.SDK_INT==36 && noService() && denied())
        // CI additionally proves fresh install, user 0, one owned emulator, no install -g.
        // No cancellation/open-account/deletion/fixture writes before these ownership checks.
        check(app.repository==null && app.activeId()==null)
        check(app.getSharedPreferences("account",Context.MODE_PRIVATE).all.isEmpty())
        check(app.databaseList().none { it.startsWith("fsm_") })
        check(File(app.filesDir,"accounts").listFiles().isNullOrEmpty())
        check(File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().none { it.name.startsWith("tokens_") })
        check(wm.getWorkInfosByTag(SyncWorker::class.java.name).get(remaining(),TimeUnit.MILLISECONDS).all { it.state.isFinished })
        local { SyncGate.mutex.lock(gateOwner) }; ownsGate=true
        // Save the value BEFORE mutating the possibly shared ServiceInfo instance.
        val info=requireNotNull(automation.serviceInfo)
        originalAutomationFlags=info.flags
        info.flags=info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=info
    }

    private fun seedAndOpen() {
        val source=wire.parseToJsonElement(instrumentation.context.assets.open("contract-fixtures.json").bufferedReader().use { it.readText() }).jsonObject
        val fixture=wire.decodeFromJsonElement<SnapshotDto>(source.getValue("snapshotBeforeClose"))
        val id=uuid(); accountId=id
        val r=app.open(id); repo=r
        order=fixture.workOrders.single().copy(assigneeId=id)
        local {
            r.db.withTransaction {
                r.store.employee(Employee(id,wire.encodeToString(fixture.employee.copy(id=id))))
                fixture.sites.forEach { r.store.site(Site(it.id,wire.encodeToString(it))) }
                fixture.equipment.forEach { r.store.equipment(Equipment(it.id,it.siteId,wire.encodeToString(it))) }
                r.applyCanonical(order)
                r.create(order.id)
                val draft=wire.decodeFromString<ResultWriteDto>(requireNotNull(r.store.result(order.id)).json)
                r.save(order.id,draft.copy(answers=order.checklistItems.map { AnswerDto(it.id,true) },
                    readings=order.measurementSpecs.map { ReadingDto(it.key,"2.40") },comment="synthetic permission check"))
            }
            // Register under the production mutex BEFORE model cleanupOrphans can start.
            Media.filesMutex.withLock {
                val bytes=Base64.decode(source.getValue("photoUpload").jsonObject.getValue("jpegBase64").jsonPrimitive.content,Base64.DEFAULT)
                check(bytes.size==631 && sha256(bytes)=="4a0043beb47e76e4bf6a12e9d93832dc69d596c8d1b114870e17f9d1420d61bf")
                val photoId=uuid(); val file=File(r.files,"$photoId.jpg"); check(file.createNewFile()); photoFile=file
                java.io.FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                r.addPhoto(order.id,file,photoId,sha256(bytes))
            }
        }
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity=it; model=ViewModelProvider(it)[FsmModel::class.java] }
        waitFor { model.orders.any { it.id==order.id } && model.photos.size==1 && model.results.size==1 && !model.busy && !model.saving }
        // Serialize with running sweeps. Every setup file is already registered, so even a
        // later-starting model sweep cannot remove it; no unregistered setup file remains.
        local { Media.filesMutex.withLock { check(photoFile!!.isFile) } }
        main { model.go("detail",order.id) }
        compose.onNodeWithText("Фото и закрытие").performScrollTo().performClick()
        waitFor { model.route=="close" && model.selected==order.id && !model.busy && !model.saving }
        idle()
        check(snapshot().operations.isEmpty())
    }

    private fun snapshot():Snapshot {
        val r=requireNotNull(repo)
        return local {
            r.db.withTransaction {
                val photos=r.store.photos(order.id)
                val disk=r.files.listFiles().orEmpty().sortedBy { it.name }.map { file ->
                    check(file.isFile && file.canonicalFile.parentFile==r.files.canonicalFile)
                    DiskPhoto(file.name,file.length(),sha256(file.readBytes()))
                }
                Snapshot(requireNotNull(r.store.order(order.id)),requireNotNull(r.store.result(order.id)),photos,r.store.operations(),disk)
            }
        }
    }

    private fun focusedSystemWindow(packageName:String):Boolean {
        val windows=automation.windows
        try {
            return windows.any { window ->
                if(!window.isFocused || !window.isActive) false else {
                    val root=window.root
                    try { root?.packageName?.toString()==packageName } finally { root?.recycle() }
                }
            }
        } finally { windows.forEach { it.recycle() } }
    }
    private fun clickOrdinaryDeny(packageName:String) {
        val windows=automation.windows
        try {
            val focused=windows.filter { it.isFocused && it.isActive }
            check(focused.size==1)
            val root=requireNotNull(focused.single().root)
            try {
                check(root.packageName?.toString()==packageName)
                val nodes=root.findAccessibilityNodeInfosByViewId("$packageName:id/permission_deny_button")
                try {
                    val visible=nodes.filter { it.isVisibleToUser && it.isEnabled && it.isClickable }
                    check(visible.size==1)
                    check(visible.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally { nodes.forEach { it.recycle() } }
            } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }
    private fun sameResumedWindow(expectedActivity:MainActivity,expectedModel:FsmModel):Boolean {
        var ready=false
        main {
            ready=activity===expectedActivity && !activity.isDestroyed && activity.lifecycle.currentState==Lifecycle.State.RESUMED &&
                activity.window.decorView.isAttachedToWindow && activity.window.decorView.hasWindowFocus() &&
                ViewModelProvider(activity)[FsmModel::class.java]===expectedModel && model.selected==order.id
        }
        return ready
    }

    @Test fun denyingLocationInSystemDialogStillQueuesValidDraftDurably()=runCase {
        val before=snapshot()
        check(before.result.lifecycle=="DRAFT" && before.photos.size==1 && before.disk.size==1)
        val original=wire.decodeFromString<ResultWriteDto>(before.result.json)
        check(Validation.errors(order,original).isEmpty() && denied())
        // PackageManager.getPermissionControllerPackageName is not in the public SDK36.
        // Resolve the pinned manifest action instead; do not launch it or synthesize a result.
        val controllerActivity=requireNotNull(app.packageManager.resolveActivity(
            Intent("android.content.pm.action.REQUEST_PERMISSIONS"),PackageManager.MATCH_DEFAULT_ONLY)).activityInfo
        check(controllerActivity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)
        val controller=controllerActivity.packageName
        compose.onNodeWithText("Сохранить закрытие и поставить в очередь").performScrollTo().performClick()
        waitFor { focusedSystemWindow(controller) }
        // Real ordinary deny, not revoke/grant, don't-ask-again, a seeded result, or direct callback.
        clickOrdinaryDeny(controller)
        waitFor { denied() && model.route=="detail" && !model.busy && !model.saving && model.results.single().lifecycle=="QUEUED" }
        idle()
        compose.onNodeWithText("Результат: Закрытие сохранено на устройстве; ожидает подтверждения сервера").assertExists()
        val after=snapshot()
        check(after.order==before.order && after.photos==before.photos && after.disk==before.disk)
        check(after.result.id==before.result.id && after.result.baseRevision==before.result.baseRevision && after.result.lifecycle=="QUEUED")
        val queued=wire.decodeFromString<ResultWriteDto>(after.result.json)
        check(queued.location==LocationDto.missing("DENIED")) // status/reason AND every nullable field.
        check(queued.answers==original.answers && queued.readings==original.readings && queued.comment==original.comment)
        check(queued.photoIds==before.photos.map { it.id } && queued.id==original.id)
        check(after.operations.size==2 && after.operations.map { it.id }.distinct().size==2)
        check(after.operations.all { it.workOrderId==order.id && it.state=="PENDING" && it.attempts==0 && it.error==null && it.wireBody==null && it.nextAttemptAt==0L })
        val upload=after.operations.single { it.kind=="UPLOAD_PHOTO" }
        val close=after.operations.single { it.kind=="CLOSE_WORK_ORDER" }
        val photo=after.photos.single()
        check(upload.photoId==photo.id && upload.sequence<close.sequence)
        check(wire.decodeFromString<PhotoUploadMetadata>(upload.frozenPayload)==PhotoUploadMetadata(photo.id,"image/jpeg",photo.byteSize,photo.sha256))
        check(wire.decodeFromString<CloseRequest>(close.frozenPayload)==CloseRequest(before.result.baseRevision,queued))
        check(wire.decodeFromString<List<String>>(close.dependencies)==listOf(upload.id))
        // Independent committed transaction: IDs, frozen bytes/hashes, rows and JPEG remain stable.
        val frozenHash=sha256(close.frozenPayload.toByteArray(Charsets.UTF_8))
        val again=snapshot()
        check(again==after && sha256(again.operations.single { it.id==close.id }.frozenPayload.toByteArray(Charsets.UTF_8))==frozenHash)
        check(denied() && model.error==null)
    }

    @Test fun cancellingSystemPhotoPickerLeavesDraftPhotoAndOutboxUnchanged()=runCase {
        val before=snapshot()
        check(before.result.lifecycle=="DRAFT" && before.photos.size==1 && before.disk.size==1 && before.operations.isEmpty())
        val contract=ActivityResultContracts.PickVisualMedia()
        val intent=contract.createIntent(app,PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        check(intent.action==MediaStore.ACTION_PICK_IMAGES)
        val resolved=requireNotNull(app.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)).activityInfo
        check(resolved.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)
        val originalActivity=activity; val originalModel=model
        compose.onNodeWithText("Выбрать фото").performScrollTo().performClick()
        waitFor {
            var left=false
            main { left=activity.lifecycle.currentState!=Lifecycle.State.RESUMED }
            left && focusedSystemWindow(resolved.packageName)
        }
        check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitFor { sameResumedWindow(originalActivity,originalModel) && !focusedSystemWindow(resolved.packageName) }
        // AOSP16 delivers pending results before resume; Activity1.10.1 dispatch/parse/callback
        // is synchronous (pending registry delivery at ON_START). Idle is AFTER that round-trip,
        // not merely a visible app, and no direct null callback is invoked by the test.
        idle()
        check(model===originalModel && model.route=="close" && model.selected==order.id && !model.busy && !model.saving && model.error==null)
        compose.onNodeWithText("Выбрать фото").assertExists()
        check(snapshot()==before)
    }

    private fun cleanup() {
        val r=repo; val id=accountId
        try {
            scenario?.close()
            if(::model.isInitialized) {
                main { model.viewModelScope.cancel() }
                local { requireNotNull(model.viewModelScope.coroutineContext[Job]).join() }
            }
            if(r!=null && id!=null) {
                check(ownsGate && SyncGate.mutex.holdsLock(gateOwner) && noService())
                check(app.activeId()==id && app.repository===r && r.employeeId==id)
                wm.cancelUniqueWork("sync_$id").result.get(remaining(),TimeUnit.MILLISECONDS)
                check(wm.getWorkInfosForUniqueWork("sync_$id").get(remaining(),TimeUnit.MILLISECONDS).all { it.state.isFinished })
                // Gate was held BEFORE account/model and continuously through clearActive:
                // SyncWorker's isLocked branch returns BEFORE engine/store/postcheck. No Service.
                app.clearActive()
                r.db.close()
                check(app.deleteDatabase("fsm_$id.db"))
                photoFile?.let { check(it.canonicalFile.parentFile==r.files.canonicalFile && it.delete()) }
                check(r.files.listFiles().isNullOrEmpty() && r.files.delete())
                check(r.files.parentFile!!.delete())
                app.deleteSharedPreferences("tokens_$id")
            }
        } finally {
            // Unknown writer => never close/delete Room/photos. Deactivate ONLY our account to
            // prevent a later Worker entering engine once the gate is released; retain to teardown.
            if(r!=null && id!=null && app.repository===r && app.activeId()==id) app.clearActive()
        }
    }
}
