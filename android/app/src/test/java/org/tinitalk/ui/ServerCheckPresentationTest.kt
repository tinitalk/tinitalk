package org.tinitalk.ui

import org.tinitalk.data.ServerCheckResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCheckPresentationTest {
    @Test
    fun presentsServerHealthWithDistinctIndicatorsAndMessages() {
        val cases = listOf(
            serverCheckPresentation(false, false, null) to
                ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Введите полный адрес сервера"),
            serverCheckPresentation(true, false, null) to
                ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…"),
            serverCheckPresentation(true, true, null) to
                ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…"),
            serverCheckPresentation(true, false, ServerCheckResult.Available) to
                ServerCheckPresentation(ServerCheckIndicator.Available, "Сервер TiniTalk доступен"),
            serverCheckPresentation(true, false, ServerCheckResult.WrongServer) to
                ServerCheckPresentation(ServerCheckIndicator.Unavailable, "По этому адресу нет сервера TiniTalk"),
            serverCheckPresentation(true, false, ServerCheckResult.Unavailable) to
                ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Сервер недоступен. Проверьте адрес и подключение к сети"),
            serverCheckPresentation(true, false, ServerCheckResult.ServerOutdated) to
                ServerCheckPresentation(ServerCheckIndicator.Incompatible, "Сервер TiniTalk устарел. Обновите сервер"),
            serverCheckPresentation(true, false, ServerCheckResult.AppOutdated) to
                ServerCheckPresentation(ServerCheckIndicator.Incompatible, "Приложение TiniTalk устарело. Установите новую версию"),
        )

        cases.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }

    @Test
    fun offlineIsNotPresentedAsServerFailure() {
        assertEquals(
            ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Нет подключения к интернету"),
            serverCheckPresentation(
                serverReady = true,
                checking = false,
                result = ServerCheckResult.Unavailable,
                internetAvailable = false,
            ),
        )
    }
}
