use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use crate::types::{
    normalize_chat_kind, now_ms, truncate_title, AuthMode, ChatSummary, Conversation, Error,
    ErrorKind, Message,
};

/// File-backed store: one JSON document per conversation, atomic writes.
/// Sized for mobile usage (hundreds of chats); no database, nothing to corrupt.
pub struct Store {
    root: PathBuf,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct AuthFile {
    mode: AuthMode,
    token: String,
}

static TMP_COUNTER: AtomicU64 = AtomicU64::new(0);

impl Store {
    pub fn open(root: impl Into<PathBuf>) -> Result<Self, Error> {
        let root = root.into();
        fs::create_dir_all(root.join("conversations"))?;
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn conv_dir(&self) -> PathBuf {
        self.root.join("conversations")
    }

    fn conv_path(&self, id: &str) -> Result<PathBuf, Error> {
        if !valid_id(id) {
            return Err(Error::new(ErrorKind::Invalid, "Некорректный идентификатор чата"));
        }
        Ok(self.conv_dir().join(format!("{id}.json")))
    }

    pub fn save_auth(&self, mode: AuthMode, token: &str) -> Result<(), Error> {
        let path = self.root.join("auth.json");
        let raw = serde_json::to_vec(&AuthFile { mode, token: token.to_string() })?;
        atomic_write(&path, &raw)?;
        restrict(&path)?;
        // Migrate away from the pre-web single-token file.
        let _ = fs::remove_file(self.root.join("token"));
        Ok(())
    }

    pub fn load_auth(&self) -> Option<(AuthMode, String)> {
        if let Ok(raw) = fs::read_to_string(self.root.join("auth.json")) {
            if let Ok(auth) = serde_json::from_str::<AuthFile>(&raw) {
                let token = auth.token.trim().to_string();
                if !token.is_empty() {
                    return Some((auth.mode, token));
                }
            }
        }
        let raw = fs::read_to_string(self.root.join("token")).ok()?;
        let token = raw.trim().to_string();
        if token.is_empty() {
            None
        } else {
            Some((AuthMode::Api, token))
        }
    }

    pub fn load_online(&self) -> bool {
        match fs::read_to_string(self.root.join("online")) {
            Ok(raw) => raw.trim() != "0",
            Err(_) => true,
        }
    }

    pub fn save_online(&self, online: bool) -> Result<(), Error> {
        atomic_write(&self.root.join("online"), if online { b"1" } else { b"0" })?;
        Ok(())
    }

    /// Stable bearer key for the local OpenAI-compatible server.
    pub fn server_key(&self) -> Result<String, Error> {
        let path = self.root.join("server_key");
        if let Ok(raw) = fs::read_to_string(&path) {
            let key = raw.trim();
            if key.len() >= 16 && key.chars().all(|c| c.is_ascii_hexdigit()) {
                return Ok(key.to_string());
            }
        }
        let key = random_hex(32);
        atomic_write(&path, key.as_bytes())?;
        restrict(&path)?;
        Ok(key)
    }

    pub fn clear_auth(&self) -> Result<(), Error> {
        for name in ["auth.json", "token"] {
            match fs::remove_file(self.root.join(name)) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
            }
        }
        Ok(())
    }

    pub fn create(&self) -> Conversation {
        self.create_kind("chat")
    }

    pub fn create_kind(&self, kind: &str) -> Conversation {
        let ts = now_ms();
        Conversation {
            id: new_id(ts),
            title: String::new(),
            kind: normalize_chat_kind(kind),
            created_at: ts,
            updated_at: ts,
            messages: Vec::new(),
            web_session_id: String::new(),
            web_parent_id: None,
        }
    }

    pub fn list(&self) -> Result<Vec<ChatSummary>, Error> {
        self.list_kind("chat")
    }

    pub fn list_kind(&self, kind: &str) -> Result<Vec<ChatSummary>, Error> {
        let mut out = Vec::new();
        let Ok(entries) = fs::read_dir(self.conv_dir()) else {
            return Ok(out);
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let Ok(raw) = fs::read_to_string(&path) else { continue };
            let Ok(conv) = serde_json::from_str::<Conversation>(&raw) else { continue };
            if conv.kind != normalize_chat_kind(kind) {
                continue;
            }
            // Agent chats own a workspace, so an empty one must stay visible:
            // otherwise a ZIP imported before the first message becomes orphaned.
            if conv.messages.is_empty() && conv.normalized_kind() != "agent" {
                continue;
            }
            out.push(ChatSummary {
                id: conv.id,
                title: if conv.title.is_empty() {
                    conv.messages
                        .iter()
                        .find(|m| m.role == "user")
                        .map(|m| truncate_title(&m.content))
                        .unwrap_or_else(|| "Новый чат".to_string())
                } else {
                    conv.title
                },
                updated_at: conv.updated_at,
                message_count: conv.messages.len(),
            });
        }
        out.sort_by(|a, b| b.updated_at.cmp(&a.updated_at).then_with(|| b.id.cmp(&a.id)));
        Ok(out)
    }

    pub fn get(&self, id: &str) -> Result<Conversation, Error> {
        let path = self.conv_path(id)?;
        let raw = fs::read_to_string(&path)
            .map_err(|_| Error::not_found("Чат не найден — возможно, он был удалён"))?;
        Ok(serde_json::from_str(&raw)?)
    }

    pub fn save(&self, conv: &Conversation) -> Result<(), Error> {
        let path = self.conv_path(&conv.id)?;
        let raw = serde_json::to_vec(conv)?;
        atomic_write(&path, &raw)
    }

    pub fn delete(&self, id: &str) -> Result<(), Error> {
        let path = self.conv_path(id)?;
        match fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.into()),
        }
    }

    pub fn append_user(
        &self,
        conv: &mut Conversation,
        text: &str,
        images: &[String],
    ) -> Result<(), Error> {
        let mut message = Message::user(text);
        message.images = images.to_vec();
        conv.messages.push(message);
        if conv.title.is_empty() {
            conv.title = truncate_title(text);
        }
        conv.updated_at = now_ms();
        self.save(conv)
    }

    pub fn append_assistant(
        &self,
        conv: &mut Conversation,
        content: &str,
        reasoning: &str,
        sources: &[crate::types::Source],
    ) -> Result<(), Error> {
        let mut message = Message::assistant(content, reasoning);
        message.sources = sources.to_vec();
        conv.messages.push(message);
        conv.updated_at = now_ms();
        self.save(conv)
    }
}

fn random_hex(len: usize) -> String {
    let mut state = now_ms()
        ^ ((std::process::id() as u64) << 32)
        ^ TMP_COUNTER.fetch_add(1, Ordering::Relaxed).wrapping_mul(0x9e37_79b9_7f4a_7c15);
    let mut out = String::with_capacity(len + 16);
    while out.len() < len {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        out.push_str(&format!("{state:016x}"));
    }
    out.truncate(len);
    out
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id.chars().all(|c| c.is_ascii_hexdigit() || c == '-')
}

fn new_id(ts: u64) -> String {
    let n = TMP_COUNTER.fetch_add(1, Ordering::Relaxed) as u32;
    format!("{ts:x}-{:x}-{n:x}", std::process::id() as u32 & 0xffff_0000 | (ts as u32 & 0xffff))
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), Error> {
    let dir = path.parent().ok_or_else(|| Error::new(ErrorKind::Io, "Нет родительской папки"))?;
    fs::create_dir_all(dir)?;
    let n = TMP_COUNTER.fetch_add(1, Ordering::Relaxed);
    let tmp = dir.join(format!(".tmp-{}-{n}", std::process::id()));
    {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    #[cfg(unix)]
    {
        // Directory fsync makes the rename durable across power loss.
        if let Ok(dir_file) = fs::File::open(dir) {
            let _ = dir_file.sync_all();
        }
    }
    Ok(())
}

#[cfg(unix)]
fn restrict(path: &Path) -> Result<(), Error> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(not(unix))]
fn restrict(_path: &Path) -> Result<(), Error> {
    Ok(())
}
