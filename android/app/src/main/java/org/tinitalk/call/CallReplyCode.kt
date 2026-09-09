package org.tinitalk.call

import androidx.annotation.StringRes
import org.tinitalk.R

/** Stable protocol values. User-visible wording is always resolved locally. */
enum class CallReplyCode(
    val wireValue: String,
    @param:StringRes val textRes: Int,
    @param:StringRes val resultTextRes: Int,
    @param:StringRes val receivedHistoryRes: Int,
    @param:StringRes val sentHistoryRes: Int,
) {
    CannotTalk(
        "cannot_talk", R.string.call_reply_cannot_talk, R.string.call_reply_result_cannot_talk,
        R.string.call_reply_history_cannot_talk, R.string.call_reply_history_sent_cannot_talk,
    ),
    CallMeLater(
        "call_me_later", R.string.call_reply_call_me_later, R.string.call_reply_result_call_me_later,
        R.string.call_reply_history_call_me_later, R.string.call_reply_history_sent_call_me_later,
    ),
    WillCallBack(
        "will_call_back", R.string.call_reply_will_call_back, R.string.call_reply_result_will_call_back,
        R.string.call_reply_history_will_call_back, R.string.call_reply_history_sent_will_call_back,
    );

    companion object {
        fun fromWire(value: String?): CallReplyCode? = entries.firstOrNull { it.wireValue == value }
    }
}
