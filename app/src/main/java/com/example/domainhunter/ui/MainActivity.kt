package com.example.domainhunter.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.domainhunter.databinding.ActivityMainBinding
import com.example.domainhunter.service.DomainScanService
import com.example.domainhunter.utils.ExportHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = DomainAdapter()
    private var filePath: String? = null
    private var totalDomainsInFile = 0
    private var uiUpdateJob: Job? = null
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openFilePicker()
        else Toast.makeText(this, "يجب منح صلاحية قراءة الملفات", Toast.LENGTH_SHORT).show()
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val fileName = getFileName(uri) ?: "domains.txt"
            val fileSize = getFileSize(uri)
            val file = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            filePath = file.absolutePath
            binding.tvFileName.text = "📄 $fileName  (${fileSize})"
            filterAndCountDomains(file)
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في قراءة الملف: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): String {
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) size = cursor.getLong(idx)
            }
        }
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        }
    }

    private fun openFilePicker() {
        filePicker.launch(arrayOf("text/plain", "text/csv", "*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // استعادة آخر قيم
        binding.etTimeout.setText(prefs.getString("timeout", "5000"))
        binding.etDelay.setText(prefs.getString("delay", "500"))

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.domains.observe(this) { list -> adapter.submitList(list) }

        binding.btnImport.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                openFilePicker()
            } else if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        binding.btnStart.setOnClickListener {
            if (filePath == null) {
                Toast.makeText(this, "اختر ملفاً أولاً!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // حفظ القيم
            prefs.edit()
                .putString("timeout", binding.etTimeout.text.toString())
                .putString("delay", binding.etDelay.text.toString())
                .apply()
            startScan()
        }

        binding.btnPause.setOnClickListener {
            if (!DomainScanService.isRunning) return@setOnClickListener
            startService(Intent(this, DomainScanService::class.java).apply {
                action = DomainScanService.ACTION_PAUSE
            })
        }

        binding.btnStop.setOnClickListener { stopScan() }

        binding.btnClear.setOnClickListener {
            filePath = null
            totalDomainsInFile = 0
            binding.tvFileName.text = "لم يتم اختيار ملف"
            binding.tvProgress.text = "0 / 0"
            binding.progressBar.progress = 0
            binding.tvRegistered.text = "✅ 0"
            binding.tvFailed.text = "❌ 0"
            binding.tvIgnored.text = "⏭️ 0"
            binding.tvEta.text = "--"
            binding.importProgressBar.isVisible = false
            binding.tvImportStatus.isVisible = false
        }

        binding.etSearch.addTextChangedListener { viewModel.setSearch(it.toString()) }

        binding.btnExport.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent.createChooser(
                ExportHelper.exportToCsv(this, domains), "تصدير النتائج"
            ))
        }

        binding.btnCopyAll.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "لا توجد نتائج!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("domains", ExportHelper.copyAll(domains)))
            Toast.makeText(this, "تم نسخ ${domains.size} نطاق!", Toast.LENGTH_SHORT).show()
        }

        startUiUpdate()
    }

    private fun filterAndCountDomains(file: File) {
        binding.importProgressBar.isVisible = true
        binding.tvImportStatus.isVisible = true
        binding.tvImportStatus.text = "⏳ جاري تصفية النطاقات..."
        binding.btnStart.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            var totalLines = 0
            var comCount = 0

            try {
                // حساب العدد الكلي أولاً
                file.bufferedReader().use { reader ->
                    while (reader.readLine() != null) totalLines++
                }

                // تصفية .com مع تحديث التقدم
                file.bufferedReader().use { reader ->
                    var lineNum = 0
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lineNum++
                        if (line!!.trim().lowercase().endsWith(".com")) comCount++

                        if (lineNum % 500 == 0) {
                            val percent = (lineNum * 100 / totalLines)
                            withContext(Dispatchers.Main) {
                                binding.importProgressBar.progress = percent
                                binding.tvImportStatus.text = "⏳ تصفية: $lineNum / $totalLines  ($percent%)"
                            }
                        }
                    }
                }

                totalDomainsInFile = comCount
                withContext(Dispatchers.Main) {
                    binding.importProgressBar.progress = 100
                    binding.tvImportStatus.text = "✅ إجمالي الملف: $totalLines سطر  |  نطاقات .com: $comCount"
                    binding.tvProgress.text = "0 / $comCount"
                    binding.progressBar.max = comCount
                    binding.btnStart.isEnabled = comCount > 0
                    if (comCount == 0) {
                        Toast.makeText(this@MainActivity, "لا توجد نطاقات .com في الملف!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.importProgressBar.isVisible = false
                    binding.tvImportStatus.isVisible = false
                }
            }
        }
    }

    private fun startScan() {
        val intent = Intent(this, DomainScanService::class.java).apply {
            putExtra(DomainScanService.EXTRA_FILE_PATH, filePath)
            putExtra(DomainScanService.EXTRA_TIMEOUT,
                binding.etTimeout.text.toString().toLongOrNull() ?: 5000L)
            putExtra(DomainScanService.EXTRA_DELAY,
                binding.etDelay.text.toString().toLongOrNull() ?: 500L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.btnStart.isEnabled = false
        binding.btnPause.isEnabled = true
        binding.btnStop.isEnabled = true
    }

    private fun stopScan() {
        startService(Intent(this, DomainScanService::class.java).apply {
            action = DomainScanService.ACTION_STOP
        })
        binding.btnStart.isEnabled = true
        binding.btnPause.isEnabled = false
        binding.btnStop.isEnabled = false
    }

    private fun startUiUpdate() {
        uiUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                if (DomainScanService.isRunning) {
                    val p = DomainScanService.progress
                    val t = DomainScanService.total.takeIf { it > 0 } ?: totalDomainsInFile
                    binding.tvProgress.text = "$p / $t"
                    binding.progressBar.max = t
                    binding.progressBar.progress = p
                    binding.tvRegistered.text = "✅ ${DomainScanService.registered}"
                    binding.tvFailed.text = "❌ ${DomainScanService.failed}"
                    binding.tvIgnored.text = "⏭️ ${DomainScanService.ignored}"
                    binding.tvEta.text = DomainScanService.estimatedTimeLeft
                    binding.tvResultCount.text = "🔍 ${DomainScanService.registered} نطاق محجوز"
                    if (DomainScanService.currentSessionId != -1L) {
                        viewModel.setSession(DomainScanService.currentSessionId)
                    }
                    if (!DomainScanService.isRunning) {
                        binding.btnStart.isEnabled = true
                        binding.btnPause.isEnabled = false
                        binding.btnStop.isEnabled = false
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiUpdateJob?.cancel()
    }
}
