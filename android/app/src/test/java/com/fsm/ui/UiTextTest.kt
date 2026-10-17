package com.fsm.ui

import com.fsm.data.Api
import com.fsm.data.ApiFailure
import com.fsm.data.InvalidInspection
import com.fsm.data.Operation
import org.junit.Assert.*
import org.junit.Test

class UiTextTest {
    private fun operation(state:String,kind:String="UPLOAD_PHOTO",error:String?=null)=
        Operation("operation-id",kind,"order-id",sequence=1,state=state,frozenPayload="{}",error=error)

    @Test fun allBusinessAndResultStatesHaveRussianLabels() {
        assertEquals("Назначена",UiText.orderStatus("ASSIGNED"))
        assertEquals("Закрыта",UiText.orderStatus("CLOSED"))
        mapOf(
            "DRAFT" to "Черновик на устройстве",
            "QUEUED" to "Закрытие сохранено на устройстве; ожидает подтверждения сервера",
            "CLOSED" to "Результат подтверждён сервером",
            "CONFLICT" to "Нужно сравнить локальную и серверную версии"
        ).forEach { (state,label) -> assertEquals(label,UiText.resultStatus(state)) }
        assertNotEquals(UiText.orderStatus("CLOSED"),UiText.resultStatus("QUEUED"))
    }
    @Test fun allOperationStatesAndKindsHaveRussianLabels() {
        mapOf(
            "PENDING" to "Ожидает передачи",
            "RUNNING" to "Передаётся; подтверждение ещё не получено",
            "RETRY_WAIT" to "Ожидает повторной попытки",
            "AUTH_REQUIRED" to "Для передачи нужен повторный вход",
            "CONFLICT" to "Нужно сравнить версии",
            "FAILED" to "Передача остановлена из-за ошибки",
            "ACKED" to "Подтверждено сервером",
            "CANCELLED" to "Операция отменена"
        ).forEach { (state,label) -> assertEquals(label,UiText.operationState(state)) }
        assertEquals("Отправка фото",UiText.operationKind("UPLOAD_PHOTO"))
        assertEquals("Отправка результата закрытия",UiText.operationKind("CLOSE_WORK_ORDER"))
    }
    @Test fun unknownValuesNeverEchoUntrustedInput() {
        val input="unexpected body: password=private-value Bearer private-token /private/path"
        listOf(UiText.orderStatus(input),UiText.resultStatus(input),UiText.operationState(input),
            UiText.operationKind(input),UiText.location(input,input),UiText.location("MISSING",input),
            UiText.error(input),UiText.actionError(IllegalStateException(input)),
            UiText.actionError(ApiFailure(500,input))).forEach { text ->
            assertFalse(text.contains(input)); assertFalse(text.contains("private")); assertTrue(text.isNotBlank())
        }
        assertEquals(UiText.error(null),UiText.error(input))
    }
    @Test fun everyKnownApiAndSyncErrorHasSafeExplanation() {
        val codes=Api.safeCodes+setOf("PROTOCOL_ERROR","NETWORK_UNAVAILABLE","UNKNOWN_OUTCOME","LOCAL_INTEGRITY_ERROR","ACCESS_REVOKED")
        codes.forEach { code ->
            val text=UiText.error(code)
            assertNotEquals("Missing mapping: $code",UiText.error(null),text)
            assertFalse(text.contains(code))
            assertEquals(text,UiText.actionError(ApiFailure(400,code)))
        }
        assertTrue(UiText.error("UNKNOWN_OUTCOME").contains("сервер мог принять"))
        assertFalse(UiText.error("UNKNOWN_OUTCOME").contains("не отправлены"))
    }
    @Test fun requiredFieldValidationRemainsActionable() {
        val problems=listOf("Обязательный пункт: Осмотр","Требуется Давление","Давление: вне диапазона")
        assertEquals(problems.joinToString("\n"),UiText.actionError(InvalidInspection(problems)))
    }
    @Test fun gpsMissingReasonsDoNotClaimCoordinatesWereCaptured() {
        assertEquals("Местоположение зафиксировано",UiText.location("CAPTURED",null))
        mapOf(
            "DENIED" to "Местоположение не получено: нет разрешения",
            "PROVIDER_DISABLED" to "Местоположение не получено: геолокация выключена",
            "TIMEOUT" to "Местоположение не получено за время ожидания",
            "UNAVAILABLE" to "Местоположение недоступно"
        ).forEach { (reason,label) -> assertEquals(label,UiText.location("MISSING",reason)) }
    }
    @Test fun mixedQueueProgressUsesActualRetainedRecordsWithoutPercentages() {
        val states=listOf("ACKED","RUNNING","PENDING","RETRY_WAIT","AUTH_REQUIRED","CONFLICT","FAILED","CANCELLED","FUTURE")
        val text=UiText.progress(states.map { operation(it) })
        assertEquals("Сохранённая очередь: подтверждено 1 из 9 операций (фото и закрытия). Передаётся: 1. Ожидает передачи или повтора: 2. Требует внимания: 3. Отменено: 1. Состояние неизвестно: 1.",text)
        assertFalse(text.contains("%")); assertFalse(text.contains("FUTURE"))
    }
    @Test fun uploadAcknowledgementDoesNotMeanCloseCompleted() {
        val ops=listOf(operation("ACKED"),operation("PENDING","CLOSE_WORK_ORDER"))
        assertTrue(UiText.progress(ops).contains("подтверждено 1 из 2"))
        assertNull(UiText.closedNotice("QUEUED","ASSIGNED"))
        assertEquals("Фото и закрытие",UiText.closeTitle("QUEUED","ASSIGNED"))
        assertFalse(UiText.progress(ops).contains("Заявка закрыта"))
    }
    @Test fun emptyAndFullyAcknowledgedQueueAreNotBusinessCompletionSignals() {
        assertEquals("В сохранённой очереди нет операций.",UiText.progress(emptyList()))
        assertEquals("Сохранённая очередь: подтверждено 2 из 2 операций (фото и закрытия).",UiText.progress(listOf(operation("ACKED"),operation("ACKED","CLOSE_WORK_ORDER"))))
        assertNull(UiText.closedNotice("DRAFT","ASSIGNED"))
        assertNull(UiText.closedNotice(null,"ASSIGNED"))
    }
    @Test fun backgroundCloseChangesNoticeAndPhotoScreenTitleWithoutNewRoute() {
        assertNull(UiText.closedNotice("QUEUED","ASSIGNED"))
        val notice=UiText.closedNotice("CLOSED","CLOSED")
        assertNotNull(notice)
        assertTrue(requireNotNull(notice).startsWith("Заявка закрыта, результат отправлен"))
        assertEquals("Фото и результат закрытия",UiText.closeTitle("CLOSED","CLOSED"))
        assertNotNull(UiText.closedNotice(null,"CLOSED"))
        // Independent Room flows may emit the canonical result before the order row.
        assertNotNull(UiText.closedNotice("CLOSED","ASSIGNED"))
    }
    @Test fun conflictAndUnconfirmedLocalResultNeverShowSuccessForServerClosed() {
        listOf("CONFLICT","QUEUED","DRAFT","FUTURE").forEach { lifecycle ->
            assertNull(UiText.closedNotice(lifecycle,"CLOSED"))
            assertEquals("Фото и закрытие",UiText.closeTitle(lifecycle,"CLOSED"))
        }
    }
    @Test fun presentationDoesNotMutateRawDiagnosticRecords() {
        val op=operation("RETRY_WAIT",error="UNKNOWN_OUTCOME")
        UiText.progress(listOf(op)); UiText.operationState(op.state); UiText.error(op.error)
        assertEquals("RETRY_WAIT",op.state); assertEquals("UNKNOWN_OUTCOME",op.error)
        assertEquals("operation-id",op.id); assertEquals("{}",op.frozenPayload)
    }
}
