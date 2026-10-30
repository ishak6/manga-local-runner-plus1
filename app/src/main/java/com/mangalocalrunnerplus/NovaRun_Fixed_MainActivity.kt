package com.mangalocalrunnerplus

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URLDecoder

class MainActivity : Activity() {
    private lateinit var projectDir: File
    private lateinit var statusView: TextView
    private lateinit var portField: EditText
    private lateinit var urlField: EditText
    private lateinit var logView: TextView
    private var server: LocalServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectDir = File(filesDir, "NovaRunProject").apply {
            if (!exists()) mkdirs()
            val index = File(this, "index.html")
            if (!index.exists()) index.writeText(
                "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><title>NovaRun</title></head><body style='font-family:sans-serif;padding:24px'><h1>Welcome to NovaRun</h1><p>Edit index.html to get started.</p></body></html>"
            )
        }
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 24)
            setBackgroundColor(Color.rgb(15, 19, 30))
        }
        scroll.addView(page)
        setContentView(scroll)
        page.addView(label("NOVARUN", 30, Color.WHITE))
        page.addView(label("Mobile Local Development Studio", 14, Color.LTGRAY))
        statusView = label("● Server stopped", 16, Color.rgb(255, 170, 80))
        page.addView(statusView)
        page.addView(label("SERVER SETTINGS", 13, Color.rgb(130, 180, 255)))
        portField = EditText(this).apply {
            setSingleLine(true); setText("8080"); hint = "Port"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        page.addView(portField, matchWidth())
        urlField = EditText(this).apply {
            setSingleLine(true); setText("/"); hint = "Website path"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        page.addView(urlField, matchWidth())
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("START") { startServer() }, weighted())
        buttons.addView(actionButton("STOP") { stopServer() }, weighted())
        page.addView(buttons)
        page.addView(label("PROJECT FILES", 13, Color.rgb(130, 180, 255)))
        page.addView(actionButton("Refresh file list") { showFiles() })
        page.addView(actionButton("New file") { createFile() })
        page.addView(actionButton("Open index.html editor") {
            editFile(File(projectDir, "index.html"))
        })
        page.addView(label("WEBSITE PREVIEW", 13, Color.rgb(130, 180, 255)))
        page.addView(actionButton("Open / Reload preview") { loadPreview() })
        logView = label("Ready. Project: " + projectDir.name, 12, Color.LTGRAY)
        page.addView(logView)
    }

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setPadding(0, 10, 0, 10)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    )

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
    }

    private fun log(message: String) { logView.text = message }

    private fun startServer() {
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            log("Enter a valid port between 1024 and 65535")
            return
        }
        try {
            stopServer()
            server = LocalServer(projectDir, port).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            statusView.text = "● Server running"
            statusView.setTextColor(Color.rgb(90, 220, 150))
            log("Running at http://127.0.0.1:$port/")
        } catch (e: Exception) {
            server = null
            statusView.text = "● Server stopped"
            log("Could not start server: " + (e.message ?: "unknown error"))
        }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        if (::statusView.isInitialized) {
            statusView.text = "● Server stopped"
            statusView.setTextColor(Color.rgb(255, 170, 80))
        }
        if (::logView.isInitialized) log("Server stopped")
    }

    private fun loadPreview() {
        val port = portField.text.toString().toIntOrNull() ?: 8080
        val path = urlField.text.toString().ifBlank { "/" }.let {
            if (it.startsWith("/")) it else "/$it"
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:$port$path")
        }
        val dialog = AlertDialog.Builder(this).setTitle("NovaRun Preview")
            .setView(web).setPositiveButton("Close", null).create()
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.8).toInt()
        )
    }

    private fun showFiles() {
        val files = projectDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val names = files.map {
            (if (it.isDirectory) "Folder: " else "File: ") + it.name
        }.toTypedArray()
        if (names.isEmpty()) { log("Project folder is empty"); return }
        AlertDialog.Builder(this).setTitle("Project files")
            .setItems(names) { _, which ->
                val selected = files[which]
                if (selected.isFile) editFile(selected)
            }.setNegativeButton("Close", null).show()
    }

    private fun createFile() {
        val input = EditText(this).apply {
            hint = "example.html"; setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Create file").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !name.contains("/") &&
                    !name.contains("\\") && name != "." && name != "..") {
                    val file = File(projectDir, name)
                    if (!file.exists()) {
                        file.writeText("")
                        editFile(file)
                    } else log("File already exists")
                } else log("Invalid file name")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editFile(file: File) {
        if (!file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)) {
            log("File path is outside project")
            return
        }
        val editor = EditText(this).apply {
            setText(file.readText())
            minLines = 12
            gravity = android.view.Gravity.TOP
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this).setTitle(file.name).setView(editor)
            .setPositiveButton("Save") { _, _ ->
                file.writeText(editor.text.toString())
                log("Saved " + file.name)
            }.setNeutralButton("Delete") { _, _ ->
                if (file.name != "index.html") file.delete()
                log("Delete requested: " + file.name)
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private class LocalServer(private val root: File, port: Int) :
        NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                val raw = URLDecoder.decode(session.uri, "UTF-8").removePrefix("/")
                val relative = if (raw.isBlank()) "index.html" else raw
                val target = File(root, relative).canonicalFile
                if (!target.path.startsWith(root.canonicalPath + File.separator) &&
                    target != root.canonicalFile) {
                    newFixedLengthResponse(
                        Response.Status.FORBIDDEN, "text/plain", "Forbidden"
                    )
                } else if (!target.isFile) {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, "text/plain", "Not found"
                    )
                } else {
                    val mime = when (target.extension.lowercase()) {
                        "html", "htm" -> "text/html; charset=utf-8"
                        "css" -> "text/css; charset=utf-8"
                        "js" -> "application/javascript; charset=utf-8"
                        "json" -> "application/json; charset=utf-8"
                        "svg" -> "image/svg+xml"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        else -> "text/plain; charset=utf-8"
                    }
                    newFixedLengthResponse(
                        Response.Status.OK, mime, target.inputStream(), target.length()
                    )
                }
            } catch (_: Exception) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "Server error"
                )
            }
        }
    }
}
