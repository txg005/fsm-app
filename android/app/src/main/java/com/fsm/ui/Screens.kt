package com.fsm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fsm.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun AppScreen(vm:FsmModel) {
    val ctx=LocalContext.current
    var logout by remember { mutableStateOf(false) }
    var accept by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf("ALL") }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var captureOrder by rememberSaveable { mutableStateOf<String?>(null) }
    var captureAccount by rememberSaveable { mutableStateOf<String?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null && vm.selected!=null) vm.importPhoto(uri,requireNotNull(vm.selected)) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path=capturePath; val owner=captureOrder
        if(ok && path!=null && owner!=null && captureAccount==vm.repo?.employeeId) vm.action {
            try { Media.importPhoto(ctx,requireNotNull(vm.repo),owner,Uri.fromFile(File(path))) } finally { File(path).delete() }
        } else path?.let { File(it).delete() }
        capturePath=null; captureOrder=null; captureAccount=null
    }
    val location=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.close() }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.sync() }
    val row=vm.orders.find { it.id==vm.selected }
    val order=row?.let { wire.decodeFromString<WorkOrderDto>(it.json) }
    val result=vm.results.find { it.workOrderId==vm.selected }
    val content=result?.let { wire.decodeFromString<ResultWriteDto>(it.json) }
    val photos=vm.photos.filter { it.workOrderId==vm.selected }
    val editable=result?.lifecycle=="DRAFT" && row?.available==true
    BackHandler(enabled=vm.route !in listOf("login","list")) { vm.go(if(vm.route in listOf("form","close","conflict")) "detail" else "list") }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("FSM • ${when(vm.route) { "login"->"Вход"; "list"->"Мои заявки"; "detail"->"Карточка"; "form"->"Результат осмотра"; "close"->"Фото и закрытие"; "logs"->"Технический журнал"; else->"Конфликт версий" }}",style=MaterialTheme.typography.headlineSmall)
            if(vm.route !in listOf("login","list")) Button(onClick={ vm.go(if(vm.route in listOf("form","close","conflict")) "detail" else "list") }) { Text("Назад") }
            if(vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            when(vm.route) {
                "login" -> {
                    var login by rememberSaveable { mutableStateOf("") }; var password by remember { mutableStateOf("") }
                    OutlinedTextField(login,{login=it},label={Text("Логин")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                    OutlinedTextField(password,{password=it},label={Text("Пароль")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
                    Button(enabled=!vm.busy && login.isNotEmpty() && password.isNotEmpty(),onClick={vm.login(login,password); password=""}) {Text("Войти")}
                    Text("Для первого входа нужна сеть. Demo credentials задаются в backend, встроенных паролей нет.")
                }
                "list" -> {
                    Text("Список читается из Room. Сохранено локально ≠ отправлено.")
                    Button(enabled=!vm.busy,onClick={vm.refresh()}) {Text("Загрузить метаданные")}
                    Button(onClick={ if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.sync() }) {Text("Отправить работы • Service")}
                    Text("Уведомление показывает передачу; отказ в уведомлениях не запрещает Service.")
                    Button(onClick={vm.go("logs")}) {Text("Очередь и технические логи")}
                    Button(enabled=!vm.busy,onClick={logout=true}) {Text("Выйти")}
                    if(vm.orders.isEmpty()) Text("Нет кэша заявок. Загрузите метаданные при доступном API.")
                    vm.orders.forEach { item ->
                        val dto=wire.decodeFromString<WorkOrderDto>(item.json)
                        val local=vm.results.find { it.workOrderId==item.id }
                        OutlinedButton(onClick={vm.go("detail",item.id)},modifier=Modifier.fillMaxWidth()) {Text("${dto.title}\n${local?.lifecycle ?: dto.status}${if(!item.available) " • доступ отозван" else ""}")}
                    }
                }
                "detail" -> if(order!=null) {
                    Text(order.title,style=MaterialTheme.typography.titleLarge); Text(order.description)
                    Text("Срок: ${order.dueAt ?: "не задан"}; revision ${order.revision}")
                    Text("Оборудование: ${order.equipmentId}")
                    Text("Состояние: ${result?.lifecycle ?: order.status}")
                    if(row?.available==false) Text("Доступ отозван. Локальная копия сохранена; отправка заблокирована.")
                    order.checklistItems.forEach { Text("${if(it.required) "* " else ""}${it.text}") }
                    if(result==null && order.status=="ASSIGNED" && row?.available==true) Button(enabled=!vm.busy,onClick={vm.begin()}) {Text("Начать осмотр")}
                    if(editable) {
                        Button(enabled=!vm.busy,onClick={vm.loadForm()}) {Text("Изменить черновик")}
                        Button(enabled=!vm.busy,onClick={vm.go("close")}) {Text("Фото / закрытие")}
                        OutlinedButton(enabled=!vm.busy,onClick={delete=true}) {Text("Удалить черновик")}
                    }
                    content?.let {
                        Text("Комментарий: ${it.comment}")
                        it.readings.forEach { r -> Text("${r.key}: ${r.value}") }
                        Text("GPS: ${it.location.status} ${it.location.reason ?: ""}")
                    }
                    photos.forEach { photo -> PhotoCard(photo); if(photo.localPath==null && photo.serverPhotoId!=null) OutlinedButton(enabled=!vm.busy,onClick={vm.action { vm.repo?.cachePhoto(photo.id) }}) {Text("Скачать фото для offline")} }
                    vm.operations.filter { it.workOrderId==order.id }.forEach { Text("${it.kind}: ${it.state} ${it.error ?: ""}") }
                    if(result?.lifecycle=="CONFLICT") Button(enabled=!vm.busy,onClick={vm.conflict()}) {Text("Сравнить версии")}
                    if(vm.operations.any { it.workOrderId==order.id && it.state=="FAILED" }) Button(enabled=!vm.busy,onClick={vm.resolve(false)}) {Text("Исправить после окончательного отказа")}
                    if(result?.lifecycle=="QUEUED") { Text("Правки заблокированы до однозначного исхода. Отмена передачи не отменяет закрытие."); Button(onClick={vm.sync()}) {Text("Передать Service") } }
                } else Text("Заявка загружается из Room или больше недоступна")
                "form" -> {
                    val draft=vm.edits?.takeIf { it.id==result?.id } ?: content
                    if(draft!=null && order!=null) {
                        Text(if(vm.saving) "Сохранение…" else "Ввод сохраняется на устройстве")
                        order.checklistItems.forEach { item ->
                            Row { Checkbox(checked=draft.answers.any { it.itemId==item.id && it.checked },enabled=editable&&!vm.busy,onCheckedChange={ checked -> vm.edit(draft.copy(answers=draft.answers.filterNot { it.itemId==item.id }+AnswerDto(item.id,checked))) }); Text(item.text+(if(item.required) " *" else ""),Modifier.padding(top=12.dp)) }
                        }
                        order.measurementSpecs.forEach { spec ->
                            OutlinedTextField(value=draft.readings.find { it.key==spec.key }?.value ?: "",onValueChange={ value -> vm.edit(draft.copy(readings=draft.readings.filterNot { it.key==spec.key }+if(value.isEmpty()) emptyList() else listOf(ReadingDto(spec.key,value)))) },enabled=editable&&!vm.busy,label={Text("${spec.label} (${spec.unit})${if(spec.required) " *" else ""}")},modifier=Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(draft.comment,{vm.edit(draft.copy(comment=it))},enabled=editable&&!vm.busy,label={Text("Комментарий")},modifier=Modifier.fillMaxWidth(),minLines=3)
                        Button(enabled=!vm.saving&&!vm.busy,onClick={vm.go("close")}) {Text("К фото и закрытию")}
                    }
                }
                "close" -> {
                    Text("Фото необязательны. При закрытии автоматически запрашивается GPS (до 15 секунд); отказ не мешает закрытию.")
                    if(editable) {
                        Button(enabled=!vm.busy && photos.size<10,onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}) {Text("Выбрать фото")}
                        Button(enabled=!vm.busy && photos.size<10,onClick={
                            val file=File(ctx.filesDir,"capture/${vm.repo?.employeeId}/${uuid()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
                            capturePath=file.absolutePath; captureOrder=vm.selected; captureAccount=vm.repo?.employeeId
                            try { camera.launch(FileProvider.getUriForFile(ctx,"${ctx.packageName}.files",file)) } catch(_:Exception) { file.delete(); capturePath=null; vm.error="Приложение камеры недоступно" }
                        }) {Text("Камера")}
                    }
                    photos.forEach { p -> PhotoCard(p); if(editable) OutlinedButton(enabled=!vm.busy,onClick={vm.removePhoto(p.id)}) {Text("Удалить фото") } }
                    if(editable) Button(enabled=!vm.busy&&!vm.saving,onClick={
                        val fine=ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        val coarse=ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        if(!fine&&!coarse) location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) else vm.close()
                    }) {Text("Закрыть и поставить в очередь")}
                }
                "logs" -> {
                    Button(onClick={vm.sync()}) {Text("Запустить Service")}; OutlinedButton(onClick={vm.stopSync()}) {Text("Остановить передачу")}
                    Text("После force-stop автоматическое продолжение не гарантируется. Данные остаются до следующего открытия.")
                    vm.operations.forEach { Text("${it.kind} ${it.state} • попыток ${it.attempts}\n${it.error ?: ""} • ${it.id}") }
                    Row { listOf("ALL","INFO","WARN","ERROR").forEach { l -> TextButton(onClick={level=l}) {Text(l)} } }
                    vm.logs.filter { level=="ALL" || it.level==level }.forEach { Text("${instant(it.at)} ${it.level}\n${it.event}: ${it.message}") }
                }
                "conflict" -> {
                    Text("Локальная frozen копия сохранена. Автоматического merge нет.")
                    Text("Локальная версия",style=MaterialTheme.typography.titleMedium); Text(result?.json ?: "Нет")
                    Text("Серверная версия",style=MaterialTheme.typography.titleMedium); Text(vm.shadow ?: "Не удалось загрузить серверную версию")
                    Button(enabled=!vm.busy,onClick={accept=true}) {Text("Принять серверную…")}
                    Button(enabled=!vm.busy,onClick={vm.resolve(false)}) {Text("Вернуться к черновику, если сервер ASSIGNED")}
                }
            }
        }
    }
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("Выйти с сохранением работы?")},text={Text("Черновики и pending сохранятся в изолированном хранилище этого аккаунта. Для отправки войдите тем же пользователем. Offline logout не гарантирует отзыв сессии на сервере.")},confirmButton={TextButton(onClick={logout=false;vm.logout()}) {Text("Выйти с сохранением")}},dismissButton={TextButton(onClick={logout=false}) {Text("Отмена")}})
    if(accept) AlertDialog(onDismissRequest={accept=false},title={Text("Заменить локальный результат?")},text={Text("Локальный результат и его очередь будут удалены только после явного выбора серверной версии.")},confirmButton={TextButton(onClick={accept=false;vm.resolve(true)}) {Text("Принять серверную")}},dismissButton={TextButton(onClick={accept=false}) {Text("Отмена")}})
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Удалить локальный черновик и его фото?")},confirmButton={TextButton(onClick={delete=false;vm.deleteDraft()}) {Text("Удалить")}},dismissButton={TextButton(onClick={delete=false}) {Text("Отмена")}})
}
@Composable private fun PhotoCard(photo:Photo) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,photo.localPath) { value=withContext(Dispatchers.IO) { photo.localPath?.let { path -> android.graphics.BitmapFactory.decodeFile(path,android.graphics.BitmapFactory.Options().apply { inSampleSize=4 }) } } }
    bitmap?.let { Image(it.asImageBitmap(),"Прикреплённое фото",Modifier.fillMaxWidth().heightIn(max=240.dp)) }
    Text(if(bitmap==null) "Фото ещё не доступно offline" else "Фото сохранено на устройстве${if(photo.serverPhotoId!=null) " • upload подтверждён" else ""}")
}
