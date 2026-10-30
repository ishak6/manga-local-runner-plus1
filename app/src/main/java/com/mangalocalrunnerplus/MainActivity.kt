package com.mangalocalrunnerplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    companion object {
        private const val ZIP_REQUEST = 4102
        private const val MAX_ENTRIES = 5000
        private const val MAX_BYTES = 200L * 1024L * 1024L
    }

    private lateinit var projectDir: File
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var portField: EditText
    private var server: LocalServer? = null

    private val bg = Color.rgb(12, 17, 32)
    private val panel = Color.rgb(25, 34, 55)
    private val blue = Color.rgb(65, 130, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(165, 180, 205)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectDir = File(filesDir, "NovaRunProject").apply { mkdirs() }
        val index = File(projectDir, "index.html")
        if (!index.exists()) index.writeText(
            """<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<title>NovaRun</title></head><body style="font-family:sans-serif;padding:30px">
<h1>Welcome to NovaRun!</h1><p>Your coding workspace is ready.</p>
</body></html>"""
        )
        buildDashboard()
    }

    private fun buildDashboard() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 30)
            setBackgroundColor(bg)
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(TextView(this).apply {
            text = "N ▶ NovaRun"; textSize = 30f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(white)
        })
        page.addView(label("CODE • BUILD • RUN • ANYWHERE", 12, blue))
        page.addView(label("Welcome back!\nYour coding workspace", 23, white))
        val status = card()
        statusView = label("● Server stopped", 15, muted)
        status.addView(statusView); page.addView(status)

        page.addView(section("PROJECT"))
        action(page, "📦", "Import ZIP Project", "Select and extract a ZIP") { pickZip() }
        action(page, "📁", "Browse Project Files", "Open folders and edit files") { browse() }
        action(page, "＋", "Create New File", "Create HTML, CSS or JS file") { createFile() }
        action(page, "✏️", "Edit index.html", "Edit the main webpage") { edit(File(projectDir, "index.html")) }

        page.addView(section("PREVIEW & SERVER"))
        action(page, "🌐", "Web Preview", "Run and preview the local site") { preview() }
        val serverCard = card()
        portField = EditText(this).apply {
            setSingleLine(true); setText("8080"); hint = "Server port"
            setTextColor(white); setHintTextColor(muted)
        }
        serverCard.addView(portField, match())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("START SERVER") { startServer() }, weighted())
        row.addView(button("STOP SERVER") { stopServer() }, weighted())
        serverCard.addView(row); page.addView(serverCard)

        page.addView(section("ACTIVITY LOG"))
        val logCard = card()
        logView = label("NovaRun is ready.\nProject: ${projectDir.name}", 13, muted)
        logCard.addView(logView); page.addView(logCard)
    }

    private fun label(s: String, size: Int, color: Int) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(color); setPadding(0, 7, 0, 7)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); setBackgroundColor(panel)
    }
    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(blue); setPadding(0, 22, 0, 10)
    }
    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; setTextColor(white); setBackgroundColor(blue); setOnClickListener { action() }
    }
    private fun action(parent: LinearLayout, icon: String, title: String, sub: String, click: () -> Unit) {
        val item = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        item.addView(label(icon, 24, white))
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detail.addView(label(title, 16, white)); detail.addView(label(sub, 12, muted))
        item.addView(detail); item.setOnClickListener { click() }
        val lp = match(); lp.bottomMargin = 10; parent.addView(item, lp)
    }
    private fun log(s: String) { if (::logView.isInitialized) logView.text = s }

    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
            ))
        }
        startActivityForResult(i, ZIP_REQUEST)
    }

    @Deprecated("Activity result API retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ZIP_REQUEST && resultCode == RESULT_OK) data?.data?.let { importZip(it) }
    }

    private fun importZip(uri: Uri) {
        val temp = File(cacheDir, "novarun-import-${System.currentTimeMillis()}")
        try {
            temp.mkdirs()
            var count = 0
            var bytes = 0L
            val root = temp.canonicalFile
            val stream = contentResolver.openInputStream(uri) ?: error("Cannot open ZIP")
            stream.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (++count > MAX_ENTRIES) error("ZIP has more than $MAX_ENTRIES entries")
                        val outFile = File(temp, entry.name).canonicalFile
                        if (outFile.path != root.path && !outFile.path.startsWith(root.path + File.separator))
                            throw SecurityException("Unsafe ZIP path blocked")
                        if (entry.isDirectory) outFile.mkdirs() else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    bytes += n
                                    if (bytes > MAX_BYTES) error("Extracted ZIP exceeds 200 MB")
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            val top = temp.listFiles()?.toList().orEmpty()
            val items = if (top.size == 1 && top[0].isDirectory)
                top[0].listFiles()?.toList().orEmpty() else top
            if (items.isEmpty()) error("ZIP is empty")
            items.forEach { src ->
                val dest = File(projectDir, src.name)
                if (dest.exists()) dest.deleteRecursively()
                src.copyRecursively(dest, overwrite = true)
            }
            log("ZIP imported: $count entries")
            Toast.makeText(this, "ZIP imported successfully", Toast.LENGTH_LONG).show()
            browse()
        } catch (e: Exception) {
            log("ZIP import failed: ${e.message}")
            AlertDialog.Builder(this).setTitle("ZIP Import Failed")
                .setMessage(e.message ?: "Invalid ZIP file").setPositiveButton("OK", null).show()
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun browse(folder: File = projectDir) {
        val root = projectDir.canonicalPath
        val current = folder.canonicalFile
        if (current.path != root && !current.path.startsWith(root + File.separator)) {
            log("Access denied"); return
        }
        val files = current.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ).orEmpty()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File>()
        if (current != projectDir.canonicalFile) {
            labels.add("⬅ .."); targets.add(current.parentFile ?: projectDir)
        }
        files.forEach {
            labels.add((if (it.isDirectory) "📁 " else "📄 ") + it.name); targets.add(it)
        }
        if (labels.isEmpty()) { log("Folder is empty"); return }
        AlertDialog.Builder(this).setTitle("Project Files")
            .setItems(labels.toTypedArray()) { _, n ->
                val f = targets[n]; if (f.isDirectory) browse(f) else edit(f)
            }.setNegativeButton("Close", null).show()
    }

    private fun createFile() {
        val input = EditText(this).apply { hint = "example.html"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create New File").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains("/") || name.contains("\\") || name == "." || name == "..") {
                    log("Invalid file name")
                } else {
                    val f = File(projectDir, name)
                    if (f.exists()) log("File already exists") else { f.writeText(""); edit(f) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun edit(file: File) {
        val root = projectDir.canonicalPath
        if (!file.canonicalPath.startsWith(root + File.separator) || !file.isFile) {
            log("Access denied"); return
        }
        val editor = EditText(this).apply {
            setText(file.readText()); minLines = 16
            gravity = Gravity.TOP or Gravity.START; textSize = 14f
            typeface = Typeface.MONOSPACE; setTextColor(white)
            setPadding(14, 14, 14, 14); setBackgroundColor(Color.rgb(17, 24, 39))
        }
        val scroll = ScrollView(this).apply { addView(editor) }
        AlertDialog.Builder(this).setTitle("Edit: ${file.name}").setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                try { file.writeText(editor.text.toString()); log("Saved ${file.name}") }
                catch (e: Exception) { log("Save failed: ${e.message}") }
            }
            .setNeutralButton("Delete") { _, _ ->
                if (file.name == "index.html") log("Cannot delete index.html")
                else if (file.delete()) log("Deleted ${file.name}") else log("Delete failed")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startServer() {
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) { log("Port must be 1024-65535"); return }
        try {
            stopServer()
            server = LocalServer(projectDir, port).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            statusView.text = "● Server running: 127.0.0.1:$port"
            statusView.setTextColor(Color.rgb(80, 220, 150))
            log("Server: http://127.0.0.1:$port/")
        } catch (e: Exception) { server = null; log("Server error: ${e.message}") }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        if (::statusView.isInitialized) { statusView.text = "● Server stopped"; statusView.setTextColor(muted) }
    }

    private fun preview() {
        if (server == null) startServer()
        if (server == null) return
        val port = portField.text.toString().toIntOrNull() ?: return
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = WebViewClient(); webChromeClient = WebChromeClient()
            loadUrl("http://127.0.0.1:$port/")
        }
        val dialog = AlertDialog.Builder(this).setTitle("NovaRun Preview")
            .setView(web).setPositiveButton("Close", null).create()
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.82).toInt())
    }

    override fun onDestroy() { stopServer(); super.onDestroy() }

    private class LocalServer(private val root: File, port: Int) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response = try {
            val uri = URLDecoder.decode(session.uri, "UTF-8")
            val relative = uri.removePrefix("/").ifBlank { "index.html" }
            val base = root.canonicalFile
            val target = File(base, relative).canonicalFile
            if (!target.path.startsWith(base.path + File.separator)) {
                newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
            } else if (!target.isFile) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            } else {
                val mime = when (target.extension.lowercase()) {
                    "html", "htm" -> "text/html; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "js" -> "application/javascript; charset=utf-8"
                    "json" -> "application/json; charset=utf-8"
                    "svg" -> "image/svg+xml"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "txt" -> "text/plain; charset=utf-8"
                    else -> "application/octet-stream"
                }
                newFixedLengthResponse(Response.Status.OK, mime, target.inputStream(), target.length())
            }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error")
        }
    }
}
