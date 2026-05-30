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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.domainhunter.data.db.AppDatabase
import com.example.domainhunter.databinding.ActivityMainBinding
import com.example.domainhunter.service.DomainScanService
import com.example.domainhunter.utils.DomainParser
import com.example.domainhunter.utils.ExportHelper
import com.google.android.material.textfield.TextInputEditText
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

    private var timeoutValue = 5000L
    private var delayValue = 500L

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openFilePicker()
        else Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
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
            binding.tvFileName.text = "📄 $fileName  ($fileSize)"
            filterAndCountDomains(file)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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

        timeoutValue = prefs.getString("timeout", "5000")?.toLongOrNull() ?: 5000L
        delayValue = prefs.getString("delay", "500")?.toLongOrNull() ?: 500L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.domains.observe(this) { list -> adapter.submitList(list) }

        val sortOptions = listOf("Default", "Expiry: Soonest", "Expiry: Latest")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSort.adapter = spinnerAdapter
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                viewModel.setSort(when (pos) {
                    1 -> SortOrder.EXPIRY_SOONEST
                    2 -> SortOrder.EXPIRY_LATEST
                    else -> SortOrder.DEFAULT
                })
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

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
                Toast.makeText(this, "Please select a file first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (DomainScanService.isPaused) {
                startService(Intent(this, DomainScanService::class.java).apply {
                    action = DomainScanService.ACTION_PAUSE
                })
            } else {
                startScan()
            }
        }

        binding.btnPause.setOnClickListener {
            if (!DomainScanService.isRunning) return@setOnClickListener
            startService(Intent(this, DomainScanService::class.java).apply {
                action = DomainScanService.ACTION_PAUSE
            })
        }

        // ✅ Stop: يوقف الفحص ويمسح النتائج ويعيد قسم الاستيراد
        binding.btnStop.setOnClickListener {
            stopScanAndClearResults()
        }

        binding.btnExport.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "No results to export!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent.createChooser(
                ExportHelper.exportToCsv(this, domains), "Export Results"
            ))
        }

        binding.btnCopyAll.setOnClickListener {
            val domains = adapter.currentList
            if (domains.isEmpty()) {
                Toast.makeText(this, "No results!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("domains", ExportHelper.copyAll(domains)))
            Toast.makeText(this, "Copied ${domains.size} domains!", Toast.LENGTH_SHORT).show()
        }

        startUiUpdate()
    }

    private fun filterAndCountDomains(file: File) {
        binding.importProgressBar.isVisible = true
        binding.tvImportStatus.isVisible = true
        binding.tvImportStatus.text = "⏳ Filtering domains..."
        binding.btnStart.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            var totalLines = 0
            var comCount = 0
            try {
                file.bufferedReader().use { while (it.readLine() != null) totalLines++ }
                file.bufferedReader().use { reader ->
                    var lineNum = 0
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lineNum++
                        if (DomainParser.extractDomain(line!!) != null) comCount++
                        if (lineNum % 500 == 0) {
                            val percent = (lineNum * 100 / totalLines)
                            withContext(Dispatchers.Main) {
                                binding.importProgressBar.progress = percent
                                binding.tvImportStatus.text = "⏳ $lineNum / $totalLines ($percent%)"
                            }
                        }
                    }
                }
                totalDomainsInFile = comCount
                withContext(Dispatchers.Main) {
                    binding.importProgressBar.progress = 100
                    binding.tvImportStatus.text = "✅ Total: $totalLines lines  |  .com: $comCount"
                    binding.tvProgress.text = "0 / $comCount"
                    binding.progressBar.max = comCount
                    binding.btnStart.isEnabled = comCount > 0
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startScan() {
        val intent = Intent(this, DomainScanService::class.java).apply {
            putExtra(DomainScanService.EXTRA_FILE_PATH, filePath)
            putExtra(DomainScanService.EXTRA_TIMEOUT, timeoutValue)
            putExtra(DomainScanService.EXTRA_DELAY, delayValue)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.btnStart.isEnabled = false
        binding.btnPause.isEnabled = true
        binding.btnStop.isEnabled = true
        
        // ✅ إخفاء قسم الاستيراد أثناء الفحص
        binding.cardImport.visibility = View.GONE
    }

    // ✅ وظيفة جديدة: إيقاف الفحص ومسح جميع النتائج
    private fun stopScanAndClearResults() {
        // إيقاف الخدمة
        startService(Intent(this, DomainScanService::class.java).apply {
            action = DomainScanService.ACTION_STOP
        })
        
        // مسح قاعدة البيانات
        CoroutineScope(Dispatchers.IO).launch {
            if (DomainScanService.currentSessionId != -1L) {
                AppDatabase.getInstance(this@MainActivity)
                    .domainDao()
                    .deleteBySession(DomainScanService.currentSessionId)
            }
            withContext(Dispatchers.Main) {
                // إعادة تعيين جميع المتغيرات
                filePath = null
                totalDomainsInFile = 0
                DomainScanService.currentSessionId = -1L
                DomainScanService.progress = 0
                DomainScanService.registered = 0
                DomainScanService.failed = 0
                DomainScanService.ignored = 0
                DomainScanService.estimatedTimeLeft = "--"
                
                // إعادة تعيين الواجهة
                binding.tvFileName.text = "No file selected"
                binding.tvProgress.text = "0 / 0"
                binding.progressBar.progress = 0
                binding.tvRegistered.text = "✅ 0"
                binding.tvFailed.text = "❌ 0"
                binding.tvIgnored.text = "⏭ 0"
                binding.tvEta.text = "--"
                binding.tvResultCount.text = "🔍 0 registered"
                binding.importProgressBar.isVisible = false
                binding.tvImportStatus.isVisible = false
                
                // ✅ إعادة ظهور قسم الاستيراد
                binding.cardImport.visibility = View.VISIBLE
                
                // إعادة تعيين الأزرار
                binding.btnStart.isEnabled = true
                binding.btnPause.isEnabled = false
                binding.btnStop.isEnabled = false
                binding.btnPause.text = "⏸"
                
                adapter.submitList(emptyList())
            }
        }
    }

    private fun startUiUpdate() {
        uiUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val running = DomainScanService.isRunning
                val paused = DomainScanService.isPaused
                if (running || paused) {
                    val p = DomainScanService.progress
                    val t = DomainScanService.total.takeIf { it > 0 } ?: totalDomainsInFile
                    binding.tvProgress.text = "$p / $t"
                    binding.progressBar.max = t
                    binding.progressBar.progress = p
                    binding.tvRegistered.text = "✅ ${DomainScanService.registered}"
                    binding.tvFailed.text = "❌ ${DomainScanService.failed}"
                    binding.tvIgnored.text = "⏭ ${DomainScanService.ignored}"
                    binding.tvEta.text = DomainScanService.estimatedTimeLeft
                    binding.tvResultCount.text = "🔍 ${DomainScanService.registered} registered"
                    binding.btnPause.text = if (paused) "▶" else "⏸"
                    binding.btnStart.isEnabled = paused
                    binding.btnPause.isEnabled = running || paused
                    binding.btnStop.isEnabled = running || paused
                    if (DomainScanService.currentSessionId != -1L) {
                        viewModel.setSession(DomainScanService.currentSessionId)
                    }
                } else {
                    binding.btnStart.isEnabled = filePath != null
                    binding.btnPause.isEnabled = false
                    binding.btnStop.isEnabled = false
                    binding.btnPause.text = "⏸"
                }
                delay(500)
            }
        }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(50, 30, 50, 30)
        
        val timeoutInput = TextInputEditText(this)
        timeoutInput.hint = "Timeout (ms)"
        timeoutInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        timeoutInput.setText(timeoutValue.toString())
        container.addView(timeoutInput)
        
        val delayInput = TextInputEditText(this)
        delayInput.hint = "Delay (ms)"
        delayInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        delayInput.setText(delayValue.toString())
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = 40
        delayInput.layoutParams = params
        container.addView(delayInput)
        
        AlertDialog.Builder(this)
            .setTitle("⚙️ Scan Settings")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                timeoutValue = timeoutInput.text.toString().toLongOrNull() ?: 5000L
                delayValue = delayInput.text.toString().toLongOrNull() ?: 500L
                prefs.edit()
                    .putString("timeout", timeoutValue.toString())
                    .putString("delay", delayValue.toString())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiUpdateJob?.cancel()
    }
}
