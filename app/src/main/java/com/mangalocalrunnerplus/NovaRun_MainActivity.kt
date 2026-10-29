package com.mangalocalrunnerplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * NovaRun starter implementation.
 * Uses the project's existing Activity + NanoHTTPD + Chaquopy architecture.
 * Node.js/npm is intentionally NOT advertised because no Node runtime is bundled.
 */
class MainActivity : Activity() {
    private lateinit var rootDir: File
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var fileList: TextView
    private lateinit var portInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var hostInput: EditText
    private var server: LocalServer? = null
    private var editorFile: File? = null
    private var editorText: EditText? = null
    private var editorOriginal = ""
    private var dark = true
    private var webView: WebView? = null

    private val pickZip = 101
    private val exportZip = 102
    private val pickDb = 103
    private val pickPython = 104
    private val pickOther = 105
    private val maxTextBytes = 2 * 1024 * 1024

    private val bg: Int get() = if (dark) Color.rgb(15, 20, 34) else Color.rgb(247, 248, 252)
    private val panel: Int get() = if (dark) Color.rgb(28, 36, 55) else Color.WHITE
    private val fg: Int get() = if (dark) Color.rgb(242, 244, 250) else Color.rgb(25, 30, 42)
    private val muted: Int get() = if (dark) Color.rgb(167, 178, 201) else Color.rgb(95, 104, 120)
    private val accent: Int get() = Color.rgb(111, 104, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("novarun", MODE_PRIVATE)
        dark = prefs.getBoolean("dark", true)
        rootDir = File(filesDir, "workspace").apply { mkdirs() }
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        buildHome()
        refreshFiles()
        log("NovaRun ready. Node.js/npm runtime is not bundled.")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, color: Int = fg) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(panel)
    }

    private fun buildHome() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "N"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            val p = LinearLayout.LayoutParams(dp(48), dp(48))
            p.rightMargin = dp(12)
            layoutParams = p
        }
        header.addView(logo)
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("NovaRun", 25f))
        titleBox.addView(label("Local Development Studio", 12f, muted))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makeButton(if (dark) "Light" else "Dark") {
            dark = !dark
            getSharedPreferences("novarun", MODE_PRIVATE).edit().putBoolean("dark", dark).apply()
            buildHome()
            refreshFiles()
        })
        page.addView(header)

        status = label("Ready — import a project ZIP to begin.", 14f, muted)
        status.setPadding(0, dp(18), 0, dp(12))
        page.addView(status)

        val runner = card()
        runner.addView(label("LOCALHOST RUNNER", 12f, accent))
        runner.addView(label("Host", 12f, muted))
        hostInput = edit("127.0.0.1")
        runner.addView(hostInput)
        runner.addView(label("Port", 12f, muted))
        portInput = edit("8080", InputType.TYPE_CLASS_NUMBER)
        runner.addView(portInput)
        runner.addView(label("URL path", 12f, muted))
        pathInput = edit("/")
        runner.addView(pathInput)
        runner.addView(label("Preview URL (editable)", 12f, muted))
        urlInput = edit("http://127.0.0.1:8080/")
        runner.addView(urlInput)
        val urlButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlButtons.addView(makeButton("Build URL") { buildUrlFromFields() }, LinearLayout.LayoutParams(0, -2, 1f))
        urlButtons.addView(makeButton("Copy URL") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("NovaRun URL", urlInput.text.toString()))
            log("URL copied.")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        runner.addView(urlButtons)
        val serverButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        serverButtons.addView(makeButton("▶ Start") { startServer() }, LinearLayout.LayoutParams(0, -2, 1f))
        serverButtons.addView(makeButton("■ Stop") { stopServer() }, LinearLayout.LayoutParams(0, -2, 1f))
        runner.addView(serverButtons)
        serverButtons.getChildAt(0).setBackgroundColor(Color.rgb(36, 128, 86))
        page.addView(runner, marginTop(12))

        val tools = card()
        tools.addView(label("PROJECT TOOLS", 12f, accent))
        addWideButton(tools, "Import ZIP project") { chooseOpen("application/zip", pickZip) }
        addWideButton(tools, "Export project as ZIP") { chooseSave("application/zip", "novarun-project.zip", exportZip) }
        addWideButton(tools, "Code Editor") { chooseFileToEdit() }
        addWideButton(tools, "Website Preview (in app)") { openPreview() }
        addWideButton(tools, "SQLite Editor") { chooseOpen("*/*", pickDb) }
        addWideButton(tools, "Run Python script") { chooseOpen("*/*", pickPython) }
        addWideButton(tools, "Refresh file list") { refreshFiles() }
        addWideButton(tools, "Clear logs") { logs.text = "Logs cleared." }
        page.addView(tools, marginTop(12))

        page.addView(label("OUTPUT / SERVER LOGS", 12f, accent), marginTop(14))
        logs = label("NovaRun logs will appear here.", 12f, muted)
        logs.setPadding(dp(12), dp(12), dp(12), dp(12))
        logs.setBackgroundColor(panel)
        page.addView(logs)

        page.addView(label("PROJECT FILES (up to 300)", 12f, accent), marginTop(14))
        fileList = label("No project files yet.", 12f, muted)
        fileList.setPadding(dp(12), dp(12), dp(12), dp(12))
        fileList.setBackgroundColor(panel)
        page.addView(fileList)
        page.addView(label("Static websites and embedded Python are supported. Node.js/npm is not bundled in this build.", 11f, muted), marginTop(12))
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun marginTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun edit(value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setText(value)
        inputType = type
        textSize = 14f
        setTextColor(fg)
        setHintTextColor(muted)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundColor(bg)
    }

    private fun makeButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(fg)
        setOnClickListener { action() }
    }

    private fun addWideButton(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(makeButton(text, action), marginTop(4))
    }

    private fun log(message: String) {
        if (!::logs.isInitialized) return
        runOnUiThread {
            val old = logs.text?.toString().orEmpty()
            logs.text = (old + "\n• " + message).takeLast(12000)
        }
    }

    private fun chooseOpen(mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, code)
    }

    private fun chooseSave(mime: String, name: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, code)
    }

    @Deprecated("Activity result API kept for compatibility with this repository")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            pickZip -> importZip(uri)
            exportZip -> exportZip(uri)
            pickDb -> inspectDatabase(uri)
            pickPython -> runPython(uri)
        }
    }

    private fun importZip(uri: Uri) {
        try {
            rootDir.listFiles()?.forEach { it.deleteRecursively() }
            contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(rootDir, entry.name).canonicalFile
                        val base = rootDir.canonicalPath + File.separator
                        require(out.canonicalPath.startsWith(base) || out.canonicalPath == rootDir.canonicalFile.path) {
                            "ZIP contains unsafe path"
                        }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Could not read ZIP")
            status.text = "Project imported."
            log("ZIP import complete.")
            refreshFiles()
        } catch (e: Exception) {
            log("ZIP import failed: ${e.message}")
            status.text = "Import failed: ${e.message}"
        }
    }

    private fun exportZip(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    rootDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("Cannot create ZIP")
            log("Project ZIP exported.")
            status.text = "Project exported."
        } catch (e: Exception) {
            log("ZIP export failed: ${e.message}")
        }
    }

    private fun chooseFileToEdit() {
        val files = rootDir.walkTopDown().filter { it.isFile && it.length() <= maxTextBytes }
            .filter { it.extension.lowercase() in setOf("txt","html","htm","css","js","json","xml","md","py","kt","java","csv","svg","yml","yaml","toml","properties") }
            .take(300).toList()
        if (files.isEmpty()) {
            status.text = "Import a project with text files first."
            return
        }
        AlertDialog.Builder(this).setTitle("Choose file")
            .setItems(files.map { it.relativeTo(rootDir).path }.toTypedArray()) { _, i -> openEditor(files[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openEditor(file: File) {
        val original = try { file.readText(Charsets.UTF_8) } catch (e: Exception) {
            log("Cannot read ${file.name}: ${e.message}"); return
        }
        editorFile = file
        editorOriginal = original
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(bg)
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(makeButton("Find") { findInEditor() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(makeButton("Save") { saveEditor() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(makeButton("Close") { closeEditor() }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(label(file.relativeTo(rootDir).path, 13f, muted))
        box.addView(tools)
        editorText = EditText(this).apply {
            setText(original)
            setTextColor(fg)
            setBackgroundColor(panel)
            setTextSize(14f)
            gravity = Gravity.TOP or Gravity.START
            minLines = 20
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
        }
        box.addView(ScrollView(this).apply { addView(editorText) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(box)
    }

    private fun findInEditor() {
        val input = edit("")
        input.hint = "Find text"
        AlertDialog.Builder(this).setTitle("Find")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val text = editorText?.text?.toString().orEmpty()
                val q = input.text.toString()
                val start = editorText?.selectionStart?.coerceAtLeast(0) ?: 0
                val found = if (q.isEmpty()) -1 else text.indexOf(q, start, ignoreCase = true).let {
                    if (it < 0 && q.isNotEmpty()) text.indexOf(q, 0, ignoreCase = true) else it
                }
                if (found >= 0) {
                    editorText?.requestFocus()
                    editorText?.setSelection(found, found + q.length)
                } else Toast.makeText(this, "Text not found", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveEditor() {
        val file = editorFile ?: return
        try {
            file.writeText(editorText?.text?.toString().orEmpty(), Charsets.UTF_8)
            editorOriginal = file.readText(Charsets.UTF_8)
            log("Saved ${file.relativeTo(rootDir).path}")
            refreshFiles()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { log("Save failed: ${e.message}") }
    }

    private fun closeEditor() {
        val changed = editorText?.text?.toString().orEmpty() != editorOriginal
        if (changed) {
            AlertDialog.Builder(this).setTitle("Unsaved changes")
                .setMessage("Save changes before closing?")
                .setPositiveButton("Save") { _, _ -> saveEditor(); buildHome(); refreshFiles() }
                .setNeutralButton("Discard") { _, _ -> buildHome(); refreshFiles() }
                .setNegativeButton("Cancel", null).show()
        } else { buildHome(); refreshFiles() }
    }

    private fun buildUrlFromFields() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val path = pathInput.text.toString().trim().ifBlank { "/" }
        if (host != "127.0.0.1" && host != "localhost") {
            status.text = "For safety, host must be 127.0.0.1 or localhost in this version."
            return
        }
        if (port == null || port !in 1024..65535) {
            status.text = "Port must be a number from 1024 to 65535."
            return
        }
        val safePath = if (path.startsWith("/")) path else "/$path"
        urlInput.setText("http://127.0.0.1:$port$safePath")
    }

    private fun startServer() {
        if (server != null) { log("Server already running."); return }
        buildUrlFromFields()
        val port = portInput.text.toString().toIntOrNull() ?: return
        val index = findIndex(rootDir)
        if (index == null) {
            status.text = "No index.html found. Import a built website ZIP first."
            log("Start failed: no index.html.")
            return
        }
        try {
            val webRoot = index.parentFile ?: rootDir
            val candidate = LocalServer(port, webRoot)
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = candidate
            val path = pathInput.text.toString().trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
            urlInput.setText("http://127.0.0.1:$port$path")
            status.text = "Server running on 127.0.0.1:$port"
            log("Started static server at http://127.0.0.1:$port/ (root: ${webRoot.name})")
        } catch (e: Exception) {
            server = null
            status.text = "Could not start server: ${e.message}"
            log("Server start failed: ${e.message}. Port may already be in use.")
        }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        if (::status.isInitialized) status.text = "Server stopped."
        log("Server stopped.")
    }

    private fun openPreview() {
        val index = findIndex(rootDir)
        if (index == null) {
            status.text = "No index.html found. Import a built website ZIP."
            return
        }
        if (server == null) {
            val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8080
            try {
                server = LocalServer(port, index.parentFile ?: rootDir).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                portInput.setText(port.toString())
                log("Preview started local server on port $port")
            } catch (e: Exception) {
                log("Preview server failed: ${e.message}")
                status.text = "Could not start preview server: ${e.message}"
                return
            }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previewUrl = "http://127.0.0.1:${portInput.text}/"
        val address = edit(urlInput.text.toString().ifBlank { previewUrl })
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(makeButton("Go") {
            val typed = address.text.toString().trim()
            if (typed.startsWith("http://127.0.0.1:") || typed.startsWith("http://localhost:")) {
                webView?.loadUrl(typed)
            } else {
                val path = if (typed.startsWith("/")) typed else "/$typed"
                webView?.loadUrl("http://127.0.0.1:${portInput.text}$path")
            }
        })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(makeButton("Back") { if (webView?.canGoBack() == true) webView?.goBack() })
        controls.addView(makeButton("Reload") { webView?.reload() })
        controls.addView(makeButton("Home") { webView?.loadUrl("http://127.0.0.1:${portInput.text}/") })
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:${portInput.text}/")
        }
        box.addView(bar)
        box.addView(controls)
        box.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(box)
    }

    private fun findIndex(dir: File): File? {
        File(dir, "index.html").takeIf { it.isFile }?.let { return it }
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            File(child, "index.html").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun refreshFiles() {
        if (!::fileList.isInitialized) return
        val files = rootDir.walkTopDown().filter { it.isFile }.take(300).toList()
        fileList.text = if (files.isEmpty()) "No project files yet." else files.joinToString("\n") { "• ${it.relativeTo(rootDir).path}" }
        fileList.setOnClickListener { showFileManager(files) }
    }

    private fun showFileManager(files: List<File>) {
        if (files.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Project files — select an item")
            .setItems(files.map { it.relativeTo(rootDir).path }.toTypedArray()) { _, i ->
                val file = files[i]
                val actions = arrayOf("Open/Edit", "Rename", "Delete")
                AlertDialog.Builder(this).setTitle(file.name).setItems(actions) { _, action ->
                    when (action) {
                        0 -> openEditor(file)
                        1 -> renameFile(file)
                        2 -> AlertDialog.Builder(this).setTitle("Delete file?")
                            .setMessage(file.relativeTo(rootDir).path)
                            .setPositiveButton("Delete") { _, _ ->
                                if (file.delete()) log("Deleted ${file.name}") else log("Delete failed.")
                                refreshFiles()
                            }.setNegativeButton("Cancel", null).show()
                    }
                }.show()
            }.show()
    }

    private fun renameFile(file: File) {
        val input = edit(file.name)
        AlertDialog.Builder(this).setTitle("Rename file").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains("/") || name == "." || name == "..") {
                    log("Invalid file name."); return@setPositiveButton
                }
                val target = File(file.parentFile, name)
                if (target.exists()) log("A file with that name already exists.")
                else if (file.renameTo(target)) log("Renamed to $name") else log("Rename failed.")
                refreshFiles()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun inspectDatabase(uri: Uri) {
        try {
            val dbFile = File(rootDir, "selected-database.db")
            contentResolver.openInputStream(uri)?.use { input -> dbFile.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot read database")
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val tables = mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            db.close()
            if (tables.isEmpty()) { log("No user tables found in database."); return }
            AlertDialog.Builder(this).setTitle("Choose table")
                .setItems(tables.toTypedArray()) { _, i -> showTable(dbFile, tables[i]) }
                .setNegativeButton("Cancel", null).show()
        } catch (e: Exception) { log("SQLite open failed: ${e.message}") }
    }

    private fun showTable(dbFile: File, table: String) {
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val safe = quoteIdentifier(table)
            val rows = mutableListOf<String>()
            db.rawQuery("SELECT rowid, * FROM `$safe` LIMIT 100", null).use { c ->
                val names = (0 until c.columnCount).map { c.getColumnName(it) }
                while (c.moveToNext()) rows.add((0 until c.columnCount).joinToString(" | ") { c.getString(it) ?: "NULL" })
                if (rows.isEmpty()) rows.add("(no rows)")
                rows.add(0, names.joinToString(" | "))
            }
            db.close()
            AlertDialog.Builder(this).setTitle("$table (first 100 rows)")
                .setItems(rows.toTypedArray()) { _, selected ->
                    if (selected > 0 && rows[selected] != "(no rows)") editRow(dbFile, table, rows[selected])
                }
                .setPositiveButton("Add row") { _, _ -> addRow(dbFile, table) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { log("Table read failed: ${e.message}") }
    }

    private fun editRow(dbFile: File, table: String, display: String) {
        // This basic editor uses rowid and shows editable non-rowid columns.
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val safe = quoteIdentifier(table)
            val cols = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(`$safe`)", null).use { c -> while (c.moveToNext()) cols.add(c.getString(1)) }
            val values = display.split(" | ")
            if (values.size < 2) { db.close(); return }
            val rowId = values[0].toLongOrNull()
            if (rowId == null) { db.close(); log("This table does not expose a usable rowid."); return }
            val fields = cols.mapIndexed { i, col -> "$col = ${values.getOrNull(i + 1) ?: ""}" }
            val message = fields.joinToString("\n")
            val input = edit(message)
            input.minLines = 8
            AlertDialog.Builder(this).setTitle("Edit row (one column per line)")
                .setMessage("Edit each line as column = value. Use NULL for SQL NULL.")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    try {
                        // Keep a recoverable copy before modifying the selected database.
                        File(dbFile.parentFile, "selected-database.db.bak").let { backup ->
                            dbFile.copyTo(backup, overwrite = true)
                            log("SQLite backup saved: ${backup.name}")
                        }
                        val cv = ContentValues()
                        input.text.toString().lines().forEach { line ->
                            val pos = line.indexOf('=')
                            if (pos > 0) {
                                val col = line.substring(0, pos).trim()
                                val value = line.substring(pos + 1).trim()
                                if (col in cols) {
                                    if (value.equals("NULL", true)) cv.putNull(col) else cv.put(col, value)
                                }
                            }
                        }
                        db.beginTransaction()
                        try {
                            db.update(table, cv, "rowid=?", arrayOf(rowId.toString()))
                            db.setTransactionSuccessful()
                            log("Updated row $rowId in $table")
                        } finally { db.endTransaction(); db.close() }
                    } catch (e: Exception) { try { db.close() } catch (_: Exception) {}; log("Row update failed: ${e.message}") }
                    showTable(dbFile, table)
                }.setNegativeButton("Cancel") { _, _ -> db.close() }.show()
        } catch (e: Exception) { log("Could not edit row: ${e.message}") }
    }

    private fun addRow(dbFile: File, table: String) {
        val input = edit("")
        input.hint = "column=value, one per line"
        input.minLines = 6
        AlertDialog.Builder(this).setTitle("Add SQLite row")
            .setMessage("Enter values as column=value, one per line. Text values only in this starter editor.")
            .setView(input)
            .setPositiveButton("Insert") { _, _ ->
                try {
                    val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    val cols = mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info(`${quoteIdentifier(table)}`)", null).use { c -> while (c.moveToNext()) cols.add(c.getString(1)) }
                    val cv = ContentValues()
                    input.text.toString().lines().forEach { line ->
                        val p = line.indexOf('=')
                        if (p > 0) {
                            val col = line.substring(0,p).trim()
                            if (col in cols) cv.put(col, line.substring(p+1).trim())
                        }
                    }
                    val backup = File(rootDir, "selected-database.db.bak")
                    db.close()
                    dbFile.copyTo(backup, overwrite = true)
                    val writable = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                    writable.beginTransaction()
                    try { writable.insertOrThrow(table, null, cv); writable.setTransactionSuccessful(); log("Inserted row into $table") }
                    finally { writable.endTransaction(); writable.close() }
                    showTable(dbFile, table)
                } catch (e: Exception) { log("Insert failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun quoteIdentifier(value: String) = value.replace("`", "``")

    private fun runPython(uri: Uri) {
        try {
            val name = (uri.lastPathSegment ?: "script.py").substringAfterLast('/').substringAfterLast(':')
                .let { if (it.endsWith(".py")) it else "script.py" }
            val target = File(rootDir, name)
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot read Python file")
            val py = Python.getInstance()
            val builtins = py.getModule("builtins")
            val globals = builtins.callAttr("dict")
            builtins.callAttr("exec", target.readText(), globals)
            log("Python script finished: $name")
        } catch (e: Exception) { log("Python error: ${e.message}") }
    }

    private fun stopWebView() {
        try { webView?.stopLoading(); webView?.destroy() } catch (_: Exception) {}
        webView = null
    }

    override fun onDestroy() {
        stopServer()
        stopWebView()
        super.onDestroy()
    }

    private inner class LocalServer(port: Int, private val webRoot: File) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            val decoded = try { URLDecoder.decode(session.uri ?: "/", "UTF-8") } catch (_: Exception) { "/" }
            val rel = decoded.removePrefix("/").ifBlank { "index.html" }
            val target = File(webRoot, rel).canonicalFile
            val base = webRoot.canonicalPath + File.separator
            if (!target.canonicalPath.startsWith(base) && target.canonicalPath != webRoot.canonicalPath) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
            }
            val file = if (target.isDirectory) File(target, "index.html") else target
            if (!file.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            val mime = when (file.extension.lowercase()) {
                "html","htm" -> "text/html; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "js","mjs" -> "application/javascript; charset=utf-8"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg","jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "txt","md" -> "text/plain; charset=utf-8"
                "wasm" -> "application/wasm"
                else -> "application/octet-stream"
            }
            return try { newChunkedResponse(Response.Status.OK, mime, file.inputStream()) }
            catch (_: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Read error") }
        }
    }
}
