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
        Ok(zed::Command {
            command: "java".into(),
            args: vec![
                "-jar".into(),
                "checkstyle-lsp-shim.jar".into(),
                "--checkstyle-jar".into(),
                "checkstyle.jar".into(),
                "--config".into(),
                "checkstyle.xml".into(),
            ],
            env: vec![],
        })
    }
}

zed::register_extension!(JavaLintExtension);
