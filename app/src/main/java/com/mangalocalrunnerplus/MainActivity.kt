package com.mangalocalrunnerplus

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import fi.iki.elonen.NanoHTTPD

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var fileList: TextView
    private lateinit var rootDir: File
    private var server: LocalServer? = null

    private val pickZip = 101
    private val pickPython = 102
    private val pickDb = 103
    private val pickPdf = 104

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootDir = File(filesDir, "workspace").apply { mkdirs() }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        buildUi()
        refreshFiles()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
        }

        layout.addView(TextView(this).apply {
            text = "Manga Local Runner Plus"
            textSize = 25f
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            text = "Import a ZIP project to begin."
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }

        output = TextView(this).apply {
            textSize = 13f
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFFF0F0F0.toInt())
            text = "Output appears here."
        }

        fileList = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }

        layout.addView(status)
        addButton(layout, "Import ZIP project") { choose("application/zip", pickZip) }
        addButton(layout, "Start local website server") { startServer() }
        addButton(layout, "Stop server") { stopServer() }
        addButton(layout, "Open website in browser") { openUrl("http://127.0.0.1:8080/") }
        addButton(layout, "Run Python script (.py)") { choose("*/*", pickPython) }
        addButton(layout, "Inspect SQLite database") { choose("*/*", pickDb) }
        addButton(layout, "Open PDF / other file") { choose("*/*", pickPdf) }
        addButton(layout, "Refresh project file list") { refreshFiles() }

        layout.addView(TextView(this).apply {
            text = "Runtime notes: static websites + embedded Python + read-only SQLite. Node/npm/Vite source is not executed directly; build Vite to dist first."
            textSize = 12f
            setPadding(0, 12, 0, 12)
        })

        layout.addView(output)
        layout.addView(fileList)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun addButton(layout: LinearLayout, label: String, action: () -> Unit) {
        layout.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 7
        })
    }

    private fun choose(mime: String, request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, request)
    }

    @Deprecated("Starter uses onActivityResult for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            pickZip -> importZip(uri)
            pickPython -> runPython(uri)
            pickDb -> inspectDb(uri)
            pickPdf -> openExternal(uri)
        }
    }

    private fun importZip(uri: Uri) {
        try {
            clearWorkspace()
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(rootDir, entry.name).canonicalFile
                        val rootPath = rootDir.canonicalPath + File.separator
                        require(
                            outFile.canonicalPath.startsWith(rootPath) ||
                                outFile.canonicalPath == rootDir.canonicalPath
                        ) {
                            "Unsafe ZIP path detected"
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zip.copyTo(it) }
                        }

                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot read selected file")

            status.text = "ZIP imported. Choose an action below."
            refreshFiles()
        } catch (e: Exception) {
            status.text = "ZIP import failed: ${e.message}"
        }
    }

    private fun runPython(uri: Uri) {
        try {
            val name = (uri.lastPathSegment ?: "script.py")
                .substringAfterLast('/')
                .substringAfterLast(':')
                .let { if (it.endsWith(".py")) it else "script.py" }

            val target = File(rootDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read script")

            val source = target.readText()
            val py = Python.getInstance()
            val builtins = py.getModule("builtins")
            val globals = builtins.callAttr("dict")
            val result = builtins.callAttr("exec", source, globals)

            output.text = "Python script completed.\n$result"
            status.text = "Python run finished: ${target.name}"
        } catch (e: Exception) {
            output.text = "Python error:\n${e.message ?: e.toString()}"
            status.text = "Python script failed."
        }
    }

    private fun inspectDb(uri: Uri) {
        try {
            val dbFile = File(rootDir, "selected-database.db")
            contentResolver.openInputStream(uri)?.use { input ->
                dbFile.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read database")

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val lines = mutableListOf<String>("SQLite database: ${dbFile.name}")

            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0)
                    lines.add("\nTable: $table")
                    val safeTable = table.replace("`", "``")
                    db.rawQuery("PRAGMA table_info(`$safeTable`)", null).use { info ->
                        while (info.moveToNext()) {
                            lines.add("  ${info.getString(1)} : ${info.getString(2)}")
                        }
                    }
                }
            }

            db.close()
            output.text = lines.joinToString("\n")
            status.text = "Database opened read-only. No rows were changed."
        } catch (e: Exception) {
            output.text = "Database inspection error:\n${e.message ?: e.toString()}"
            status.text = "Could not inspect database."
        }
    }

    private fun openExternal(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "*/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No compatible app found for this file.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearWorkspace() {
        rootDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun startServer() {
        try {
            if (server != null) {
                status.text = "Server already running at http://127.0.0.1:8080/"
                return
            }

            val index = findIndex(rootDir)
            if (index == null) {
                status.text = "No index.html found. Import a built website ZIP with index.html at root."
                return
            }

            server = LocalServer(8080, index.parentFile ?: rootDir).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            status.text = "Server running at http://127.0.0.1:8080/\nKeep app open while using it."
        } catch (e: Exception) {
            status.text = "Could not start server: ${e.message}"
        }
    }

    private fun findIndex(dir: File): File? {
        File(dir, "index.html").takeIf { it.isFile }?.let { return it }
        (dir.listFiles()?.filter { it.isDirectory } ?: emptyList()).forEach { child ->
            File(child, "index.html").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshFiles() {
        val all = rootDir.walkTopDown().filter { it.isFile }.take(300).toList()
        fileList.text = if (all.isEmpty()) {
            "Project files appear here after importing a ZIP."
        } else {
            "Project files (up to 300):\n" + all.joinToString("\n") { file ->
                val note = when (file.extension.lowercase()) {
                    "pdf" -> " [PDF]"
                    "db", "sqlite", "sqlite3" -> " [SQLITE DATABASE]"
                    "json", "manga", "msai" -> " [PROJECT/DATA]"
                    "html" -> " [WEB]"
                    "py" -> " [PYTHON]"
                    "ts", "tsx", "js", "jsx" -> " [SOURCE; BUILD REQUIRED]"
                    else -> ""
                }
                "• ${file.relativeTo(rootDir).path}$note"
            }
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private class LocalServer(port: Int, private val webRoot: File) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val raw = session.uri ?: "/"
            val decoded = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }

            val rel = decoded.removePrefix("/").ifBlank { "index.html" }
            val target = File(webRoot, rel).canonicalFile
            val base = webRoot.canonicalPath + File.separator

            if (target.canonicalPath != webRoot.canonicalPath &&
                !target.canonicalPath.startsWith(base)
            ) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain",
                    "Forbidden"
                )
            }

            val file = if (target.isDirectory) File(target, "index.html") else target
            if (!file.isFile) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "File not found"
                )
            }

            val mime = when (file.extension.lowercase()) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js", "mjs" -> "application/javascript"
                "json", "map" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "wasm" -> "application/wasm"
                else -> "application/octet-stream"
            }

            return newChunkedResponse(Response.Status.OK, mime, file.inputStream())
        }
    }
}
