package com.hbctool.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hbctool.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var selectedFileUri: Uri? = null
    private var selectedFilePath: String? = null
    private var lastOutputDir: String? = null  // 记录最后的输出目录
    private var isDisassembledOutput: Boolean = false  // 标记输出是否是反汇编生成的
    private var isOperationRunning: Boolean = false  // 标记是否有操作正在运行，防止重复点击
    private val maxLogLines = 500  // 日志最大行数，防止UI卡顿
    
    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }
    
    // 导出ZIP文件选择器
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportToUri(uri)
            }
        }
    }
    
    // 导出Bundle文件选择器
    private val exportBundleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportBundleToUri(uri)
            }
        }
    }
    
    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        // 设置边缘到边缘显示，让内容延伸到状态栏和导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // 根据当前主题设置状态栏和导航栏图标颜色
        val isDarkMode = (resources.configuration.uiMode and 
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = getColor(android.R.color.transparent)
            // 浅色模式用深色图标，深色模式用浅色图标
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDarkMode
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = getColor(android.R.color.transparent)
            // 浅色模式用深色图标，深色模式用浅色图标
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !isDarkMode
        }
        
        // 初始化 Python
        initPython()
        
        // 设置监听器
        setupListeners()
        
        // 检查现有文件
        checkExistingFiles()
        
        // 处理外部打开的文件
        handleIntent(intent)
    }
    
    private fun checkExistingFiles() {
        // 检查是否有输入文件
        val inputDir = File(filesDir, "input")
        if (inputDir.exists()) {
            val inputFiles = inputDir.listFiles()
            if (inputFiles != null && inputFiles.isNotEmpty()) {
                val latestFile = inputFiles.maxByOrNull { it.lastModified() }
                if (latestFile != null && latestFile.exists()) {
                    selectedFilePath = latestFile.absolutePath
                    binding.filePathText.text = "已选择: ${latestFile.name}\n路径: ${latestFile.absolutePath}"
                    
                    // 根据文件类型启用按钮
                    when {
                        latestFile.name.endsWith(".bundle", ignoreCase = true) -> {
                            binding.disassembleButton.isEnabled = true
                            binding.cleanEnvironmentButton.isEnabled = true
                        }
                        latestFile.name.endsWith(".hasm", ignoreCase = true) -> {
                            binding.assembleButton.isEnabled = true
                            binding.cleanEnvironmentButton.isEnabled = true
                        }
                    }
                }
            }
        }
        
        // 检查是否有输出文件
        val outputDir = File(filesDir, "output")
        if (outputDir.exists()) {
            val outputDirs = outputDir.listFiles { file -> file.isDirectory }
            if (outputDirs != null && outputDirs.isNotEmpty()) {
                val latestOutputDir = outputDirs.maxByOrNull { it.lastModified() }
                if (latestOutputDir != null && latestOutputDir.exists()) {
                    lastOutputDir = latestOutputDir.absolutePath
                    binding.cleanEnvironmentButton.isEnabled = true
                    // 启动时不启用导出按钮,因为不确定是反汇编还是汇编输出
                    // 启动时不自动加载输出文件,只在导入ZIP时才自动加载
                }
            }
        }
    }
    
    private fun checkAndLoadFileFromOutput(outputDir: File) {
        val hasmFiles = outputDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("hasm", ignoreCase = true) }
            .toList()
        
        // 只自动加载反汇编生成的.hasm文件,不加载汇编生成的.bundle文件
        if (hasmFiles.size == 1) {
            val hasmFile = hasmFiles.first()
            // 汇编需要目录路径（包含 metadata.json, string.json, instruction.hasm）
            hasmFile.parentFile?.let { parent ->
                selectedFilePath = parent.absolutePath
                binding.filePathText.text = "已选择目录: ${parent.name}\n（汇编需要完整目录）"
                binding.assembleButton.isEnabled = true
                binding.disassembleButton.isEnabled = false
                binding.cleanEnvironmentButton.isEnabled = true
            }
        }
    }
    
    private fun initPython() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
            appendLog("✓ Python 引擎已启动")
        }
    }
    
    private fun setupListeners() {
        // 选择文件按钮
        binding.selectFileButton.setOnClickListener {
            checkPermissionsAndPickFile()
        }
        
        // 反汇编按钮
        binding.disassembleButton.setOnClickListener {
            selectedFilePath?.let { path ->
                performDisassemble(path)
            }
        }
        
        // 汇编按钮
        binding.assembleButton.setOnClickListener {
            selectedFilePath?.let { path ->
                performAssemble(path)
            }
        }
        
        // 清空日志按钮
        binding.clearLogButton.setOnClickListener {
            binding.logTextView.text = "日志已清空\n"
        }
        
        // 复制日志按钮
        binding.copyLogButton.setOnClickListener {
            copyLogToClipboard()
        }
        
        // 清理环境按钮
        binding.cleanEnvironmentButton.setOnClickListener {
            cleanEnvironment()
        }
        
        // 导出ZIP文件按钮
        binding.exportButton.setOnClickListener {
            exportOutputFiles()
        }
        
        // 导出Bundle文件按钮
        binding.exportBundleButton.setOnClickListener {
            exportBundleFile()
        }
    }
    
    private fun cleanEnvironment() {
        val inputDir = File(filesDir, "input")
        val outputDir = File(filesDir, "output")
        
        val hasInputFiles = inputDir.exists() && inputDir.listFiles()?.isNotEmpty() == true
        val hasOutputFiles = outputDir.exists() && outputDir.listFiles()?.isNotEmpty() == true
        
        if (!hasInputFiles && !hasOutputFiles) {
            Toast.makeText(this, "没有可清理的文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        val message = buildString {
            append("确定要清理环境吗？将删除：\n")
            if (hasInputFiles) {
                val fileCount = inputDir.listFiles()?.size ?: 0
                append("\n• Input 文件夹：$fileCount 个文件")
            }
            if (hasOutputFiles) {
                val dirCount = outputDir.listFiles()?.size ?: 0
                append("\n• Output 文件夹：$dirCount 个目录")
            }
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("清理环境")
            .setMessage(message)
            .setPositiveButton("清理") { _, _ ->
                var successCount = 0
                var failCount = 0
                
                // 清理 input 文件夹
                if (hasInputFiles) {
                    try {
                        var deletedCount = 0
                        inputDir.listFiles()?.forEach { file ->
                            if (file.deleteRecursively()) {
                                deletedCount++
                            }
                        }
                        if (deletedCount > 0) {
                            appendLog("✓ 已清理 Input 文件夹：删除 $deletedCount 个文件")
                            successCount++
                        }
                    } catch (e: Exception) {
                        appendLog("❌ 清理 Input 文件夹异常: ${e.message}")
                        failCount++
                    }
                }
                
                // 清理 output 文件夹
                if (hasOutputFiles) {
                    try {
                        var deletedCount = 0
                        outputDir.listFiles()?.forEach { dir ->
                            if (dir.deleteRecursively()) {
                                deletedCount++
                            }
                        }
                        if (deletedCount > 0) {
                            appendLog("✓ 已清理 Output 文件夹：删除 $deletedCount 个目录")
                            successCount++
                        }
                    } catch (e: Exception) {
                        appendLog("❌ 清理 Output 文件夹异常: ${e.message}")
                        failCount++
                    }
                }
                
                // 重置所有状态和UI
                selectedFilePath = null
                selectedFileUri = null
                lastOutputDir = null
                isDisassembledOutput = false
                binding.filePathText.text = getString(R.string.select_file_hint)
                binding.disassembleButton.isEnabled = false
                binding.assembleButton.isEnabled = false
                binding.cleanEnvironmentButton.isEnabled = false
                binding.exportButton.isEnabled = false
                binding.exportBundleButton.isEnabled = false
                
                // 显示结果
                val resultMessage = when {
                    failCount == 0 -> "清理完成"
                    successCount == 0 -> "清理失败"
                    else -> "部分清理成功"
                }
                Toast.makeText(this, resultMessage, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun copyLogToClipboard() {
        val logText = binding.logTextView.text.toString()
        if (logText.isBlank() || logText == "日志已清空\n") {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("HBC-Tool Log", logText)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
    
    private fun checkPermissionsAndPickFile() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ 不需要存储权限
                openFilePicker()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12
                openFilePicker()
            }
            else -> {
                // Android 10 及以下
                val permissions = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                
                val hasPermissions = permissions.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
                
                if (hasPermissions) {
                    openFilePicker()
                } else {
                    permissionLauncher.launch(permissions)
                }
            }
        }
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // 支持 .bundle、.hasm 和 .zip 文件
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "text/plain",
                "application/zip"
            ))
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun handleSelectedFile(uri: Uri) {
        try {
            selectedFileUri = uri
            
            // 获取文件名
            val fileName = getFileName(uri) ?: "未知文件"
            
            // 检查是否是 ZIP 文件
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                // 处理 ZIP 导入
                importFromUri(uri)
                return
            }
            
            // 处理普通文件（.bundle 或 .hasm）
            val inputFile = File(filesDir, "input/$fileName")
            inputFile.parentFile?.mkdirs()
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            selectedFilePath = inputFile.absolutePath
            
            // 更新 UI
            binding.filePathText.text = "已选择: $fileName\n路径: ${inputFile.absolutePath}"
            
            // 根据文件类型启用相应按钮
            when {
                fileName.endsWith(".bundle", ignoreCase = true) -> {
                    binding.disassembleButton.isEnabled = true
                    binding.assembleButton.isEnabled = false
                    binding.cleanEnvironmentButton.isEnabled = true
                    appendLog("✓ 已加载 Bundle 文件: $fileName")
                }
                fileName.endsWith(".hasm", ignoreCase = true) -> {
                    // .hasm 文件单独选择无法使用，汇编需要完整目录
                    binding.disassembleButton.isEnabled = false
                    binding.assembleButton.isEnabled = false
                    binding.cleanEnvironmentButton.isEnabled = true
                    appendLog("⚠ 单个 HASM 文件无法汇编")
                    appendLog("提示：汇编需要包含以下文件的完整目录：")
                    appendLog("  - metadata.json")
                    appendLog("  - string.json")
                    appendLog("  - instruction.hasm")
                    appendLog("建议：将这些文件打包成 ZIP 后重新导入")
                    Toast.makeText(this, "HASM 文件需要完整目录才能汇编", Toast.LENGTH_LONG).show()
                }
                else -> {
                    binding.disassembleButton.isEnabled = false
                    binding.assembleButton.isEnabled = false
                    binding.cleanEnvironmentButton.isEnabled = true
                    appendLog("⚠ 未识别的文件类型")
                }
            }
            
        } catch (e: Exception) {
            appendLog("❌ 文件加载失败: ${e.message}")
            Toast.makeText(this, "文件加载失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun performDisassemble(inputPath: String) {
        if (isOperationRunning) {
            Toast.makeText(this, "当前有操作正在进行，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                isOperationRunning = true
                showProgress(true)
                binding.disassembleButton.isEnabled = false
                binding.assembleButton.isEnabled = false
                
                appendLog("\n========== 开始反汇编 ==========")
                appendLog("输入文件: $inputPath")
                
                // 准备输出目录
                val outputDir = File(filesDir, "output/hasm_${System.currentTimeMillis()}")
                outputDir.mkdirs()
                appendLog("输出目录: ${outputDir.absolutePath}")
                
                val result = withContext(Dispatchers.Default) {
                    try {
                        // 调用 Python (CPU密集型任务使用 Dispatchers.Default)
                        val python = Python.getInstance()
                        val bridge = python.getModule("api_bridge")
                        
                        val pyResult = bridge.callAttr("do_disassemble", inputPath, outputDir.absolutePath)
                        
                        pyResult
                    } catch (e: Exception) {
                        appendLog("❌ Python 调用异常: ${e.javaClass.simpleName}")
                        appendLog("异常消息: ${e.message}")
                        e.printStackTrace()
                        throw e
                    }
                }
                
                // 解析结果
                try {
                    // 使用 Python 字典的 get 方法来访问键值
                    val status = result.callAttr("get", "status")?.toString() ?: "unknown"
                    
                    val log = result.callAttr("get", "log")?.toString() ?: ""
                    val error = result.callAttr("get", "error")?.toString()
                
                    if (status == "success") {
                        appendLog("✓ 反汇编完成！")
                        // 显示 Python 返回的日志（截断过长内容，避免UI卡顿）
                        if (log.isNotEmpty()) {
                            appendTruncatedLog(log)
                        }
                        // 记录输出目录并启用删除和导出按钮
                        lastOutputDir = outputDir.absolutePath
                        isDisassembledOutput = true  // 标记为反汇编输出
                        binding.cleanEnvironmentButton.isEnabled = true
                        binding.exportButton.isEnabled = true  // 启用导出ZIP
                        binding.exportBundleButton.isEnabled = false  // 禁用导出Bundle
                        
                        Toast.makeText(this@MainActivity, R.string.operation_success, Toast.LENGTH_SHORT).show()
                    } else {
                        appendLog("❌ 反汇编失败")
                        if (log.isNotEmpty()) {
                            appendTruncatedLog(log)
                        }
                        if (error != null) {
                            appendLog("错误详情: $error")
                        }
                        Toast.makeText(this@MainActivity, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (parseException: Exception) {
                    appendLog("❌ 解析返回值失败: ${parseException.message}")
                    appendLog("原始返回值: $result")
                    Toast.makeText(this@MainActivity, "返回值解析失败", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                appendLog("❌ 错误: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showProgress(false)
                binding.disassembleButton.isEnabled = true
                binding.assembleButton.isEnabled = true
                isOperationRunning = false
            }
        }
    }
    
    /**
     * 截断过长的日志后追加显示，避免UI卡顿
     */
    private fun appendTruncatedLog(log: String) {
        val lines = log.lines()
        if (lines.size <= 50) {
            // 日志不多，直接显示
            for (line in lines) {
                if (line.isNotBlank()) {
                    appendLog(line)
                }
            }
        } else {
            // 日志过多，只显示前20行和最后20行
            val firstLines = lines.take(20).filter { it.isNotBlank() }
            val lastLines = lines.takeLast(20).filter { it.isNotBlank() }
            val skipped = lines.size - firstLines.size - lastLines.size
            
            for (line in firstLines) {
                appendLog(line)
            }
            appendLog("...（中间省略 $skipped 行）...")
            for (line in lastLines) {
                appendLog(line)
            }
            appendLog("提示：完整输出请查看输出目录中的文件")
        }
    }
    
    private fun performAssemble(inputPath: String) {
        if (isOperationRunning) {
            Toast.makeText(this, "当前有操作正在进行，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                isOperationRunning = true
                showProgress(true)
                binding.assembleButton.isEnabled = false
                binding.disassembleButton.isEnabled = false
                
                appendLog("\n========== 开始汇编 ==========")
                appendLog("输入文件: $inputPath")
                
                // 准备输出目录
                val outputDir = File(filesDir, "output/bundle_${System.currentTimeMillis()}")
                outputDir.mkdirs()
                appendLog("输出目录: ${outputDir.absolutePath}")
                
                val result = withContext(Dispatchers.Default) {
                    try {
                        val python = Python.getInstance()
                        val bridge = python.getModule("api_bridge")
                        
                        val pyResult = bridge.callAttr("do_assemble", inputPath, outputDir.absolutePath)
                        
                        pyResult
                    } catch (e: Exception) {
                        appendLog("❌ Python 调用异常: ${e.javaClass.simpleName}")
                        appendLog("异常消息: ${e.message}")
                        e.printStackTrace()
                        throw e
                    }
                }
                
                // 使用 Python 字典的 get 方法来访问键值
                val status = result.callAttr("get", "status")?.toString() ?: "unknown"
                val log = result.callAttr("get", "log")?.toString() ?: ""
                val error = result.callAttr("get", "error")?.toString()
                
                if (status == "success") {
                    appendLog("✓ 汇编完成！")
                    // 显示 Python 返回的日志（截断过长内容，避免UI卡顿）
                    if (log.isNotEmpty()) {
                        appendTruncatedLog(log)
                    }
                    // 记录输出目录并启用删除和导出按钮
                    lastOutputDir = outputDir.absolutePath
                    isDisassembledOutput = false  // 标记为汇编输出
                    binding.cleanEnvironmentButton.isEnabled = true
                    binding.exportButton.isEnabled = false  // 禁用导出ZIP
                    binding.exportBundleButton.isEnabled = true  // 启用导出Bundle
                    
                    Toast.makeText(this@MainActivity, R.string.operation_success, Toast.LENGTH_SHORT).show()
                } else {
                    appendLog("❌ 汇编失败")
                    if (log.isNotEmpty()) {
                        appendTruncatedLog(log)
                    }
                    if (error != null) {
                        appendLog("错误详情: $error")
                    }
                    Toast.makeText(this@MainActivity, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                appendLog("❌ 错误: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showProgress(false)
                binding.assembleButton.isEnabled = true
                binding.disassembleButton.isEnabled = true
                isOperationRunning = false
            }
        }
    }
    
    private fun showProgress(show: Boolean) {
        binding.progressBar.isVisible = show
    }
    
    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logLine = "[$timestamp] $message\n"
            binding.logTextView.append(logLine)
            
            // 限制日志行数，防止过多文本导致UI卡顿
            val text = binding.logTextView.text
            if (text != null) {
                val lineCount = binding.logTextView.lineCount
                if (lineCount > maxLogLines) {
                    // 只保留最后一半的日志
                    val keepLines = maxLogLines / 2
                    var lineStart = 0
                    var linesToSkip = lineCount - keepLines
                    for (i in 0 until linesToSkip) {
                        lineStart = text.indexOf('\n', lineStart) + 1
                        if (lineStart <= 0) break
                    }
                    if (lineStart > 0) {
                        binding.logTextView.text = "（已省略 ${lineCount - keepLines} 行）\n" + text.substring(lineStart)
                    }
                }
            }
        }
    }
    
    /**
     * 批量追加日志（优化：一次性计算后设置，减少多次重绘）
     */
    private fun appendLogBulk(messages: List<String>) {
        if (messages.isEmpty()) return
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val sb = StringBuilder()
            for (msg in messages) {
                sb.append("[$timestamp] $msg\n")
            }
            binding.logTextView.append(sb.toString())
            
            // 限制日志行数
            val text = binding.logTextView.text
            if (text != null && binding.logTextView.lineCount > maxLogLines) {
                val keepLines = maxLogLines / 2
                var lineStart = 0
                var linesToSkip = binding.logTextView.lineCount - keepLines
                for (i in 0 until linesToSkip) {
                    lineStart = text.indexOf('\n', lineStart) + 1
                    if (lineStart <= 0) break
                }
                if (lineStart > 0) {
                    binding.logTextView.text = "（已省略 ${linesToSkip} 行）\n" + text.substring(lineStart)
                }
            }
        }
    }
    
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun exportOutputFiles() {
        val outputPath = lastOutputDir
        if (outputPath == null) {
            Toast.makeText(this, "没有可导出的文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 验证是否为反汇编输出
        if (!isDisassembledOutput) {
            Toast.makeText(this, "导出ZIP仅适用于反汇编文件", Toast.LENGTH_LONG).show()
            return
        }
        
        val outputDir = File(outputPath)
        if (!outputDir.exists() || !outputDir.isDirectory) {
            Toast.makeText(this, "输出目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 验证是否包含必需的反汇编文件
        val hasMetadata = File(outputDir, "metadata.json").exists()
        val hasString = File(outputDir, "string.json").exists()
        val hasHasm = outputDir.walkTopDown().any { it.extension.equals("hasm", ignoreCase = true) }
        
        if (!hasMetadata || !hasString || !hasHasm) {
            appendLog("❌ 导出失败：缺少必需的反汇编文件")
            appendLog("需要: metadata.json, string.json, instruction.hasm")
            Toast.makeText(this, "输出目录不包含完整的反汇编文件", Toast.LENGTH_LONG).show()
            return
        }
        
        // 创建时间戳文件名
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = "hbctool_output_$timestamp.zip"
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportLauncher.launch(intent)
    }
    
    private fun exportToUri(uri: Uri) {
        val outputPath = lastOutputDir ?: return
        val outputDir = File(outputPath)
        
        if (isOperationRunning) {
            Toast.makeText(this, "当前有操作正在进行，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            isOperationRunning = true
            showProgress(true)
            
            val success = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        ZipOutputStream(outputStream).use { zipStream ->
                            outputDir.walkTopDown().forEach { file ->
                                if (file.isFile) {
                                    val relativePath = file.relativeTo(outputDir).path
                                    val zipEntry = ZipEntry(relativePath)
                                    zipStream.putNextEntry(zipEntry)
                                    
                                    FileInputStream(file).use { inputStream ->
                                        inputStream.copyTo(zipStream)
                                    }
                                    zipStream.closeEntry()
                                }
                            }
                        }
                    }
                    true
                } catch (e: Exception) {
                    appendLog("❌ 导出失败: ${e.message}")
                    false
                }
            }
            
            if (success) {
                appendLog("✓ 文件已导出")
                Toast.makeText(this@MainActivity, "导出成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "导出失败", Toast.LENGTH_SHORT).show()
            }
            
            showProgress(false)
            isOperationRunning = false
        }
    }
    
    private fun exportBundleFile() {
        val outputPath = lastOutputDir
        if (outputPath == null) {
            Toast.makeText(this, "没有可导出的Bundle文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 验证是否为汇编输出
        if (isDisassembledOutput) {
            Toast.makeText(this, "导出Bundle文件仅适用于汇编生成的文件", Toast.LENGTH_LONG).show()
            return
        }
        
        val outputDir = File(outputPath)
        if (!outputDir.exists() || !outputDir.isDirectory) {
            Toast.makeText(this, "输出目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 查找.bundle文件
        val bundleFiles = outputDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("bundle", ignoreCase = true) }
            .toList()
        
        if (bundleFiles.isEmpty()) {
            appendLog("❌ 导出失败：未找到Bundle文件")
            Toast.makeText(this, "输出目录不包含Bundle文件", Toast.LENGTH_LONG).show()
            return
        }
        
        if (bundleFiles.size > 1) {
            appendLog("❌ 导出失败：发现多个Bundle文件")
            Toast.makeText(this, "输出目录包含多个Bundle文件", Toast.LENGTH_LONG).show()
            return
        }
        
        // 创建文件名，确保保留.bundle扩展名
        val bundleFile = bundleFiles.first()
        val fileName = bundleFile.name.let { name ->
            // 确保文件名以.bundle结尾
            if (name.endsWith(".bundle", ignoreCase = true)) {
                name
            } else {
                "$name.bundle"
            }
        }
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"  // 使用通配符MIME类型，避免系统建议改为.bin
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportBundleLauncher.launch(intent)
    }
    
    private fun exportBundleToUri(uri: Uri) {
        val outputPath = lastOutputDir ?: return
        val outputDir = File(outputPath)
        
        if (isOperationRunning) {
            Toast.makeText(this, "当前有操作正在进行，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            isOperationRunning = true
            showProgress(true)
            
            val result = withContext(Dispatchers.IO) {
                try {
                    // 查找.bundle文件
                    val bundleFiles = outputDir.walkTopDown()
                        .filter { it.isFile && it.extension.equals("bundle", ignoreCase = true) }
                        .toList()
                    
                    if (bundleFiles.isEmpty()) {
                        Pair(false, "未找到Bundle文件")
                    } else {
                        val bundleFile = bundleFiles.first()
                        
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            FileInputStream(bundleFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        
                        Pair(true, bundleFile.name)
                    }
                } catch (e: Exception) {
                    Pair(false, e.message ?: "未知错误")
                }
            }
            
            val (success, info) = result
            if (success) {
                appendLog("✓ Bundle文件已导出: $info")
                Toast.makeText(this@MainActivity, "导出成功", Toast.LENGTH_SHORT).show()
            } else {
                appendLog("❌ 导出失败: $info")
                Toast.makeText(this@MainActivity, "导出失败: $info", Toast.LENGTH_SHORT).show()
            }
            
            showProgress(false)
            isOperationRunning = false
        }
    }
    
    private fun importFromUri(uri: Uri) {
        if (isOperationRunning) {
            Toast.makeText(this, "当前有操作正在进行，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fileName = getFileName(uri) ?: "未知"
        
        lifecycleScope.launch {
            isOperationRunning = true
            showProgress(true)
            
            appendLog("\n========== 开始导入 ==========\n导入文件: $fileName")
            
            val result = withContext(Dispatchers.IO) {
                try {
                    // 创建新的输出目录
                    val outputDir = File(filesDir, "output/imported_${System.currentTimeMillis()}")
                    outputDir.mkdirs()
                    
                    var fileCount = 0
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipStream ->
                            var entry = zipStream.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val file = File(outputDir, entry.name)
                                    file.parentFile?.mkdirs()
                                    
                                    FileOutputStream(file).use { outputStream ->
                                        zipStream.copyTo(outputStream)
                                    }
                                    fileCount++
                                }
                                zipStream.closeEntry()
                                entry = zipStream.nextEntry
                            }
                        }
                    }
                    
                    if (fileCount > 0) {
                        // 检查是否有可处理的文件
                        val hasmFiles = outputDir.walkTopDown()
                            .filter { it.isFile && it.extension.equals("hasm", ignoreCase = true) }
                            .toList()
                        val bundleFiles = outputDir.walkTopDown()
                            .filter { it.isFile && it.extension.equals("bundle", ignoreCase = true) }
                            .toList()
                        
                        ImportResult(true, outputDir.absolutePath, fileCount, hasmFiles.size, bundleFiles.size)
                    } else {
                        outputDir.deleteRecursively()
                        ImportResult(false, "", 0, 0, 0, "ZIP 文件为空")
                    }
                } catch (e: Exception) {
                    ImportResult(false, "", 0, 0, 0, e.message ?: "未知错误")
                }
            }
            
            if (result.success) {
                lastOutputDir = result.outputDir
                isDisassembledOutput = true
                binding.cleanEnvironmentButton.isEnabled = true
                binding.exportButton.isEnabled = true
                binding.exportBundleButton.isEnabled = false
                
                appendLog("✓ 导入完成！")
                appendLog("解压文件数: ${result.fileCount}")
                appendLog("输出目录: ${File(result.outputDir).name}")
                
                when {
                    result.hasmCount == 1 && result.bundleCount == 0 -> {
                        // 只有一个 .hasm 文件，自动加载
                        val outputDir = File(result.outputDir)
                        val hasmFile = outputDir.walkTopDown()
                            .first { it.isFile && it.extension.equals("hasm", ignoreCase = true) }
                        hasmFile.parentFile?.let { parent ->
                            selectedFilePath = parent.absolutePath
                            binding.filePathText.text = "已选择目录: ${parent.name}\n（汇编需要完整目录）"
                            binding.assembleButton.isEnabled = true
                            binding.disassembleButton.isEnabled = false
                            appendLog("✓ 已自动加载 HASM 目录: ${parent.name}")
                        }
                        Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
                    }
                    result.bundleCount == 1 && result.hasmCount == 0 -> {
                        // 只有一个 .bundle 文件，自动加载
                        val outputDir = File(result.outputDir)
                        val bundleFile = outputDir.walkTopDown()
                            .first { it.isFile && it.extension.equals("bundle", ignoreCase = true) }
                        selectedFilePath = bundleFile.absolutePath
                        binding.filePathText.text = "已选择: ${bundleFile.name}\n路径: ${bundleFile.absolutePath}"
                        binding.disassembleButton.isEnabled = true
                        binding.assembleButton.isEnabled = false
                        appendLog("✓ 已自动加载 Bundle 文件: ${bundleFile.name}")
                        Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
                    }
                    result.hasmCount > 0 || result.bundleCount > 0 -> {
                        val totalFiles = result.hasmCount + result.bundleCount
                        appendLog("⚠ 发现 $totalFiles 个可处理文件（${result.hasmCount} 个 HASM，${result.bundleCount} 个 Bundle）")
                        appendLog("提示：请使用'选择文件'按钮手动从导入目录选择")
                        Toast.makeText(this@MainActivity, "导入成功，共 ${result.fileCount} 个文件", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        appendLog("⚠ 未发现可处理的文件（.hasm 或 .bundle）")
                        Toast.makeText(this@MainActivity, "导入成功，共 ${result.fileCount} 个文件", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                appendLog("❌ 导入失败: ${result.error}")
                Toast.makeText(this@MainActivity, "导入失败: ${result.error}", Toast.LENGTH_SHORT).show()
            }
            
            showProgress(false)
            isOperationRunning = false
        }
    }
    
    private data class ImportResult(
        val success: Boolean,
        val outputDir: String,
        val fileCount: Int,
        val hasmCount: Int,
        val bundleCount: Int,
        val error: String = ""
    )
    
    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_info)
            .setPositiveButton("确定", null)
            .setNeutralButton("GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://github.com/muort521/HBC-Tool")
                }
                startActivity(intent)
            }
            .show()
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }
    
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }
}
