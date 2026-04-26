use std::fs;
use zed_extension_api::{self as zed, LanguageServerId, Result, Worktree};

/// Name of the checkstyle jar we download + cache
const CHECKSTYLE_VERSION: &str = "10.21.4";
const CHECKSTYLE_JAR: &str = "checkstyle.jar";

/// Download URL for Checkstyle
fn checkstyle_url() -> String {
    format!(
        "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-{v}/checkstyle-{v}-all.jar",
        v = CHECKSTYLE_VERSION
    )
}

struct JavaLintExtension {
    /// Path to cached checkstyle jar (set after first download)
    checkstyle_jar_path: Option<String>,
}

impl zed::Extension for JavaLintExtension {
    fn new() -> Self {
        JavaLintExtension {
            checkstyle_jar_path: None,
        }
    }

    /// Called by Zed when it needs to start the language server for a Java file.
    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        worktree: &Worktree,
    ) -> Result<zed::Command> {
        // ── 1. Resolve / download Checkstyle jar ──────────────────────────
        let jar_path = self.get_or_download_checkstyle()?;

        // ── 2. Look for a checkstyle config in the project root ────────────
        //    Priority: checkstyle.xml → .checkstyle → bundled google_checks.xml
        let root = worktree.root_path();
        let config_path = find_config(&root).unwrap_or_else(|| {
            // Fall back to the bundled Google style config shipped with the jar
            "google_checks.xml".to_string()
        });

        // ── 3. Launch the checkstyle-ls wrapper ────────────────────────────
        //    checkstyle-ls is a tiny LSP shim that wraps the Checkstyle jar
        //    and speaks the Language Server Protocol over stdin/stdout.
        //    We ship it as a second jar bundled with this extension.
        let lsp_shim = self.get_or_download_lsp_shim()?;

        Ok(zed::Command {
            command: "java".into(),
            args: vec![
                "-jar".into(),
                lsp_shim,
                // pass the real checkstyle jar and config to the shim
                "--checkstyle-jar".into(),
                jar_path,
                "--config".into(),
                config_path,
            ],
            env: vec![],
        })
    }

    /// Called when the user changes LSP settings so we can reconfigure.
    fn language_server_initialization_options(
        &mut self,
        _language_server_id: &LanguageServerId,
        _worktree: &Worktree,
    ) -> Result<Option<serde_json::Value>> {
        Ok(Some(serde_json::json!({
            "checkstyleVersion": CHECKSTYLE_VERSION,
        })))
    }
}

// ── helpers ──────────────────────────────────────────────────────────────────

impl JavaLintExtension {
    /// Returns a path to the Checkstyle jar, downloading it if necessary.
    fn get_or_download_checkstyle(&mut self) -> Result<String> {
        if let Some(ref p) = self.checkstyle_jar_path {
            return Ok(p.clone());
        }

        // Zed gives each extension its own writable work directory
        let dir = zed::Extension::work_dir(self);
        let path = format!("{}/{}", dir, CHECKSTYLE_JAR);

        if !fs::metadata(&path).map(|m| m.is_file()).unwrap_or(false) {
            zed::download_file(
                &checkstyle_url(),
                &path,
                zed::DownloadedFileType::Uncompressed,
            )
            .map_err(|e| format!("Failed to download Checkstyle: {e}"))?;
        }

        self.checkstyle_jar_path = Some(path.clone());
        Ok(path)
    }

    /// Returns path to the bundled LSP shim jar.
    fn get_or_download_lsp_shim(&self) -> Result<String> {
        // The shim jar is bundled inside the extension at build time.
        // Zed copies extension assets into the work directory automatically.
        let dir = zed::Extension::work_dir(self);
        let path = format!("{}/checkstyle-lsp-shim.jar", dir);

        // If for some reason it's missing, surface a clear error.
        if !fs::metadata(&path).map(|m| m.is_file()).unwrap_or(false) {
            return Err(
                "checkstyle-lsp-shim.jar not found — please reinstall the extension".to_string(),
            );
        }

        Ok(path)
    }
}

/// Walk up from `root` looking for a Checkstyle config file.
fn find_config(root: &str) -> Option<String> {
    for name in &["checkstyle.xml", ".checkstyle", "checkstyle-config.xml"] {
        let candidate = format!("{}/{}", root, name);
        if fs::metadata(&candidate).map(|m| m.is_file()).unwrap_or(false) {
            return Some(candidate);
        }
    }
    None
}

zed::register_extension!(JavaLintExtension);
