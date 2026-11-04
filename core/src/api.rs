use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use reqwest::blocking::{Client, Response};
use reqwest::StatusCode;
use serde_json::{json, Value};

use crate::types::{Balance, Error, ErrorKind, Message, Source, Usage};

#[derive(Debug, Clone)]
pub enum Delta {
    Reasoning(String),
    Content(String),
    Usage(Usage),
}

/// Blocking DeepSeek API client. One instance per token; cheap to clone.
#[derive(Clone)]
pub struct Api {
    client: Client,
    base: String,
    key: String,
}

impl Api {
    pub fn new(base: &str, key: &str) -> Result<Self, Error> {
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(900))
            .user_agent(concat!("localapi-deepseek/", env!("CARGO_PKG_VERSION")))
            .build()?;
        Ok(Self { client, base: base.trim_end_matches('/').to_string(), key: key.to_string() })
    }

    /// Cheap credential check: GET /models. 200 means the token is alive.
    pub fn validate(&self) -> Result<Vec<String>, Error> {
        let resp = self
            .client
            .get(format!("{}/models", self.base))
            .bearer_auth(&self.key)
            .send()?;
        let body = read_json(resp)?;
        let models = body
            .get("data")
            .and_then(Value::as_array)
            .map(|items| {
                items
                    .iter()
                    .filter_map(|m| m.get("id").and_then(Value::as_str).map(str::to_string))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        if models.is_empty() {
            return Err(Error::new(ErrorKind::Api, "Сервер не вернул ни одной модели"));
        }
        Ok(models)
    }

    /// DeepSeek Search: live web results for the user's query.
    pub fn search(&self, query: &str) -> Result<Vec<WebHit>, Error> {
        let query: String = query.trim().chars().take(128).collect();
        if query.trim().is_empty() {
            return Ok(Vec::new());
        }
        let resp = self
            .client
            .post(format!("{}/v1/tools/search", self.base))
            .bearer_auth(&self.key)
            .json(&json!({
                "query": query,
                "count": 5,
                "need_content": true,
                "ori_question": query,
            }))
            .send()?;
        let body = read_json(resp)?;
        let mut hits = Vec::new();
        if let Some(results) = body.get("results").and_then(Value::as_array) {
            for item in results.iter().take(5) {
                let url = item.get("url").and_then(Value::as_str).unwrap_or_default();
                if url.is_empty() {
                    continue;
                }
                let title = item
                    .get("title")
                    .and_then(Value::as_str)
                    .unwrap_or(url)
                    .to_string();
                let summary = item
                    .get("summary")
                    .or_else(|| item.get("snippet"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                let content = item
                    .get("content")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                hits.push(WebHit {
                    source: Source {
                        title,
                        url: url.to_string(),
                        site: item
                            .get("site_name")
                            .and_then(Value::as_str)
                            .map(str::to_string),
                        date: item.get("date").and_then(Value::as_str).map(str::to_string),
                    },
                    summary,
                    content,
                });
            }
        }
        Ok(hits)
    }

    /// Best-effort balance lookup; a failure here never blocks token setup.
    pub fn balance(&self) -> Option<Balance> {
        let resp = self
            .client
            .get(format!("{}/user/balance", self.base))
            .bearer_auth(&self.key)
            .send()
            .ok()?;
        let body: Value = resp.json().ok()?;
        let info = body.get("balance_infos")?.as_array()?.first()?.clone();
        Some(Balance {
            available: body.get("is_available").and_then(Value::as_bool).unwrap_or(true),
            currency: info.get("currency").and_then(Value::as_str).unwrap_or("USD").to_string(),
            total: info.get("total_balance").and_then(Value::as_str).unwrap_or("0").to_string(),
            granted: info.get("granted_balance").and_then(Value::as_str).unwrap_or("0").to_string(),
            topped_up: info.get("topped_up_balance").and_then(Value::as_str).unwrap_or("0").to_string(),
        })
    }

    /// Streams one completion. `cancel` is polled between deltas; on cancel the
    /// callback returns `ErrorKind::Cancelled` and the connection is dropped.
    pub fn stream_chat(
        &self,
        history: &[Message],
        model: &str,
        thinking: bool,
        effort: &str,
        cancel: &AtomicBool,
        on_delta: &mut dyn FnMut(Delta) -> Result<(), Error>,
    ) -> Result<Option<Usage>, Error> {
        let messages: Vec<Value> = history
            .iter()
            .filter(|m| {
                !m.content.is_empty() || !m.reasoning.is_empty() || !m.images.is_empty()
            })
            .map(|m| {
                if m.images.is_empty() {
                    return json!({ "role": m.role, "content": m.content });
                }
                let mut parts = Vec::new();
                if !m.content.is_empty() {
                    parts.push(json!({ "type": "text", "text": m.content }));
                }
                for image in &m.images {
                    parts.push(json!({
                        "type": "image_url",
                        "image_url": { "url": image }
                    }));
                }
                json!({ "role": m.role, "content": parts })
            })
            .collect();

        let mut body = json!({
            "model": model,
            "messages": messages,
            "stream": true,
        });
        if thinking {
            body["thinking"] = json!({ "type": "enabled" });
            body["reasoning_effort"] = json!(effort);
        } else {
            body["thinking"] = json!({ "type": "disabled" });
        }

        let resp = self
            .client
            .post(format!("{}/chat/completions", self.base))
            .bearer_auth(&self.key)
            .header("Accept", "text/event-stream")
            .json(&body)
            .send()?;

        let resp = ensure_success(resp)?;
        let mut reader = BufReader::new(resp);
        let mut line = String::new();
        let mut usage = None;

        // DeepSeek emits one JSON document per `data:` line; blank separators
        // and `event:`/comment lines are ignored.
        loop {
            line.clear();
            let read = reader.read_line(&mut line)?;
            if read == 0 {
                break;
            }
            if cancel.load(Ordering::Relaxed) {
                return Err(Error::new(ErrorKind::Cancelled, "Запрос отменён"));
            }
            let trimmed = line.trim_end_matches(['\r', '\n']);
            let Some(payload) = trimmed.strip_prefix("data:") else { continue };
            let payload = payload.trim_start();
            if payload.is_empty() {
                continue;
            }
            if handle_event(payload, on_delta, &mut usage)? == Stop::Done {
                return Ok(usage);
            }
        }
        Ok(usage)
    }
}

#[derive(Debug, Clone)]
pub struct WebHit {
    pub source: Source,
    pub summary: String,
    pub content: String,
}

#[derive(PartialEq, Eq)]
enum Stop {
    Continue,
    Done,
}

fn handle_event(
    data: &str,
    on_delta: &mut dyn FnMut(Delta) -> Result<(), Error>,
    usage: &mut Option<Usage>,
) -> Result<Stop, Error> {
    let data = data.trim();
    if data == "[DONE]" {
        return Ok(Stop::Done);
    }
    let value: Value = match serde_json::from_str(data) {
        Ok(v) => v,
        Err(_) => return Ok(Stop::Continue), // keep-alive or vendor noise
    };

    if let Some(err) = value.get("error") {
        let msg = err
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("Неизвестная ошибка во время генерации")
            .to_string();
        return Err(Error::new(ErrorKind::Api, msg));
    }

    if let Some(u) = value.get("usage").filter(|u| !u.is_null()) {
        *usage = Some(Usage {
            prompt_tokens: u.get("prompt_tokens").and_then(Value::as_u64).unwrap_or(0),
            completion_tokens: u.get("completion_tokens").and_then(Value::as_u64).unwrap_or(0),
            total_tokens: u.get("total_tokens").and_then(Value::as_u64).unwrap_or(0),
        });
    }

    let delta = value.pointer("/choices/0/delta");
    let Some(delta) = delta else { return Ok(Stop::Continue) };

    if let Some(reasoning) = delta.get("reasoning_content").and_then(Value::as_str) {
        if !reasoning.is_empty() {
            on_delta(Delta::Reasoning(reasoning.to_string()))?;
        }
    }
    if let Some(content) = delta.get("content").and_then(Value::as_str) {
        if !content.is_empty() {
            on_delta(Delta::Content(content.to_string()))?;
        }
    }
    Ok(Stop::Continue)
}

fn read_json(resp: Response) -> Result<Value, Error> {
    let resp = ensure_success(resp)?;
    let text = resp.text()?;
    serde_json::from_str(&text)
        .map_err(|_| Error::new(ErrorKind::Api, format!("Неожиданный ответ сервера: {}", preview(&text))))
}

fn ensure_success(resp: Response) -> Result<Response, Error> {
    let status = resp.status();
    if status.is_success() {
        return Ok(resp);
    }
    let text = resp.text().unwrap_or_default();
    let message = serde_json::from_str::<Value>(&text)
        .ok()
        .and_then(|v| v.pointer("/error/message").and_then(Value::as_str).map(str::to_string))
        .unwrap_or_else(|| preview(&text));

    Err(match status {
        StatusCode::UNAUTHORIZED => Error::new(
            ErrorKind::Unauthorized,
            "Токен недействителен или отозван. Скопируйте актуальный ключ в platform.deepseek.com",
        ),
        StatusCode::PAYMENT_REQUIRED => {
            Error::new(ErrorKind::InsufficientBalance, "Недостаточно средств на балансе DeepSeek")
        }
        StatusCode::TOO_MANY_REQUESTS => {
            Error::new(ErrorKind::RateLimited, "Слишком много запросов. Подождите секунду и повторите")
        }
        _ => Error::new(ErrorKind::Api, format!("DeepSeek API: {message}")),
    })
}

fn preview(body: &str) -> String {
    let flat = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() > 240 {
        flat.chars().take(239).chain(std::iter::once('…')).collect()
    } else if flat.is_empty() {
        "пустой ответ".to_string()
    } else {
        flat
    }
}
