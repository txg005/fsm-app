package com.fsm.ui

import com.fsm.data.ApiFailure
import com.fsm.data.InvalidInspection
import com.fsm.data.Operation

/** Presentation only: raw states and diagnostic codes remain unchanged in Room. */
object UiText {
    fun orderStatus(status:String)=when(status) {
        "ASSIGNED" -> "Назначена"
        "CLOSED" -> "Закрыта"
        else -> "Статус заявки неизвестен"
    }
    fun resultStatus(lifecycle:String)=when(lifecycle) {
        "DRAFT" -> "Черновик на устройстве"
        "QUEUED" -> "Закрытие сохранено на устройстве; ожидает подтверждения сервера"
        "CLOSED" -> "Результат подтверждён сервером"
        "CONFLICT" -> "Нужно сравнить локальную и серверную версии"
        else -> "Состояние локального результата неизвестно"
    }
    fun operationKind(kind:String)=when(kind) {
        "UPLOAD_PHOTO" -> "Отправка фото"
        "CLOSE_WORK_ORDER" -> "Отправка результата закрытия"
        else -> "Передача данных"
    }
    fun operationState(state:String)=when(state) {
        "PENDING" -> "Ожидает передачи"
        "RUNNING" -> "Передаётся; подтверждение ещё не получено"
        "RETRY_WAIT" -> "Ожидает повторной попытки"
        "AUTH_REQUIRED" -> "Для передачи нужен повторный вход"
        "CONFLICT" -> "Нужно сравнить версии"
        "FAILED" -> "Передача остановлена из-за ошибки"
        "ACKED" -> "Подтверждено сервером"
        "CANCELLED" -> "Операция отменена"
        else -> "Состояние передачи неизвестно"
    }
    fun actionError(failure:Exception)=when(failure) {
        is ApiFailure -> error(failure.code)
        is InvalidInspection -> failure.problems.joinToString("\n")
        else -> error(null)
    }
    fun error(code:String?)=when(code) {
        "INVALID_CREDENTIALS" -> "Не удалось войти. Проверьте логин и пароль."
        "AUTH_REQUIRED", "SESSION_EXPIRED" -> "Сессия недоступна. Войдите снова тем же пользователем для передачи работ."
        "NETWORK_UNAVAILABLE" -> "Нет связи с сервером. Проверьте подключение и повторите позже."
        "REQUEST_TIMEOUT" -> "Сервер не ответил вовремя. Подтверждение не получено."
        "UNKNOWN_OUTCOME" -> "Подтверждение не получено: сервер мог принять данные. Повторная передача использует ту же операцию."
        "REVISION_CONFLICT", "ORDER_CLOSED" -> "Заявка на сервере изменилась или уже закрыта. Сравните версии перед выбором."
        "PHOTO_ID_REUSED", "RESULT_ID_REUSED", "IDEMPOTENCY_KEY_REUSED" -> "Сервер отклонил повторную операцию. Не создавайте новое закрытие; обратитесь к техническому журналу."
        "PHOTO_TOO_LARGE", "REQUEST_TOO_LARGE" -> "Сервер отклонил слишком большой объём данных. Для исправления сначала откройте сравнение версий."
        "MALFORMED_REQUEST", "VALIDATION_ERROR" -> "Сервер отклонил данные. Для проверки и исправления сначала откройте сравнение версий."
        "PHOTO_CONTENT_INVALID", "PHOTO_MISSING" -> "Сервер не принял фото. Проверьте состояние передачи и технический журнал."
        "ACCESS_REVOKED", "NOT_FOUND" -> "Заявка или данные недоступны этому пользователю. Проверьте доступ к заявке."
        "RATE_LIMITED" -> "Сервер просит подождать перед следующей попыткой."
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE" -> "Сервер временно недоступен. Повторите позже."
        "LOCAL_INTEGRITY_ERROR" -> "Не удалось прочитать или проверить локальное фото. Передача остановлена; см. технический журнал."
        "PROTOCOL_ERROR" -> "Ответ сервера не удалось распознать. Проверьте состояние передачи и технический журнал."
        else -> "Не удалось выполнить действие. Проверьте состояние данных, подключение и свободное место. Подробности — в техническом журнале."
    }
    fun location(status:String,reason:String?)=when(status) {
        "CAPTURED" -> "Местоположение зафиксировано"
        "MISSING" -> when(reason) {
            "DENIED" -> "Местоположение не получено: нет разрешения"
            "PROVIDER_DISABLED" -> "Местоположение не получено: геолокация выключена"
            "TIMEOUT" -> "Местоположение не получено за время ожидания"
            else -> "Местоположение недоступно"
        }
        else -> "Состояние местоположения неизвестно"
    }
    fun closedNotice(lifecycle:String?,status:String?):String? =
        if(lifecycle=="CLOSED" || (lifecycle==null && status=="CLOSED"))
            "Заявка закрыта, результат отправлен. Подтверждено сервером; редактирование и повторное закрытие недоступны."
        else null

    fun closeTitle(lifecycle:String?,status:String?) =
        if(closedNotice(lifecycle,status)!=null) "Фото и результат закрытия" else "Фото и закрытие"

    /** Counts retained outbox records, not bytes, a single run, or time remaining. */
    fun progress(operations:List<Operation>):String {
        if(operations.isEmpty()) return "В сохранённой очереди нет операций."
        val confirmed=operations.count { it.state=="ACKED" }
        val running=operations.count { it.state=="RUNNING" }
        val waiting=operations.count { it.state in listOf("PENDING","RETRY_WAIT") }
        val blocked=operations.count { it.state in listOf("AUTH_REQUIRED","CONFLICT","FAILED") }
        val cancelled=operations.count { it.state=="CANCELLED" }
        val unknown=operations.size-confirmed-running-waiting-blocked-cancelled
        return buildList {
            add("Сохранённая очередь: подтверждено $confirmed из ${operations.size} операций (фото и закрытия).")
            if(running>0) add("Передаётся: $running.")
            if(waiting>0) add("Ожидает передачи или повтора: $waiting.")
            if(blocked>0) add("Требует внимания: $blocked.")
            if(cancelled>0) add("Отменено: $cancelled.")
            if(unknown>0) add("Состояние неизвестно: $unknown.")
        }.joinToString(" ")
    }
}
