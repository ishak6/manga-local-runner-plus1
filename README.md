# Manga Local Runner Plus — Android source starter

A practical Android starter for running and inspecting project files locally.

## Included
- Import ZIP into private app storage (ZIP-slip path protection)
- Serve a built static website on `http://127.0.0.1:8080/`
- Basic embedded Python runner (Python standard library; run `.py` files from imported workspace)
- Read-only SQLite inspector (lists tables and columns)
- Open PDF / other files with compatible Android apps
- Project file list and runtime notes

## Honest limitations
- **React/Vite source is not run directly.** Vite needs Node.js and npm. Build it elsewhere with `npm install` and `npm run build`, then ZIP the generated `dist/` folder (make sure `index.html` is at ZIP root) and import it here.
- **Node.js and npm are not bundled.** The most realistic phone-only option is to use Termux separately for Node.js/npm, build the project there, then import `dist/`. Running a Node backend inside this APK is not implemented.
- **Python:** the app embeds Python through Chaquopy and can run scripts using the embedded interpreter. Third-party packages are not automatically installed. Packages with native extensions may not work unless compatible Android wheels/builds are available. A script expecting desktop Linux/Windows APIs may fail.
- **Database:** read-only inspection of SQLite `.db`, `.sqlite`, `.sqlite3` files. It does not edit rows or support server databases such as MySQL/PostgreSQL.
- This is a source project, not an APK. The included GitHub Actions workflow attempts to build a debug APK; it has not been device-tested here.
- Local server is intended for trusted files on your phone/Wi-Fi. Keep the app open while serving. Do not import/run untrusted code.

## Build APK using GitHub on your phone
1. Download and extract this ZIP using your phone's file manager.
2. On GitHub, create a new repository, e.g. `manga-local-runner-plus`.
3. Upload the **contents** of the extracted folder, not the ZIP itself. Ensure `.github/workflows/build.yml` is present in the repository.
4. Open the repository's **Actions** tab and choose **Build Android APK**. Tap **Run workflow** if it has not started automatically.
5. Wait for the workflow to finish successfully.
6. Open the completed run, find **Artifacts**, and download `Manga-Local-Runner-Plus-debug-apk`.
7. Extract the downloaded artifact ZIP, tap `app-debug.apk`, and allow installation from that source if Android asks.
8. Open Manga Local Runner Plus. Import a website ZIP, start the server, or choose a Python script / SQLite database.

If build fails, open the failed workflow, copy the first red error section, and share it so the build can be fixed.
