package eg.masroof.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsReceiverEntryPoint {
    fun senderSettings(): SenderSettings

    fun smsScheduler(): SmsWorkScheduler
}

/**
 * Receives SMS_RECEIVED (REQ-SMS-020). It only joins the PDU parts and calls [SmsHandoff]: parsing, storage and upload
 * happen in WorkManager workers, never here (REQ-SMS-021).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val joined =
            parts
                .filter { it.originatingAddress != null }
                .groupBy({ it.originatingAddress.orEmpty() }, { it })
                .map { (sender, pieces) ->
                    IncomingSms(
                        sender = sender,
                        body = pieces.joinToString("") { it.messageBody.orEmpty() },
                        timestampMillis = pieces.first().timestampMillis,
                    )
                }
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, SmsReceiverEntryPoint::class.java)
        SmsHandoff(graph.senderSettings(), graph.smsScheduler()).handOff(joined)
    }
}
