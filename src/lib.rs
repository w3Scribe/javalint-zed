use zed_extension_api::{self as zed, LanguageServerId, Result, Worktree};

struct JavaLintExtension;

impl zed::Extension for JavaLintExtension {
    fn new() -> Self {
        JavaLintExtension
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        _worktree: &Worktree,
    ) -> Result<zed::Command> {
        // Get the directory where this extension's files live
        let extension_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_default();

        let shim_jar = extension_dir
            .join("checkstyle-lsp-shim.jar")
            .to_string_lossy()
            .to_string();

        let checkstyle_jar = extension_dir
            .join("checkstyle.jar")
            .to_string_lossy()
            .to_string();

        let config = extension_dir
            .join("checkstyle.xml")
            .to_string_lossy()
            .to_string();

        Ok(zed::Command {
            command: "java".into(),
            args: vec![
                "-jar".into(),
                shim_jar,
                "--checkstyle-jar".into(),
                checkstyle_jar,
                "--config".into(),
                config,
            ],
            env: vec![],
        })
    }
}

zed::register_extension!(JavaLintExtension);
