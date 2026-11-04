//! Unofficial chat.deepseek.com client (signed-in web session).
//!
//! Flow: validate `GET /api/v0/users/settings`, create a server-side session,
//! solve the `x-ds-pow-response` challenge, then stream
//! `POST /api/v0/chat/completion`. The web backend keeps conversation history,
//! so each turn sends only the new prompt plus `parent_message_id`.

use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use base64::Engine;
use reqwest::blocking::multipart::{Form, Part};
use reqwest::blocking::{Client, Response};
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE, ORIGIN, REFERER};
use reqwest::StatusCode;
use serde_json::{json, Value};

use crate::pow;
use crate::types::{Error, ErrorKind, Message};

pub const WEB_BASE_URL: &str = "https://chat.deepseek.com";
pub const DEFAULT_TARGET_PATH: &str = "/api/v0/chat/completion";
const APP_VERSION: &str = "2.4.0";
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 \
                          (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

#[derive(Debug, Clone)]
pub enum WebDelta {
    Reasoning(String),
    Answer(String),
}

/// The browser stores `{"value":"<token>","__version":"0"}` in localStorage.
/// Accept the raw token, the JSON wrapper, or an `Authorization: Bearer …` line.
pub fn normalize_token(raw: &str) -> String {
    let mut token = raw.trim();
    if let Some(rest) = token.strip_prefix("Bearer ").or_else(|| token.strip_prefix("bearer ")) {
        token = rest.trim();
    }
    if token.starts_with('{') {
        if let Ok(value) = serde_json::from_str::<Value>(token) {
            if let Some(inner) = value.get("value").and_then(Value::as_str) {
                return inner.trim().to_string();
            }
        }
    }
    token.to_string()
}

/// Flatten a local transcript into one web-chat prompt. Used when a web
/// session is ephemeral: create, ask, delete, so nothing remains in the
/// official DeepSeek sidebar.
pub fn prompt(messages: &[Message]) -> String {
    if messages.len() == 1 {
        return messages[0].content.clone();
    }
    let mut out = String::new();
    for message in messages {
        let label = match message.role.as_str() {
            "system" => "System",
            "assistant" => "Assistant",
            _ => "User",
        };
        out.push_str(label);
        out.push_str(": ");
        out.push_str(&message.content);
        out.push_str("\n\n");
    }
    out.push_str("Assistant:");
    out
}

#[derive(Clone)]
pub struct WebApi {
    client: Client,
    base: String,
}

impl WebApi {
    pub fn new(base: &str, raw_token: &str) -> Result<Self, Error> {
        let token = normalize_token(raw_token);
        if token.len() < 16 || token.chars().any(char::is_whitespace) {
            return Err(Error::new(
                ErrorKind::Invalid,
                "Веб-токен выглядит неполным: скопируйте значение userToken целиком",
            ));
        }
        let mut headers = HeaderMap::new();
        headers.insert(ACCEPT, HeaderValue::from_static("*/*"));
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_str(&format!("Bearer {token}"))
                .map_err(|_| Error::new(ErrorKind::Invalid, "Недопустимые символы в токене"))?,
        );
        headers.insert(ORIGIN, HeaderValue::from_static(WEB_BASE_URL));
        headers.insert(REFERER, HeaderValue::from_static("https://chat.deepseek.com/a/chat/"));
        headers.insert("x-client-platform", HeaderValue::from_static("web"));
        headers.insert("x-client-version", HeaderValue::from_static(APP_VERSION));
        headers.insert("x-app-version", HeaderValue::from_static(APP_VERSION));
        headers.insert("x-client-bundle-id", HeaderValue::from_static("com.deepseek.chat"));
        headers.insert("x-client-locale", HeaderValue::from_static("en_US"));

        let client = Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(900))
            .user_agent(USER_AGENT)
            .default_headers(headers)
            .build()?;

        Ok(Self { client, base: base.trim_end_matches('/').to_string() })
    }

    /// The lightest authenticated endpoint: `GET /api/v0/users/settings`.
    pub fn validate(&self) -> Result<(), Error> {
        let resp = self.client.get(self.url("/api/v0/users/settings")).send()?;
        read_envelope(resp).map(|_| ())
    }

    pub fn create_session(&self) -> Result<String, Error> {
        let resp = self
            .client
            .post(self.url("/api/v0/chat_session/create"))
            .json(&json!({}))
            .send()?;
        let body = read_envelope(resp)?;
        body.pointer("/data/biz_data/chat_session/id")
            .or_else(|| body.pointer("/data/biz_data/id"))
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or_else(|| Error::new(ErrorKind::Api, "DeepSeek не вернул id веб-сессии"))
    }

    /// Delete a web session. The web app sends an array here; using the
    /// singular key happened to return code 0 too, but the array is the
    /// contract. Returns an error so cleanup can retry next launch.
    pub fn delete_session(&self, session_id: &str) -> Result<(), Error> {
        let resp = self
            .client
            .post(self.url("/api/v0/chat_session/delete"))
            .json(&json!({ "chat_session_ids": [session_id] }))
            .send()?;
        read_envelope(resp).map(|_| ())
    }

    /// Streams one answer. Returns the assistant `message_id` used as the
    /// `parent_message_id` of the next turn.
    pub fn stream_completion(
        &self,
        session_id: &str,
        parent_id: Option<i64>,
        prompt: &str,
        model_type: Option<&str>,
        thinking: bool,
        search: bool,
        ref_file_ids: &[String],
        cancel: &AtomicBool,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<Option<i64>, Error> {
        let pow_header = self.pow_header(DEFAULT_TARGET_PATH)?;

        let mut body = json!({
            "chat_session_id": session_id,
            "parent_message_id": parent_id,
            "prompt": prompt,
            "ref_file_ids": ref_file_ids,
            "thinking_enabled": thinking,
            "search_enabled": search,
            "action": null,
            "preempt": false,
        });
        if parent_id.is_none() {
            body["model_type"] = json!(model_type.unwrap_or("default"));
        }

        let resp = self
            .client
            .post(self.url("/api/v0/chat/completion"))
            .header("x-ds-pow-response", pow_header)
            .json(&body)
            .send()?;

        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().unwrap_or_default();
            return Err(match status {
                StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => unauthorized(),
                _ => envelope_error(&text),
            });
        }

        let is_sse = resp
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.contains("text/event-stream"))
            .unwrap_or(false);
        if !is_sse {
            let text = resp.text().unwrap_or_default();
            return Err(envelope_error(&text));
        }

        // BufReader::read_line keeps an unfinished `data:` line in its buffer
        // when TCP splits it between chunks. Do not parse per-chunk bytes here.
        let mut reader = BufReader::new(resp);
        let mut line = String::new();
        let mut event = String::new();
        let mut stream = StreamParser::default();

        loop {
            line.clear();
            if reader.read_line(&mut line)? == 0 {
                break;
            }
            if cancel.load(Ordering::Relaxed) {
                return Err(Error::new(ErrorKind::Cancelled, "Запрос отменён"));
            }
            let trimmed = line.trim_end_matches(['\r', '\n']);
            if trimmed.is_empty() {
                continue;
            }
            if let Some(name) = trimmed.strip_prefix("event:") {
                event = name.trim().to_string();
                if event == "finish" || event == "close" {
                    break;
                }
                continue;
            }
            let Some(payload) = trimmed.strip_prefix("data:") else { continue };
            let payload = payload.trim();
            if payload.is_empty() {
                continue;
            }
            if payload == "[DONE]" {
                break;
            }
            if stream.apply(payload, &event, on_delta)? {
                break;
            }
        }

        if stream.filtered {
            return Err(Error::new(
                ErrorKind::Api,
                "DeepSeek заблокировал ответ фильтром. Переформулируйте запрос",
            ));
        }
        Ok(stream.message_id)
    }

    /// Uploads one image for a web attachment turn and waits for parsing.
    pub fn upload_file(
        &self,
        bytes: Vec<u8>,
        mime: &str,
        filename: &str,
    ) -> Result<String, Error> {
        let size = bytes.len();
        let pow = self.pow_header("/api/v0/file/upload_file")?;
        let part = Part::bytes(bytes)
            .file_name(filename.to_string())
            .mime_str(mime)
            .map_err(|_| Error::new(ErrorKind::Invalid, "Некорректный тип изображения"))?;
        let form = Form::new().part("file", part);
        let resp = self
            .client
            .post(self.url("/api/v0/file/upload_file"))
            .header("x-ds-pow-response", pow)
            .header("x-thinking-enabled", "0")
            .header("x-model-type", "default")
            .header("x-file-size", size.to_string())
            .multipart(form)
            .send()?;
        let body = read_envelope(resp)?;
        body.pointer("/data/biz_data/id")
            .or_else(|| body.pointer("/data/biz_data/file/id"))
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or_else(|| Error::new(ErrorKind::Api, "DeepSeek не вернул id загруженного файла"))
    }

    pub fn wait_parsed(&self, file_id: &str) -> bool {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            let status = self.file_status(file_id);
            match status.as_deref() {
                Some("SUCCESS") => return true,
                Some("FAILED") | Some("CONTENT_FILTER") | Some("CONTENT_TOO_LONG")
                | Some("CANCELLED") | Some("CONTENT_EMPTY") | Some("_CUSTOM_SYSTEM_ERROR_FAIL") => {
                    return false
                }
                _ => {}
            }
            if Instant::now() >= deadline {
                return true;
            }
            std::thread::sleep(Duration::from_millis(400));
        }
    }

    fn file_status(&self, file_id: &str) -> Option<String> {
        let resp = self
            .client
            .get(self.url(&format!(
                "/api/v0/file/fetch_files?file_ids={file_id}"
            )))
            .send()
            .ok()?;
        let body = read_envelope(resp).ok()?;
        body.pointer("/data/biz_data/files")
            .and_then(Value::as_array)
            .and_then(|files| {
                files
                    .iter()
                    .find(|f| f.get("id").and_then(Value::as_str) == Some(file_id))
                    .or_else(|| files.first())
            })
            .and_then(|f| f.get("status").and_then(Value::as_str))
            .map(|s| s.to_ascii_uppercase())
    }

    fn pow_header(&self, target_path: &str) -> Result<String, Error> {
        let resp = self
            .client
            .post(self.url("/api/v0/chat/create_pow_challenge"))
            .json(&json!({ "target_path": target_path }))
            .send()?;
        let body = read_envelope(resp)?;
        let challenge = body
            .pointer("/data/biz_data/challenge")
            .ok_or_else(|| Error::new(ErrorKind::Api, "DeepSeek не вернул PoW-задачу"))?;

        let algorithm = challenge
            .get("algorithm")
            .and_then(Value::as_str)
            .unwrap_or("DeepSeekHashV1");
        let challenge_value = challenge
            .get("challenge")
            .and_then(Value::as_str)
            .ok_or_else(|| Error::new(ErrorKind::Api, "Повреждённая PoW-задача: нет challenge"))?;
        let salt = challenge
            .get("salt")
            .and_then(Value::as_str)
            .ok_or_else(|| Error::new(ErrorKind::Api, "Повреждённая PoW-задача: нет salt"))?;
        let expire_at = challenge
            .get("expire_at")
            .and_then(Value::as_f64)
            .ok_or_else(|| Error::new(ErrorKind::Api, "Повреждённая PoW-задача: нет expire_at"))?;
        let difficulty = challenge
            .get("difficulty")
            .and_then(Value::as_f64)
            .ok_or_else(|| Error::new(ErrorKind::Api, "Повреждённая PoW-задача: нет difficulty"))?;
        let signature = challenge
            .get("signature")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let target = challenge
            .get("target_path")
            .and_then(Value::as_str)
            .unwrap_or(target_path);

        let answer = pow::solve(
            challenge_value,
            salt,
            expire_at as i64,
            difficulty.max(1.0) as u64,
        )
        .ok_or_else(|| {
            Error::new(
                ErrorKind::Api,
                "Не удалось решить PoW-задачу DeepSeek (возможно, она устарела)",
            )
        })?;

        let payload = json!({
            "algorithm": algorithm,
            "challenge": challenge_value,
            "salt": salt,
            "answer": answer,
            "signature": signature,
            "target_path": target,
        });
        Ok(base64::engine::general_purpose::STANDARD.encode(payload.to_string()))
    }

    fn url(&self, path: &str) -> String {
        format!("{}{}", self.base, path)
    }

}

#[derive(Default)]
struct StreamParser {
    types: Vec<String>,
    active: Option<usize>,
    // Content already sent for each fragment. Prefix-safe: a snapshot that
    // replaces a fragment with different text must still emit the whole new
    // text instead of skipping its first bytes at a stale offset.
    emitted_reasoning: Vec<String>,
    emitted_answer: Vec<String>,
    message_id: Option<i64>,
    filtered: bool,
}

impl StreamParser {
    /// Returns `true` when the stream signalled completion.
    fn apply(
        &mut self,
        payload: &str,
        event: &str,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<bool, Error> {
        let value: Value = match serde_json::from_str(payload) {
            Ok(v) => v,
            Err(_) => return Ok(false),
        };

        if event == "ready" {
            if let Some(id) = value.get("response_message_id").and_then(Value::as_i64) {
                self.message_id = Some(id);
            }
            return Ok(false);
        }

        // Snapshot: the complete response object.
        if let Some(snapshot) = value.get("v").filter(|v| v.get("response").is_some()) {
            self.snapshot(snapshot, on_delta)?;
            return Ok(false);
        }

        // Path patch: {"p":"…","o":"APPEND|SET","v":…}. This must run before the
        // batch branch, because `response/fragments` APPEND carries an array as
        // `v` and would otherwise be mistaken for a list of patches.
        if let Some(path) = value.get("p").and_then(Value::as_str) {
            let op = value.get("o").and_then(Value::as_str).unwrap_or("SET");
            self.patch(path, op, value.get("v").unwrap_or(&Value::Null), on_delta)?;
            return Ok(false);
        }

        // Terminal batch: {"v":[{p,o,v}, …]}.
        if let Some(Value::Array(patches)) = value.get("v") {
            for patch in patches {
                if let Some(p) = patch.get("p").and_then(Value::as_str) {
                    let op = patch.get("o").and_then(Value::as_str).unwrap_or("SET");
                    let inner = patch.get("v").unwrap_or(&Value::Null);
                    self.patch(p, op, inner, on_delta)?;
                }
            }
            return Ok(false);
        }

        // Bare token append: {"v":"…"}.
        if let Some(text) = value.get("v").and_then(Value::as_str) {
            self.append(text, on_delta)?;
        }
        Ok(false)
    }

    fn snapshot(
        &mut self,
        snapshot: &Value,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<(), Error> {
        if let Some(id) = snapshot
            .get("message_id")
            .or_else(|| snapshot.get("id"))
            .and_then(Value::as_i64)
        {
            self.message_id = Some(id);
        }
        let Some(fragments) = snapshot
            .get("response")
            .and_then(|r| r.get("fragments"))
            .and_then(Value::as_array)
        else {
            return Ok(());
        };

        self.types = fragments
            .iter()
            .map(|f| f.get("type").and_then(Value::as_str).unwrap_or("").to_string())
            .collect();
        self.emitted_reasoning
            .resize_with(self.types.len(), String::new);
        self.emitted_answer.resize_with(self.types.len(), String::new);

        // A snapshot replaces the fragment list, but it must not forget which
        // fragment is currently receiving tokens: DeepSeek sends snapshots
        // between THINK and RESPONSE, and a bare `{"v":"…"}` delta right after
        // one would otherwise be dropped.
        for (idx, fragment) in fragments.iter().enumerate() {
            self.claim(idx, fragment, on_delta)?;
            if matches!(self.kind(idx), Some("RESPONSE") | Some("TEMPLATE_RESPONSE")) {
                self.active = Some(idx);
            }
        }
        Ok(())
    }

    fn patch(
        &mut self,
        path: &str,
        op: &str,
        value: &Value,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<(), Error> {
        if (path == "response/status" || path == "status")
            && value.as_str() == Some("CONTENT_FILTER")
        {
            self.filtered = true;
            return Ok(());
        }
        if path.ends_with("message_id") {
            if let Some(id) = value.as_i64() {
                self.message_id = Some(id);
            }
            return Ok(());
        }
        if path == "response/fragments" || path == "fragments" {
            for fragment in fragment_list(value) {
                let idx = self.types.len();
                self.types
                    .push(fragment.get("type").and_then(Value::as_str).unwrap_or("").to_string());
                self.emitted_reasoning.push(String::new());
                self.emitted_answer.push(String::new());
                self.claim(idx, &fragment, on_delta)?;
                if matches!(self.kind(idx), Some("RESPONSE") | Some("TEMPLATE_RESPONSE")) {
                    self.active = Some(idx);
                }
            }
            return Ok(());
        }
        if let Some(idx) = fragment_content_index(path) {
            let idx = if idx < 0 {
                self.types.len().saturating_sub(1)
            } else {
                idx as usize
            };
            if idx < self.types.len() {
                self.active = Some(idx);
            }
            if op == "APPEND" {
                if let Some(text) = value.as_str() {
                    self.append_at(idx, text, on_delta)?;
                }
            }
            return Ok(());
        }
        Ok(())
    }

    fn append(
        &mut self,
        text: &str,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<(), Error> {
        // Bare appends (`{"v":"…"}`) address whatever fragment is active. Once
        // a RESPONSE fragment exists, answer tokens belong to it even if a
        // snapshot briefly left `active` unset (or pointing at THINK).
        if let Some(idx) = self.last_answer_index().or(self.active) {
            self.append_at(idx, text, on_delta)
        } else {
            Ok(())
        }
    }

    fn last_answer_index(&self) -> Option<usize> {
        self.types
            .iter()
            .rposition(|kind| matches!(kind.as_str(), "RESPONSE" | "TEMPLATE_RESPONSE"))
    }

    fn append_at(
        &mut self,
        idx: usize,
        text: &str,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<(), Error> {
        match self.kind(idx) {
            Some("THINK") => {
                if let Some(slot) = self.emitted_reasoning.get_mut(idx) {
                    slot.push_str(text);
                }
                on_delta(WebDelta::Reasoning(text.to_string()))
            }
            Some("RESPONSE") | Some("TEMPLATE_RESPONSE") => {
                if let Some(slot) = self.emitted_answer.get_mut(idx) {
                    slot.push_str(text);
                }
                on_delta(WebDelta::Answer(text.to_string()))
            }
            _ => Ok(()),
        }
    }

    /// Whole-fragment snapshots repeat text already streamed; emit only the tail.
    fn claim(
        &mut self,
        idx: usize,
        fragment: &Value,
        on_delta: &mut dyn FnMut(WebDelta) -> Result<(), Error>,
    ) -> Result<(), Error> {
        let Some(content) = fragment.get("content").and_then(Value::as_str) else {
            return Ok(());
        };
        match self.kind(idx) {
            Some("THINK") => {
                let previous = self
                    .emitted_reasoning
                    .get(idx)
                    .map(String::as_str)
                    .unwrap_or("");
                let delta = if content.starts_with(previous) {
                    &content[previous.len()..]
                } else {
                    content
                };
                if !delta.is_empty() {
                    on_delta(WebDelta::Reasoning(delta.to_string()))?;
                }
                if idx < self.emitted_reasoning.len() {
                    self.emitted_reasoning[idx] = content.to_string();
                }
            }
            Some("RESPONSE") | Some("TEMPLATE_RESPONSE") => {
                let previous = self
                    .emitted_answer
                    .get(idx)
                    .map(String::as_str)
                    .unwrap_or("");
                let delta = if content.starts_with(previous) {
                    &content[previous.len()..]
                } else {
                    content
                };
                if !delta.is_empty() {
                    on_delta(WebDelta::Answer(delta.to_string()))?;
                }
                if idx < self.emitted_answer.len() {
                    self.emitted_answer[idx] = content.to_string();
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn kind(&self, idx: usize) -> Option<&str> {
        self.types.get(idx).map(String::as_str)
    }
}

/// DeepSeek has been observed sending the fragment list as an array, a single
/// object, or a JSON-encoded string. All three mean the same thing.
fn fragment_list(value: &Value) -> Vec<Value> {
    let parsed = value
        .as_str()
        .and_then(|text| serde_json::from_str::<Value>(text).ok());
    let source = parsed.as_ref().unwrap_or(value);
    match source {
        Value::Array(items) => items.iter().filter(|item| item.is_object()).cloned().collect(),
        Value::Object(_) => vec![source.clone()],
        _ => Vec::new(),
    }
}

/// `response/fragments/-1/content` → -1; other paths → None.
fn fragment_content_index(path: &str) -> Option<i64> {
    let rest = path.strip_prefix("response/fragments/")?;
    let (index, tail) = rest.split_once('/')?;
    if tail != "content" {
        return None;
    }
    index.parse::<i64>().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn apply(
        parser: &mut StreamParser,
        payload: Value,
        answer: &mut String,
        reasoning: &mut String,
    ) {
        let mut sink = |delta: WebDelta| {
            match delta {
                WebDelta::Answer(text) => answer.push_str(&text),
                WebDelta::Reasoning(text) => reasoning.push_str(&text),
            }
            Ok(())
        };
        parser
            .apply(&payload.to_string(), "message", &mut sink)
            .expect("parser accepts the frame");
    }

    #[test]
    fn string_encoded_response_fragment_keeps_first_token() {
        let mut parser = StreamParser::default();
        let mut answer = String::new();
        let mut reasoning = String::new();
        apply(
            &mut parser,
            json!({"v":{"response":{"fragments":[{"type":"THINK","content":"Думаю."}]}}}),
            &mut answer,
            &mut reasoning,
        );
        let fragments = json!([{"type":"RESPONSE","content":"При!"}]).to_string();
        apply(
            &mut parser,
            json!({"p":"response/fragments","o":"APPEND","v":fragments}),
            &mut answer,
            &mut reasoning,
        );
        apply(
            &mut parser,
            json!({"v":"вет! Рад тебя видеть"}),
            &mut answer,
            &mut reasoning,
        );
        assert_eq!(reasoning, "Думаю.");
        assert_eq!(answer, "При!вет! Рад тебя видеть");
    }

    #[test]
    fn bare_object_response_fragment_is_accepted() {
        let mut parser = StreamParser::default();
        let mut answer = String::new();
        let mut reasoning = String::new();
        apply(
            &mut parser,
            json!({"p":"response/fragments","o":"APPEND",
                   "v":{"type":"RESPONSE","content":"Ответ"}}),
            &mut answer,
            &mut reasoning,
        );
        assert_eq!(answer, "Ответ");
        assert!(reasoning.is_empty());
    }

    #[test]
    fn reused_fragment_index_does_not_skip_new_text() {
        let mut parser = StreamParser::default();
        let mut answer = String::new();
        let mut reasoning = String::new();
        apply(
            &mut parser,
            json!({"v":{"response":{"fragments":[
                {"type":"RESPONSE","content":"При"}
            ]}}}),
            &mut answer,
            &mut reasoning,
        );
        // Snapshots may rebuild the fragment list; the same index can start a
        // different fragment. Its first bytes must not be skipped because an
        // older fragment at this index had already been partially emitted.
        apply(
            &mut parser,
            json!({"v":{"response":{"fragments":[
                {"type":"RESPONSE","content":"вет!"}
            ]}}}),
            &mut answer,
            &mut reasoning,
        );
        assert_eq!(answer, "Привет!");
    }

    #[test]
    fn snapshot_between_think_and_response_keeps_the_answer_active() {
        let mut parser = StreamParser::default();
        let mut answer = String::new();
        let mut reasoning = String::new();
        apply(
            &mut parser,
            json!({"v":{"response":{"fragments":[
                {"type":"THINK","content":"Думаю."},
                {"type":"RESPONSE","content":"При!"}
            ]}}}),
            &mut answer,
            &mut reasoning,
        );
        // Repeated snapshot must not clear the active RESPONSE fragment.
        apply(
            &mut parser,
            json!({"v":{"response":{"fragments":[
                {"type":"THINK","content":"Думаю."},
                {"type":"RESPONSE","content":"При!"}
            ]}}}),
            &mut answer,
            &mut reasoning,
        );
        apply(
            &mut parser,
            json!({"v":"вет! Рад тебя видеть"}),
            &mut answer,
            &mut reasoning,
        );
        assert_eq!(answer, "При!вет! Рад тебя видеть");
        assert_eq!(reasoning, "Думаю.");
    }
}

fn unauthorized() -> Error {
    Error::new(
        ErrorKind::Unauthorized,
        "Веб-токен недействителен или истёк. Войдите на chat.deepseek.com и скопируйте userToken заново",
    )
}

fn read_envelope(resp: Response) -> Result<Value, Error> {
    let status = resp.status();
    if status == StatusCode::UNAUTHORIZED || status == StatusCode::FORBIDDEN {
        return Err(unauthorized());
    }
    let text = resp.text()?;
    if !status.is_success() {
        return Err(envelope_error(&text));
    }
    let value: Value = serde_json::from_str(&text).map_err(|_| {
        Error::new(
            ErrorKind::Api,
            "DeepSeek вернул не JSON — возможно, сработала проверка Cloudflare",
        )
    })?;

    let code = value.get("code").and_then(Value::as_i64).unwrap_or(0);
    if code != 0 {
        if code == 40003 {
            return Err(unauthorized());
        }
        let msg = value.get("msg").and_then(Value::as_str).unwrap_or("");
        return Err(Error::new(ErrorKind::Api, format!("DeepSeek web: {code} {msg}")));
    }
    if let Some(biz_code) = value.pointer("/data/biz_code").and_then(Value::as_i64) {
        if biz_code != 0 {
            if biz_code == 40003 {
                return Err(unauthorized());
            }
            let msg = value
                .pointer("/data/biz_msg")
                .and_then(Value::as_str)
                .unwrap_or("");
            return Err(Error::new(
                ErrorKind::Api,
                format!("DeepSeek web: {biz_code} {msg}"),
            ));
        }
    }
    Ok(value)
}

fn envelope_error(text: &str) -> Error {
    if let Ok(value) = serde_json::from_str::<Value>(text) {
        let code = value.get("code").and_then(Value::as_i64).unwrap_or(-1);
        let msg = value.get("msg").and_then(Value::as_str).unwrap_or("");
        if code == 40003 {
            return unauthorized();
        }
        return Error::new(ErrorKind::Api, format!("DeepSeek web: {code} {msg}"));
    }
    Error::new(
        ErrorKind::Api,
        format!("DeepSeek web вернул неожиданный ответ: {}", preview(text)),
    )
}

fn preview(text: &str) -> String {
    let flat = text.split_whitespace().collect::<Vec<_>>().join(" ");
    flat.chars().take(200).collect()
}
