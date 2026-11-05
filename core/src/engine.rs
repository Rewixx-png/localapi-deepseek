use base64::Engine as _;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use serde::Serialize;

use crate::api::{Api, Delta, WebHit};
use crate::server::{Backend, LocalServer};
use crate::store::Store;
use crate::types::{
    AuthMode, Balance, ChatSummary, Conversation, Error, ErrorKind, Message, Poll,
    truncate_title, SendStart, Source, Usage, DEFAULT_BASE_URL, MODEL_ID, MODEL_LABEL,
    MODEL_VERSION,
};
use crate::web::{self, WebApi, WebDelta, WEB_BASE_URL};

#[derive(Debug, Clone, Serialize)]
pub struct Status {
    pub has_token: bool,
    pub online: bool,
    pub mode: &'static str,
    pub model: &'static str,
    pub model_label: &'static str,
    pub model_version: &'static str,
    pub base_url: String,
    pub models: Vec<String>,
    pub balance: Option<Balance>,
}

struct Inner {
    store: Arc<Store>,
    mode: Option<AuthMode>,
    api: Option<Api>,
    web: Option<WebApi>,
    models: Vec<String>,
    balance: Option<Balance>,
}

struct Stream {
    chat_id: String,
    cancel: AtomicBool,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    content: String,
    reasoning: String,
    done: bool,
    cancelled: bool,
    error: Option<Error>,
    usage: Option<Usage>,
    stage: Option<String>,
    sources: Vec<Source>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

fn decode_data_url(url: &str) -> Option<(Vec<u8>, String, String)> {
    let rest = url.strip_prefix("data:")?;
    let (meta, data) = rest.split_once(',')?;
    if !meta.contains("base64") {
        return None;
    }
    let mime = meta.split(';').next().unwrap_or("image/jpeg").to_string();
    let bytes = base64::engine::general_purpose::STANDARD.decode(data).ok()?;
    let ext = if mime.contains("png") {
        "png"
    } else if mime.contains("webp") {
        "webp"
    } else {
        "jpg"
    };
    Some((bytes, mime, ext.to_string()))
}

fn prepare_web_uploads(
    web: &WebApi,
    images: &[String],
    stream: &Stream,
) -> Result<Vec<String>, Error> {
    if images.is_empty() {
        return Ok(Vec::new());
    }
    set_stage(stream, Some("uploading"));
    let mut file_ids = Vec::new();
    for image in images {
        let (bytes, mime, ext) = decode_data_url(image)
            .ok_or_else(|| Error::new(ErrorKind::Invalid, "Повреждённое изображение"))?;
        let file_id = web.upload_file(bytes, &mime, &format!("image.{ext}"))?;
        if !web.wait_parsed(&file_id) {
            return Err(Error::new(
                ErrorKind::Api,
                "DeepSeek не смог прочитать изображение",
            ));
        }
        file_ids.push(file_id);
    }
    Ok(file_ids)
}

fn set_stage(stream: &Stream, stage: Option<&str>) {
    lock(&stream.state).stage = stage.map(str::to_string);
}

fn search_context(query: &str, hits: &[WebHit]) -> String {
    if hits.is_empty() {
        return format!(
            "Веб-поиск по запросу «{query}» не вернул результатов. Не выдумывай свежие факты; если без них ответить нельзя, скажи об этом."
        );
    }
    let mut out = format!(
        "Ниже результаты веб-поиска DeepSeek по запросу «{query}». Используй их как источник фактов и указывай URL источников. Если результаты противоречат друг другу, отметь это.\n"
    );
    for (index, hit) in hits.iter().enumerate() {
        let summary: String = hit.summary.chars().take(700).collect();
        let content: String = hit.content.chars().take(3500).collect();
        out.push_str(&format!(
            "\n[{}] {}\nURL: {}\nДата: {}\n{}\n{}\n",
            index + 1,
            hit.source.title,
            hit.source.url,
            hit.source.date.as_deref().unwrap_or("—"),
            summary,
            content
        ));
    }
    out
}

fn clean_title(raw: &str) -> String {
    let line = raw
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or("");
    let mut title = line
        .trim_matches(|c: char| {
            c.is_whitespace() || matches!(c, '"' | '\'' | '«' | '»' | '`' | '*' | '#')
        })
        .to_string();
    for prefix in ["Название чата:", "Название:", "Заголовок:", "Title:"] {
        if let Some(rest) = title.strip_prefix(prefix) {
            title = rest.trim().to_string();
        }
    }
    let title = title.trim_matches(|c: char| {
        c.is_whitespace()
            || matches!(c, '"' | '\'' | '«' | '»' | '`' | '*' | '.' | ',' | ':')
    });
    truncate_title(title)
}

fn push_text(stream: &Stream, text: &str, reasoning: bool) -> Result<(), Error> {
    if stream.cancel.load(Ordering::Relaxed) {
        return Err(Error::new(ErrorKind::Cancelled, "Отменено"));
    }
    let mut state = lock(&stream.state);
    if reasoning {
        state.reasoning.push_str(text);
    } else {
        state.content.push_str(text);
    }
    Ok(())
}

fn cleanup_web_sessions(web: WebApi, store: Arc<Store>) {
    let Ok(chats) = store.list() else {
        return;
    };
    for chat in chats {
        let Ok(mut conv) = store.get(&chat.id) else {
            continue;
        };
        if conv.web_session_id.is_empty() {
            continue;
        }
        if web.delete_session(&conv.web_session_id).is_ok() {
            conv.web_session_id.clear();
            conv.web_parent_id = None;
            let _ = store.save(&conv);
        }
    }
}

fn save_answer(
    store: &Store,
    chat_id: &str,
    fallback: &Conversation,
    content: &str,
    reasoning: &str,
    sources: &[Source],
    web_parent_id: Option<i64>,
) -> Result<(), Error> {
    if content.is_empty() && reasoning.is_empty() {
        return Ok(());
    }
    let mut latest = store.get(chat_id).unwrap_or_else(|_| fallback.clone());
    if let Some(parent) = web_parent_id {
        latest.web_parent_id = Some(parent);
    } else {
        latest.web_session_id.clear();
        latest.web_parent_id = None;
    }
    store.append_assistant(&mut latest, content, reasoning, sources)
}

#[derive(Debug, Clone, Serialize)]
pub struct ServerStatus {
    pub running: bool,
    pub port: u16,
    pub base_url: String,
    pub key: String,
}

pub struct Engine {
    base_url: String,
    web_base_url: String,
    inner: Mutex<Inner>,
    streams: Mutex<HashMap<u64, Arc<Stream>>>,
    server: Mutex<Option<LocalServer>>,
    online: AtomicBool,
    next_stream: AtomicU64,
}

impl Engine {
    pub fn open(dir: impl Into<std::path::PathBuf>) -> Result<Self, Error> {
        Self::with_base_urls(dir, DEFAULT_BASE_URL, WEB_BASE_URL)
    }

    /// Tests point both transports at the same mock server.
    pub fn with_base(dir: impl Into<std::path::PathBuf>, base_url: &str) -> Result<Self, Error> {
        Self::with_base_urls(dir, base_url, base_url)
    }

    pub fn with_base_urls(
        dir: impl Into<std::path::PathBuf>,
        api_base_url: &str,
        web_base_url: &str,
    ) -> Result<Self, Error> {
        let store = Arc::new(Store::open(dir)?);
        let store_online = store.load_online();
        let auth = store.load_auth();
        let mode = auth.as_ref().map(|(m, _)| *m);
        let api = match &auth {
            Some((AuthMode::Api, token)) => Some(Api::new(api_base_url, token)?),
            _ => None,
        };
        let web = match &auth {
            Some((AuthMode::Web, token)) => Some(WebApi::new(web_base_url, token)?),
            _ => None,
        };
        if let Some(cleanup_web) = web.clone() {
            let cleanup_store = Arc::clone(&store);
            std::thread::spawn(move || cleanup_web_sessions(cleanup_web, cleanup_store));
        }
        Ok(Self {
            base_url: api_base_url.to_string(),
            web_base_url: web_base_url.to_string(),
            inner: Mutex::new(Inner {
                store,
                mode,
                api,
                web,
                models: Vec::new(),
                balance: None,
            }),
            streams: Mutex::new(HashMap::new()),
            server: Mutex::new(None),
            online: AtomicBool::new(store_online),
            next_stream: AtomicU64::new(1),
        })
    }

    pub fn status(&self) -> Status {
        let inner = lock(&self.inner);
        Status {
            has_token: inner.api.is_some() || inner.web.is_some(),
            online: self.online.load(Ordering::Relaxed),
            mode: inner.mode.unwrap_or(AuthMode::Api).as_str(),
            model: MODEL_ID,
            model_label: MODEL_LABEL,
            model_version: MODEL_VERSION,
            base_url: match inner.mode {
                Some(AuthMode::Web) => self.web_base_url.clone(),
                _ => self.base_url.clone(),
            },
            models: inner.models.clone(),
            balance: inner.balance.clone(),
        }
    }

    /// Verifies the credential, then persists it. Validation happens before the
    /// write, so a bad paste never replaces a working token.
    pub fn set_online(&self, online: bool) -> Result<Status, Error> {
        {
            let inner = lock(&self.inner);
            inner.store.save_online(online)?;
        }
        self.online.store(online, Ordering::Relaxed);
        if !online {
            {
                let streams = lock(&self.streams);
                for stream in streams.values() {
                    stream.cancel.store(true, Ordering::Relaxed);
                }
            }
            self.stop_server();
        }
        Ok(self.status())
    }

    pub fn set_token(&self, token: &str, mode: &str) -> Result<Status, Error> {
        if !self.online.load(Ordering::Relaxed) {
            return Err(Error::new(
                ErrorKind::Network,
                "Доступ к интернету выключен",
            ));
        }
        let mode = AuthMode::parse(mode)
            .ok_or_else(|| Error::new(ErrorKind::Invalid, "Неизвестный режим авторизации"))?;
        let token = token.trim();
        if token.is_empty() {
            return Err(Error::new(ErrorKind::Invalid, "Токен пустой"));
        }

        match mode {
            AuthMode::Api => {
                if token.len() < 8 || token.chars().any(char::is_whitespace) {
                    return Err(Error::new(
                        ErrorKind::Invalid,
                        "Ключ выглядит неполным: вставьте его целиком, без пробелов",
                    ));
                }
                let api = Api::new(&self.base_url, token)?;
                let models = api.validate()?;
                let balance = api.balance();

                let mut inner = lock(&self.inner);
                inner.store.save_auth(mode, token)?;
                inner.mode = Some(mode);
                inner.api = Some(api);
                inner.web = None;
                inner.models = models;
                inner.balance = balance;
                drop(inner);
                self.stop_server();
                Ok(self.status())
            }
            AuthMode::Web => {
                let normalized = web::normalize_token(token);
                let web = WebApi::new(&self.web_base_url, &normalized)?;
                web.validate()?;

                let mut inner = lock(&self.inner);
                inner.store.save_auth(mode, &normalized)?;
                inner.mode = Some(mode);
                inner.web = Some(web);
                inner.api = None;
                inner.models.clear();
                inner.balance = None;
                drop(inner);
                self.stop_server();
                Ok(self.status())
            }
        }
    }

    /// Re-checks the stored token without replacing it.
    pub fn revalidate(&self) -> Result<Status, Error> {
        if !self.online.load(Ordering::Relaxed) {
            return Err(Error::new(
                ErrorKind::Network,
                "Доступ к интернету выключен",
            ));
        }
        let (mode, api, web) = {
            let inner = lock(&self.inner);
            (inner.mode, inner.api.clone(), inner.web.clone())
        };
        match mode {
            Some(AuthMode::Api) => {
                let api = api
                    .ok_or_else(|| Error::new(ErrorKind::Unauthorized, "Токен ещё не добавлен"))?;
                let models = api.validate()?;
                let balance = api.balance();
                let mut inner = lock(&self.inner);
                inner.models = models;
                inner.balance = balance;
            }
            Some(AuthMode::Web) => {
                let web = web
                    .ok_or_else(|| Error::new(ErrorKind::Unauthorized, "Токен ещё не добавлен"))?;
                web.validate()?;
            }
            None => {
                return Err(Error::new(
                    ErrorKind::Unauthorized,
                    "Токен ещё не добавлен",
                ))
            }
        }
        Ok(self.status())
    }

    pub fn clear_token(&self) -> Result<Status, Error> {
        let mut inner = lock(&self.inner);
        inner.store.clear_auth()?;
        inner.mode = None;
        inner.api = None;
        inner.web = None;
        inner.models.clear();
        inner.balance = None;
        drop(inner);
        self.stop_server();
        Ok(self.status())
    }

    pub fn new_chat(&self) -> Result<Conversation, Error> {
        let inner = lock(&self.inner);
        let conv = inner.store.create();
        inner.store.save(&conv)?;
        Ok(conv)
    }

    pub fn list_chats(&self) -> Result<Vec<ChatSummary>, Error> {
        let inner = lock(&self.inner);
        inner.store.list()
    }

    pub fn get_chat(&self, id: &str) -> Result<Conversation, Error> {
        let inner = lock(&self.inner);
        inner.store.get(id)
    }

    pub fn start_server(&self, port: u16) -> Result<ServerStatus, Error> {
        if !self.online.load(Ordering::Relaxed) {
            return Err(Error::new(
                ErrorKind::Network,
                "Доступ к интернету выключен",
            ));
        }
        if lock(&self.server).is_some() {
            return Ok(self.server_status());
        }
        let (mode, api, web) = {
            let inner = lock(&self.inner);
            (inner.mode, inner.api.clone(), inner.web.clone())
        };
        // api/web are clones of the engine's configured clients, so the local
        // server reuses the same reqwest::Client (TLS, DNS, connection pool)
        // as the in-app chat instead of building a new one per request.
        let backend = match mode {
            Some(AuthMode::Api) => Backend::Api(api.ok_or_else(|| {
                Error::new(ErrorKind::Unauthorized, "Сначала добавьте API-ключ DeepSeek")
            })?),
            Some(AuthMode::Web) => Backend::Web(web.ok_or_else(|| {
                Error::new(ErrorKind::Unauthorized, "Сначала добавьте веб-токен DeepSeek")
            })?),
            None => {
                return Err(Error::new(
                    ErrorKind::Unauthorized,
                    "Сначала добавьте токен DeepSeek",
                ))
            }
        };
        let key = {
            let inner = lock(&self.inner);
            inner.store.server_key()?
        };
        let socket_path = { lock(&self.inner).store.root().join("localapi.sock") };
        let server = LocalServer::start(backend, key, port, Some(socket_path))?;
        let status = ServerStatus {
            running: true,
            port: server.port(),
            base_url: format!("http://127.0.0.1:{}/v1", server.port()),
            key: server.key().to_string(),
        };
        *lock(&self.server) = Some(server);
        Ok(status)
    }

    pub fn stop_server(&self) {
        let mut guard = lock(&self.server);
        guard.take();
    }

    pub fn server_status(&self) -> ServerStatus {
        match lock(&self.server).as_ref() {
            Some(server) => ServerStatus {
                running: true,
                port: server.port(),
                base_url: format!("http://127.0.0.1:{}/v1", server.port()),
                key: server.key().to_string(),
            },
            None => ServerStatus {
                running: false,
                port: 0,
                base_url: String::new(),
                key: String::new(),
            },
        }
    }

    pub fn delete_chat(&self, id: &str) -> Result<(), Error> {
        let (session_id, web) = {
            let inner = lock(&self.inner);
            let session_id = inner
                .store
                .get(id)
                .map(|c| c.web_session_id)
                .unwrap_or_default();
            inner.store.delete(id)?;
            (session_id, inner.web.clone())
        };
        if !session_id.is_empty() && self.online.load(Ordering::Relaxed) {
            if let Some(web) = web {
                // Remote cleanup must not block the UI thread.
                std::thread::spawn(move || {
                    let _ = web.delete_session(&session_id);
                });
            }
        }
        Ok(())
    }

    pub fn send(&self, chat_id: &str, text: &str, effort: &str) -> Result<SendStart, Error> {
        self.send_opts(chat_id, text, MODEL_ID, true, false, Vec::new(), effort)
    }

    /// Asks the configured backend for a short human title for a chat. The
    /// title request is deliberately cheap: flash model, no thinking, no
    /// search, and it is not appended to the conversation.
    pub fn generate_title(&self, chat_id: &str, first_text: &str) -> Result<String, Error> {
        if !self.online.load(Ordering::Relaxed) {
            return Err(Error::new(
                ErrorKind::Network,
                "Доступ к интернету выключен",
            ));
        }
        let first_text = first_text.trim();
        let first_text = if first_text.is_empty() {
            "Сообщение с изображением"
        } else {
            first_text
        };
        // A title only needs the topic; do not resend a huge first prompt.
        let title_source: String = first_text.chars().take(600).collect();
        let (mode, api, web, store) = {
            let inner = lock(&self.inner);
            let mode = inner.mode.ok_or_else(|| {
                Error::new(ErrorKind::Unauthorized, "Сначала добавьте токен DeepSeek")
            })?;
            if inner.api.is_none() && inner.web.is_none() {
                return Err(Error::new(
                    ErrorKind::Unauthorized,
                    "Сначала добавьте токен DeepSeek",
                ));
            }
            (
                mode,
                inner.api.clone(),
                inner.web.clone(),
                Arc::clone(&inner.store),
            )
        };

        let instruction = "Придумай короткое название для чата по первому сообщению \
пользователя. Ответь только названием: 2–5 слов, без кавычек, точки и пояснений.";
        let mut raw = String::new();
        match mode {
            AuthMode::Api => {
                let api = api.ok_or_else(|| {
                    Error::new(ErrorKind::Unauthorized, "Сначала добавьте токен DeepSeek")
                })?;
                let history = vec![
                    Message {
                        role: "system".into(),
                        content: instruction.to_string(),
                        reasoning: String::new(),
                        sources: Vec::new(),
                        images: Vec::new(),
                        ts: 0,
                    },
                    Message::user(title_source.clone()),
                ];
                let cancel = AtomicBool::new(false);
                api.stream_chat(
                    &history,
                    MODEL_ID,
                    false,
                    "low",
                    &cancel,
                    &mut |delta| {
                        if let Delta::Content(text) = delta {
                            raw.push_str(&text);
                        }
                        Ok(())
                    },
                )?;
            }
            AuthMode::Web => {
                let web = web.ok_or_else(|| {
                    Error::new(ErrorKind::Unauthorized, "Сначала добавьте веб-токен DeepSeek")
                })?;
                let prompt = format!("{instruction}\n\nПервое сообщение пользователя:\n{title_source}");
                let session = web.create_session()?;
                let cancel = AtomicBool::new(false);
                let result = web.stream_completion(
                    &session,
                    None,
                    &prompt,
                    Some("default"),
                    false,
                    false,
                    &[],
                    &cancel,
                    &mut |delta| {
                        if let WebDelta::Answer(text) = delta {
                            raw.push_str(&text);
                        }
                        Ok(())
                    },
                );
                let _ = web.delete_session(&session);
                result?;
            }
        }

        let title = clean_title(&raw);
        if title.is_empty() {
            return Err(Error::new(ErrorKind::Api, "Модель не вернула название чата"));
        }
        let mut conv = store.get(chat_id)?;
        // Never overwrite a title that is no longer the automatic first-message
        // fallback (for example if another title request already finished).
        if conv.title.is_empty() || conv.title == truncate_title(first_text) {
            conv.title = title.clone();
            conv.updated_at = crate::types::now_ms();
            store.save(&conv)?;
        }
        Ok(title)
    }

    pub fn send_opts(
        &self,
        chat_id: &str,
        text: &str,
        model: &str,
        thinking: bool,
        search: bool,
        images: Vec<String>,
        effort: &str,
    ) -> Result<SendStart, Error> {
        self.send_opts_with_system(
            chat_id, text, model, thinking, search, images, effort, None,
        )
    }

    /// Like [`Engine::send_opts`], but prepends an optional per-request system
    /// instruction. It is only sent upstream and never written to the chat.
    #[allow(clippy::too_many_arguments)]
    pub fn send_opts_with_system(
        &self,
        chat_id: &str,
        text: &str,
        model: &str,
        thinking: bool,
        search: bool,
        images: Vec<String>,
        effort: &str,
        system_prompt: Option<&str>,
    ) -> Result<SendStart, Error> {
        if !self.online.load(Ordering::Relaxed) {
            return Err(Error::new(
                ErrorKind::Network,
                "Доступ к интернету выключен",
            ));
        }
        let text = text.trim();
        if text.is_empty() && images.is_empty() {
            return Err(Error::new(ErrorKind::Invalid, "Сообщение пустое"));
        }
        let model = match model {
            "deepseek-v4-pro" => "deepseek-v4-pro",
            _ => MODEL_ID,
        }
        .to_string();
        let effort = match effort {
            "low" | "high" | "max" => effort,
            _ => "high",
        }
        .to_string();

        let (mode, api, web, store) = {
            let inner = lock(&self.inner);
            let mode = inner.mode.ok_or_else(|| {
                Error::new(ErrorKind::Unauthorized, "Сначала добавьте токен DeepSeek")
            })?;
            if inner.api.is_none() && inner.web.is_none() {
                return Err(Error::new(
                    ErrorKind::Unauthorized,
                    "Сначала добавьте токен DeepSeek",
                ));
            }
            (
                mode,
                inner.api.clone(),
                inner.web.clone(),
                Arc::clone(&inner.store),
            )
        };

        if mode == AuthMode::Api && !images.is_empty() && model == "deepseek-v4-pro" {
            return Err(Error::new(
                ErrorKind::Invalid,
                "V4 Pro не поддерживает изображения. Выберите Flash 1017",
            ));
        }

        {
            let streams = lock(&self.streams);
            if streams
                .values()
                .any(|s| s.chat_id == chat_id && !lock(&s.state).done)
            {
                return Err(Error::new(ErrorKind::Invalid, "В этом чате ещё идёт ответ"));
            }
        }

        let mut conv = if chat_id.is_empty() { store.create() } else { store.get(chat_id)? };
        store.append_user(&mut conv, text, &images)?;
        let mut history = conv.messages.clone();
        if let Some(system) = system_prompt.map(str::trim).filter(|s| !s.is_empty()) {
            history.insert(
                0,
                Message {
                    role: "system".to_string(),
                    content: system.to_string(),
                    reasoning: String::new(),
                    sources: Vec::new(),
                    images: Vec::new(),
                    ts: 0,
                },
            );
        }
        let prompt = text.to_string();
        let fallback = conv.clone();

        let stream_id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        let stream = Arc::new(Stream {
            chat_id: conv.id.clone(),
            cancel: AtomicBool::new(false),
            state: Mutex::new(State::default()),
        });
        lock(&self.streams).insert(stream_id, Arc::clone(&stream));

        let worker_stream = Arc::clone(&stream);
        let worker_store = Arc::clone(&store);
        let worker_chat = conv.id.clone();
        std::thread::Builder::new()
            .name(format!("deepseek-stream-{stream_id}"))
            .spawn(move || {
                let result: Result<Option<Usage>, Error> = match mode {
                    AuthMode::Api => match api {
                        Some(api) => {
                            let mut api_history = history.clone();
                            let searched = if search && !prompt.trim().is_empty() {
                                set_stage(&worker_stream, Some("searching"));
                                api.search(&prompt)
                            } else {
                                Ok(Vec::new())
                            };
                            match searched {
                                Ok(hits) => {
                                    if search {
                                        lock(&worker_stream.state).sources =
                                            hits.iter().map(|hit| hit.source.clone()).collect();
                                        api_history.insert(
                                            0,
                                            Message {
                                                role: "system".to_string(),
                                                content: search_context(&prompt, &hits),
                                                reasoning: String::new(),
                                                sources: Vec::new(),
                                                images: Vec::new(),
                                                ts: 0,
                                            },
                                        );
                                    }
                                    set_stage(&worker_stream, Some("writing"));
                                    let mut on_delta = |delta: Delta| match delta {
                                        Delta::Content(s) => push_text(&worker_stream, &s, false),
                                        Delta::Reasoning(s) => {
                                            push_text(&worker_stream, &s, true)
                                        }
                                        Delta::Usage(u) => {
                                            lock(&worker_stream.state).usage = Some(u);
                                            Ok(())
                                        }
                                    };
                                    api.stream_chat(
                                        &api_history,
                                        &model,
                                        thinking,
                                        &effort,
                                        &worker_stream.cancel,
                                        &mut on_delta,
                                    )
                                }
                                Err(e) => Err(e),
                            }
                        }
                        None => Err(Error::new(
                            ErrorKind::Unauthorized,
                            "Сначала добавьте токен DeepSeek",
                        )),
                    },
                    AuthMode::Web => match web {
                        Some(web) => {
                            let uploads = prepare_web_uploads(
                                &web,
                                &images,
                                &worker_stream,
                            );
                            match uploads {
                                Ok(ref_file_ids) => {
                                    // Web sessions are ephemeral: the official
                                    // DeepSeek sidebar must not keep our chats.
                                    // The transcript is flattened into the prompt
                                    // instead of relying on a server-side session.
                                    if let Ok(old) = worker_store.get(&worker_chat) {
                                        if !old.web_session_id.is_empty() {
                                            let _ = web.delete_session(&old.web_session_id);
                                        }
                                    }
                                    match web.create_session() {
                                        Ok(session_id) => {
                                            let mut started = false;
                                            let mut on_delta = |delta: WebDelta| {
                                                if !started {
                                                    started = true;
                                                    set_stage(&worker_stream, Some("writing"));
                                                }
                                                match delta {
                                                    WebDelta::Reasoning(s) => {
                                                        push_text(&worker_stream, &s, true)
                                                    }
                                                    WebDelta::Answer(s) => {
                                                        push_text(&worker_stream, &s, false)
                                                    }
                                                }
                                            };
                                            set_stage(
                                                &worker_stream,
                                                Some(if search { "searching" } else { "writing" }),
                                            );
                                            let transcript = web::prompt(&history);
                                            let result = web.stream_completion(
                                                &session_id,
                                                None,
                                                &transcript,
                                                Some("default"),
                                                thinking,
                                                search,
                                                &ref_file_ids,
                                                &worker_stream.cancel,
                                                &mut on_delta,
                                            );
                                            let _ = web.delete_session(&session_id);
                                            result.map(|_| None)
                                        }
                                        Err(e) => Err(e),
                                    }
                                }
                                Err(e) => Err(e),
                            }
                        }
                        None => Err(Error::new(
                            ErrorKind::Unauthorized,
                            "Сначала добавьте веб-токен DeepSeek",
                        )),
                    },
                };

                let (content, reasoning, sources) = {
                    let st = lock(&worker_stream.state);
                    (st.content.clone(), st.reasoning.clone(), st.sources.clone())
                };

                let save_result = match &result {
                    Ok(_) => save_answer(
                        &worker_store,
                        &worker_chat,
                        &fallback,
                        &content,
                        &reasoning,
                        &sources,
                        None,
                    ),
                    Err(e) if e.kind == ErrorKind::Cancelled => save_answer(
                        &worker_store,
                        &worker_chat,
                        &fallback,
                        &content,
                        &reasoning,
                        &sources,
                        None,
                    ),
                    Err(_) => Ok(()),
                };

                let mut st = lock(&worker_stream.state);
                st.done = true;
                st.stage = None;
                match result {
                    Ok(usage) => st.usage = usage.or(st.usage.take()),
                    Err(e) if e.kind == ErrorKind::Cancelled => st.cancelled = true,
                    Err(e) => st.error = Some(e),
                }
                if let Err(e) = save_result {
                    st.error = Some(Error::new(
                        ErrorKind::Io,
                        format!("Не удалось сохранить ответ: {e}"),
                    ));
                }
            })
            .map_err(|e| {
                Error::new(ErrorKind::Io, format!("Не удалось запустить генерацию: {e}"))
            })?;

        Ok(SendStart { stream_id, chat: conv })
    }

    pub fn poll(&self, stream_id: u64) -> Result<Poll, Error> {
        let stream = {
            let streams = lock(&self.streams);
            streams
                .get(&stream_id)
                .cloned()
                .ok_or_else(|| Error::not_found("Поток не найден"))?
        };

        let st = lock(&stream.state);
        let poll = Poll {
            stream_id,
            chat_id: stream.chat_id.clone(),
            done: st.done,
            content: st.content.clone(),
            reasoning: st.reasoning.clone(),
            cancelled: st.cancelled,
            error: st.error.as_ref().map(|e| e.message.clone()),
            error_kind: st.error.as_ref().map(|e| e.kind.code().to_string()),
            usage: st.usage.clone(),
            stage: st.stage.clone(),
            sources: st.sources.clone(),
        };
        let done = st.done;
        drop(st);

        if done {
            let mut streams = lock(&self.streams);
            let finished: Vec<u64> = streams
                .iter()
                .filter(|(id, s)| **id != stream_id && lock(&s.state).done)
                .map(|(id, _)| *id)
                .collect();
            for id in finished.into_iter().rev().skip(8) {
                streams.remove(&id);
            }
        }
        Ok(poll)
    }

    pub fn cancel(&self, stream_id: u64) -> Result<(), Error> {
        let streams = lock(&self.streams);
        let stream = streams
            .get(&stream_id)
            .ok_or_else(|| Error::not_found("Поток не найден"))?;
        stream.cancel.store(true, Ordering::Relaxed);
        Ok(())
    }

}
