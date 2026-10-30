
package com.mangalocalrunnerplus

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
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

        projectDir = File(filesDir, "NovaRunProject")
        if (!projectDir.exists()) projectDir.mkdirs()

        val index = File(projectDir, "index.html")
        if (!index.exists()) {
            index.writeText("""
                <!DOCTYPE html>
                <html>
                <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>NovaRun</title>
                </head>
                <body style="font-family:sans-serif;padding:30px">
                <h1>Welcome to NovaRun!</h1>
                <p>Your mobile development workspace is ready.</p>
                </body>
                </html>
            """.trimIndent())
        }

        buildDashboard()
    }

    private fun buildDashboard() {
        val scroll = ScrollView(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 30)
            setBackgroundColor(bg)
        }

        scroll.addView(page)
        setContentView(scroll)

        val brand = TextView(this).apply {
            text = "N ▶ NovaRun"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }
        page.addView(brand)

        page.addView(text(
            "CODE  •  BUILD  •  RUN  •  ANYWHERE",
            12, blue
        ))

        page.addView(space(20))

        val welcome = TextView(this).apply {
            text = "Welcome back!\nYour coding workspace"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }
        page.addView(welcome)

        page.addView(space(18))

        val statusCard = card()
        statusView = text("● Server stopped", 15, muted)
        statusCard.addView(statusView)
        page.addView(statusCard)

        page.addView(section("DEVELOPMENT TOOLS"))

        addAction(page, "📁", "Project Manager",
            "Browse project files") { showFiles() }

        addAction(page, "✏️", "Code Editor",
            "Edit HTML, CSS and JavaScript") {
            editFile(File(projectDir, "index.html"))
        }

        addAction(page, "🌐", "Web Preview",
            "Preview your local website") {
            loadPreview()
        }

        page.addView(section("LOCAL SERVER"))

        val serverCard = card()

        portField = EditText(this).apply {
            setSingleLine(true)
            setText("8080")
            hint = "Server port"
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 16f
        }
        serverCard.addView(portField, matchWidth())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        buttons.addView(
            button("START SERVER") { startServer() },
            weighted()
        )

        buttons.addView(
            button("STOP") { stopServer() },
            weighted()
        )

        serverCard.addView(buttons)
        page.addView(serverCard)

        page.addView(section("PROJECT FILES"))

        addAction(page, "＋", "Create New File",
            "Add a file to your project") {
            createFile()
        }

        addAction(page, "📂", "Browse Files",
            "Open and edit project files") {
            showFiles()
        }

        page.addView(section("ACTIVITY LOG"))

        val logCard = card()
        logView = text("NovaRun is ready.\nProject: ${projectDir.name}", 13, muted)
        logCard.addView(logView)
        page.addView(logCard)

        page.addView(space(18))
        page.addView(text(
            "NovaRun • Mobile Development Studio",
            12, muted
        ))
    }

    private fun text(value: String, size: Int, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setPadding(0, 7, 0, 7)
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundColor(panel)
        }
    }

    private fun section(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
            setPadding(0, 22, 0, 10)
        }
    }

    private fun space(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, height)
        }
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun weighted() =
        LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(
        title: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = title
            setTextColor(white)
            setBackgroundColor(blue)
            setOnClickListener { action() }
        }
    }

    private fun addAction(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ) {
        val item = card()
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL

        val left = TextView(this).apply {
            text = icon
            textSize = 25f
            setTextColor(white)
            setPadding(0, 0, 16, 0)
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        details.addView(text(title, 16, white))
        details.addView(text(subtitle, 12, muted))

        item.addView(left)
        item.addView(details)

        item.setOnClickListener { action() }

        val params = matchWidth()
        params.bottomMargin = 10
        parent.addView(item, params)
    }

    private fun log(message: String) {
        logView.text = message
    }

    private fun startServer() {
        val port = portField.text.toString().toIntOrNull()

        if (port == null || port !in 1024..65535) {
            log("Invalid port. Use 1024-65535.")
            return
        }

        try {
            stopServer()

            server = LocalServer(projectDir, port).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }

            statusView.text = "● Server running"
            statusView.setTextColor(Color.rgb(80, 220, 150))
            log("Server running:\nhttp://127.0.0.1:$port/")

        } catch (e: Exception) {
            server = null
            statusView.text = "● Server stopped"
            log("Server error: ${e.message}")
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }

        server = null

        if (::statusView.isInitialized) {
            statusView.text = "● Server stopped"
            statusView.setTextColor(muted)
        }
    }

    private fun loadPreview() {
        val port = portField.text.toString().toIntOrNull() ?: 8080

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:$port/")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("NovaRun Preview")
            .setView(web)
            .setPositiveButton("Close", null)
            .create()

        dialog.setOnDismissListener {
            web.destroy()
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.8).toInt()
        )
    }

    private fun showFiles() {
        val files = projectDir.listFiles()
            ?.sortedBy { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            log("No files found.")
            return
        }

        val names = files.map {
            (if (it.isDirectory) "📁 " else "📄 ") + it.name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Project Files")
            .setItems(names) { _, index ->
                val file = files[index]
                if (file.isFile) editFile(file)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun createFile() {
        val input = EditText(this).apply {
            hint = "example.html"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Create New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isBlank() ||
                    name.contains("/") ||
                    name.contains("\\") ||
                    name == "." || name == "..") {
                    log("Invalid file name")
                    return@setPositiveButton
                }

                val file = File(projectDir, name)

                if (!file.exists()) {
                    file.writeText("")
                    editFile(file)
                } else {
                    log("File already exists")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editFile(file: File) {
        if (!file.canonicalPath.startsWith(
                projectDir.canonicalPath + File.separator
            )) {
            log("Access denied")
            return
        }

        val editor = EditText(this).apply {
            setText(file.readText())
            minLines = 14
            gravity = Gravity.TOP
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                file.writeText(editor.text.toString())
                log("Saved: ${file.name}")
            }
            .setNeutralButton("Delete") { _, _ ->
                if (file.name != "index.html") {
                    file.delete()
                    log("Deleted: ${file.name}")
                } else {
                    log("Cannot delete main index.html")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private class LocalServer(
        private val root: File,
        port: Int
    ) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                val decoded = URLDecoder.decode(
                    session.uri, "UTF-8"
                )

                val relative = decoded.removePrefix("/")
                    .ifBlank { "index.html" }

                val target = File(root, relative).canonicalFile
                val rootPath = root.canonicalPath

                if (!target.path.startsWith(rootPath + File.separator)) {
                    newFixedLengthResponse(
                        Response.Status.FORBIDDEN,
                        "text/plain",
                        "Forbidden"
                    )
                } else if (!target.isFile) {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Not found"
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
                        Response.Status.OK,
                        mime,
                        target.inputStream(),
                        target.length()
                    )
                }

            } catch (_: Exception) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Server error"
                )
            }
        }
    }
}
