use std::fmt;

use serde::{Deserialize, Serialize};

pub const DEFAULT_BASE_URL: &str = "https://api.deepseek.com";
pub const MODEL_ID: &str = "deepseek-flash";
pub const MODEL_LABEL: &str = "DeepSeek V4.1 Flash 1017";
pub const MODEL_VERSION: &str = "DeepSeek-V4.1-Flash-1017";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Source {
    pub title: String,
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub site: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub date: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: String,
    #[serde(default)]
    pub content: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub reasoning: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub sources: Vec<Source>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<String>,
    #[serde(default)]
    pub ts: u64,
}

impl Conversation {
    pub fn normalized_kind(&self) -> String {
        normalize_chat_kind(&self.kind)
    }
}

impl Message {
    pub fn user(text: impl Into<String>) -> Self {
        Self {
            role: "user".into(),
            content: text.into(),
            reasoning: String::new(),
            sources: Vec::new(),
            images: Vec::new(),
            ts: now_ms(),
        }
    }

    pub fn assistant(content: impl Into<String>, reasoning: impl Into<String>) -> Self {
        Self {
            role: "assistant".into(),
            content: content.into(),
            reasoning: reasoning.into(),
            sources: Vec::new(),
            images: Vec::new(),
            ts: now_ms(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Conversation {
    pub id: String,
    pub title: String,
    #[serde(default = "default_chat_kind")]
    pub kind: String,
    pub created_at: u64,
    pub updated_at: u64,
    #[serde(default)]
    pub messages: Vec<Message>,
    /// chat.deepseek.com thread id, when the chat was created in web mode.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub web_session_id: String,
    /// Assistant message id to continue the web thread from.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub web_parent_id: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ChatSummary {
    pub id: String,
    pub title: String,
    pub updated_at: u64,
    pub message_count: usize,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Usage {
    #[serde(default)]
    pub prompt_tokens: u64,
    #[serde(default)]
    pub completion_tokens: u64,
    #[serde(default)]
    pub total_tokens: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Balance {
    pub available: bool,
    pub currency: String,
    pub total: String,
    pub granted: String,
    pub topped_up: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct SendStart {
    pub stream_id: u64,
    pub chat: Conversation,
}

#[derive(Debug, Clone, Serialize)]
pub struct Poll {
    pub stream_id: u64,
    pub chat_id: String,
    pub done: bool,
    pub content: String,
    pub reasoning: String,
    pub cancelled: bool,
    pub error: Option<String>,
    pub error_kind: Option<String>,
    pub usage: Option<Usage>,
    pub stage: Option<String>,
    pub sources: Vec<Source>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Io,
    Network,
    Unauthorized,
    InsufficientBalance,
    RateLimited,
    Api,
    NotFound,
    Invalid,
    Cancelled,
}

impl ErrorKind {
    pub fn code(self) -> &'static str {
        match self {
            Self::Io => "io",
            Self::Network => "network",
            Self::Unauthorized => "unauthorized",
            Self::InsufficientBalance => "insufficient_balance",
            Self::RateLimited => "rate_limited",
            Self::Api => "api",
            Self::NotFound => "not_found",
            Self::Invalid => "invalid",
            Self::Cancelled => "cancelled",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Error {
    pub kind: ErrorKind,
    pub message: String,
}

impl Error {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
    }

    pub fn invalid(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Invalid, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::NotFound, message)
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self::new(ErrorKind::Io, format!("Ошибка файловой системы: {e}"))
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Self::new(ErrorKind::Invalid, format!("Повреждённые данные: {e}"))
    }
}

impl From<reqwest::Error> for Error {
    fn from(e: reqwest::Error) -> Self {
        let message = if e.is_timeout() {
            "Сеть не ответила вовремя".to_string()
        } else if e.is_connect() {
            "Нет соединения с api.deepseek.com".to_string()
        } else {
            format!("Сетевая ошибка: {e}")
        };
        Self::new(ErrorKind::Network, message)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AuthMode {
    Api,
    Web,
}

impl AuthMode {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "api" => Some(Self::Api),
            "web" => Some(Self::Web),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Api => "api",
            Self::Web => "web",
        }
    }
}

pub fn default_chat_kind() -> String {
    "chat".to_string()
}

pub fn normalize_chat_kind(kind: &str) -> String {
    match kind {
        "agent" => "agent".to_string(),
        _ => "chat".to_string(),
    }
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub fn truncate_title(text: &str) -> String {
    let clean: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if clean.chars().count() <= 48 {
        return clean;
    }
    let mut out: String = clean.chars().take(47).collect();
    out.push('…');
    out
}
