package moe.qwreey.powerdirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * 페어링 코드를 알림에서 직접 받는다.
 *
 * 앱 안의 입력란으로는 폰에서 쓸 수가 없다. 페어링 포트는 설정의
 * "페어링 코드로 기기 페어링" 대화상자가 떠 있는 동안에만 존재하는데, 그 대화상자가 떠 있으면
 * 우리 앱은 화면에 없다. 태블릿이라면 창 분할로 둘을 나란히 놓을 수 있지만 폰에서는 안 된다.
 *
 * 알림 그늘은 그 대화상자 위로도 내려온다. Shizuku 가 같은 이유로 같은 방법을 쓴다.
 */
object PairingNotification {
    const val KEY_CODE = "code"

    fun post(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pairing", NotificationManager.IMPORTANCE_HIGH),
        )

        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("6-digit pairing code")
            .build()

        val reply = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, PolicyReceiver::class.java).setAction(PolicyReceiver.ACTION_PAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val settings = PendingIntent.getActivity(
            context,
            3,
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pairing needed")
            .setContentText("Open Wireless debugging → Pair device with pairing code, then reply here.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "This app talks to your device's own adbd, and adbd does not know its key yet.\n\n" +
                        "1. Open Developer options → Wireless debugging\n" +
                        "2. Tap \"Pair device with pairing code\"\n" +
                        "3. Pull down this notification and reply with the 6 digits\n\n" +
                        "The pairing port only exists while that dialog is open.",
                ),
            )
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(0, "Enter code", reply)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
            .addAction(0, "Open settings", settings)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    private const val CHANNEL_ID = "pairing"
    private const val ID = 2
}
