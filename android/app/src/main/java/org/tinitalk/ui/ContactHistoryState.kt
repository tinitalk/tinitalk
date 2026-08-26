package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallHistoryPage

data class ContactHistoryState(
    val peerLogin: String? = null,
    val items: List<CallHistoryItem> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextBefore: Long = 0,
    val latestId: Long = 0,
    val errorMessage: String? = null,
)

fun ContactHistoryState.withPage(
    peerLogin: String,
    page: CallHistoryPage,
    reset: Boolean,
): ContactHistoryState = copy(
    peerLogin = peerLogin,
    items = if (reset) page.items else (items + page.items).distinctBy { it.id },
    loaded = true,
    loading = false,
    loadingMore = false,
    nextBefore = page.nextBefore,
    latestId = page.latestId,
    errorMessage = null,
)

fun shouldLoadMoreHistory(
    index: Int,
    itemCount: Int,
    nextBefore: Long,
    loading: Boolean,
    hasError: Boolean,
): Boolean = itemCount > 0 &&
    index == itemCount - 1 &&
    nextBefore > 0 &&
    !loading &&
    !hasError

fun isCurrentContactHistoryRequest(
    requestGeneration: Int,
    currentGeneration: Int,
    requestLogin: String,
    currentLogin: String?,
): Boolean = requestGeneration == currentGeneration && requestLogin == currentLogin

fun isCurrentSessionRequest(requestGeneration: Int, currentGeneration: Int): Boolean =
    requestGeneration == currentGeneration
