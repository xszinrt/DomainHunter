package com.example.domainhunter.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.domainhunter.data.db.AppDatabase
import com.example.domainhunter.data.model.Domain
import com.example.domainhunter.data.model.DomainStatus
import com.example.domainhunter.data.model.ScanSession
import com.example.domainhunter.data.model.SessionStatus
import com.example.domainhunter.ui.MainActivity
import com.example.domainhunter.utils.DomainParser
import com.example.domainhunter.utils.RdapFetcher
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader

class DomainScanService : Service() {

    private var scanJob: Job? = null
    private var delayJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private var currentSession: ScanSession? = null
    private var lastNotificationUpdate = 0L
    private val NOTIFICATION_UPDATE_INTERVAL = 300L

    companion object {
        const val CHANNEL_ID = "domain_scan_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val EXTRA_TIMEOUT = "EXTRA_TIMEOUT"
        const val EXTRA_DELAY = "EXTRA_DELAY"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"

        private const val REQUEST_CODE_STOP = 100
        private const val REQUEST_CODE_PAUSE = 101
        private const val REQUEST_CODE_RESUME = 102

        var isRunning = false
        var isPaused = false
        var progress = 0
        var total = 0
        var registered = 0
        var failed = 0
        var ignored = 0
        var estimatedTimeLeft = "--"
        var currentSessionId = -1L
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopScan()
                ACTION_PAUSE -> {
                    isPaused = !isPaused
                    if (isPaused) {
                        delayJob?.cancel()
                    }
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
        registerReceiver(notificationReceiver, IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_PAUSE)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScan()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = !isPaused
                if (isPaused) {
                    delayJob?.cancel()
                }
                updateNotification()
                return START_STICKY
            }
        }

        if (isRunning) {
            updateNotification()
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val timeout = intent?.getLongExtra(EXTRA_TIMEOUT, 5000L) ?: 5000L
        val delayMs = intent?.getLongExtra(EXTRA_DELAY, 500L) ?: 500L
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY

        isRunning = true
        isPaused = false
        progress = 0
        registered = 0
        failed = 0
        ignored = 0

        startScan(filePath, timeout, delayMs)
        return START_STICKY
    }

    private fun startScan(filePath: String, timeout: Long, delayMs: Long) {
        scanJob = scope.launch {
            delay(100)
            updateNotification()
            
            val domains = mutableListOf<String>()
            BufferedReader(FileReader(filePath)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val domain = DomainParser.extractDomain(line!!)
                    if (domain != null) domains.add(domain.removeSuffix(".com"))
                }
            }

            total = domains.size
            val id = db.sessionDao().insert(ScanSession(totalDomains = total))
            currentSessionId = id
            currentSession = db.sessionDao().getById(id)

            val startTime = System.currentTimeMillis()

            for (i in domains.indices) {
                if (!isRunning) break
                while (isPaused && isRunning) { delay(50) }
                if (!isRunning) break

                val netDomain = "${domains[i]}.net"
                progress = i + 1

                try {
                    val rdap = withContext(Dispatchers.IO) { RdapFetcher.check(netDomain) }
                    when {
                        rdap.isRegistered -> {
                            val expiring = rdap.expirationDate?.let { isExpiringSoon(it) } ?: false
                            db.domainDao().insert(
                                Domain(
                                    sessionId = currentSession!!.id,
                                    domainName = netDomain,
                                    registrationDate = rdap.registrationDate,
                                    expirationDate = rdap.expirationDate,
                                    isExpiringSoon = expiring,
                                    status = DomainStatus.REGISTERED
                                )
                            )
                            registered++
                        }
                        else -> ignored++
                    }
                } catch (e: Exception) {
                    when {
                        !isRunning -> break
                        e.message?.contains("cancel", ignoreCase = true) == true -> {
                            if (!isRunning) break
                        }
                        else -> {
                            db.domainDao().insert(
                                Domain(
                                    sessionId = currentSession!!.id,
                                    domainName = netDomain,
                                    registrationDate = null,
                                    expirationDate = null,
                                    status = DomainStatus.FAILED
                                )
                            )
                            failed++
                        }
                    }
                }

                currentSession?.let {
                    db.sessionDao().update(
                        it.copy(
                            scannedDomains = progress,
                            registeredCount = registered,
                            failedCount = failed,
                            lastScannedIndex = i
                        )
                    )
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (progress > 0) {
                    val avg = elapsed / progress.toDouble()
                    val remaining = ((total - progress) * avg / 1000).toInt()
                    estimatedTimeLeft = formatTime(remaining)
                }

                updateNotificationThrottled()

                if (isRunning && !isPaused) {
                    delayJob = scope.launch { delay(delayMs) }
                    delayJob?.join()
                }
            }

            if (isRunning) {
                db.sessionDao().update(
                    currentSession!!.copy(
                        endTime = System.currentTimeMillis(),
                        status = SessionStatus.COMPLETED
                    )
                )
                showCompletedNotification()
            }

            isRunning = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun updateNotificationThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL || progress >= total) {
            lastNotificationUpdate = now
            withContext(Dispatchers.Main) {
                updateNotification()
            }
        }
    }

    private fun stopScan() {
        isRunning = false
        isPaused = false
        delayJob?.cancel()
        scanJob?.cancel()
        scope.launch {
            currentSession?.let {
                db.sessionDao().update(it.copy(status = SessionStatus.STOPPED))
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isExpiringSoon(expirationDate: String): Boolean {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val exp = sdf.parse(expirationDate) ?: return false
            exp.time - System.currentTimeMillis() < 30L * 24 * 60 * 60 * 1000
        } catch (e: Exception) { false }
    }

    private fun formatTime(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_STOP,
            Intent(this, DomainScanService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseRequestCode = if (isPaused) REQUEST_CODE_RESUME else REQUEST_CODE_PAUSE
        val pauseIntent = PendingIntent.getBroadcast(
            this, pauseRequestCode,
            Intent(this, DomainScanService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val percent = if (total > 0) (progress * 100 / total) else 0
        val statusText = if (isPaused) "Paused" else "Scanning"
        val pauseLabel = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Domain Hunter - $statusText")
            .setContentText("$progress / $total ($percent%)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Registered: $registered  |  Failed: $failed  |  Ignored: $ignored\n" +
                    "$progress / $total ($percent%)\n" +
                    "ETA: $estimatedTimeLeft"
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(total, progress, false)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, pauseLabel, pauseIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCompletedNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scan Complete!")
            .setContentText("$registered registered domains found out of $total")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Domain Scan", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Domain scanning in background"
            setShowBadge(true)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) { }
        isRunning = false
        delayJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
    }
}
