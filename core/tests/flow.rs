//! End-to-end coverage against one local mock that speaks both protocols:
//! the official OpenAI-compatible API and chat.deepseek.com's web session API
//! (envelope + PoW + fragment SSE).

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

use base64::Engine as _;
use deepseek_core::{Engine, ErrorKind};
use serde_json::{json, Value};

const API_TOKEN: &str = "sk-test-token";
const WEB_TOKEN: &str = "web-good-token-123456";
const WEB_SESSION: &str = "sess-1";
const WEB_ANSWER: u64 = 777;

static DIR_ID: AtomicU64 = AtomicU64::new(0);
static LAST_CHAT_BODY: LazyLock<Mutex<String>> = LazyLock::new(|| Mutex::new(String::new()));
static LAST_WEB_BODY: LazyLock<Mutex<String>> = LazyLock::new(|| Mutex::new(String::new()));
static LAST_WEB_DELETE: LazyLock<Mutex<String>> = LazyLock::new(|| Mutex::new(String::new()));
static WEB_SESSION_CREATES: AtomicUsize = AtomicUsize::new(0);
static TEST_LOCK: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

fn temp_dir() -> PathBuf {
    let n = DIR_ID.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!(
        "localapi-test-{}-{}-{n}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

struct MockState {
    challenge_hex: String,
    salt: String,
    expire_at: i64,
    difficulty: u64,
    turns: AtomicUsize,
}

fn start_mock() -> String {
    let salt = "websalt".to_string();
    let expire_at = 1_800_000_000i64;
    let difficulty = 2_000u64;
    let prefix = format!("{salt}_{expire_at}_");
    let challenge = deepseek_core::pow::deepseek_hash_v1(
        format!("{prefix}{WEB_ANSWER}").as_bytes(),
    );
    let challenge_hex: String = challenge.iter().map(|b| format!("{b:02x}")).collect();

    let state = Arc::new(MockState {
        challenge_hex,
        salt,
        expire_at,
        difficulty,
        turns: AtomicUsize::new(0),
    });

    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            let state = Arc::clone(&state);
            std::thread::spawn(move || handle(conn, state));
        }
    });
    format!("http://{addr}")
}

fn handle(mut conn: TcpStream, state: Arc<MockState>) {
    let peer = conn.try_clone().unwrap();
    let mut reader = BufReader::new(peer);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() || request_line.is_empty() {
        return;
    }
    let mut content_length = 0usize;
    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() {
            return;
        }
        let line = line.trim_end();
        if line.is_empty() {
            break;
        }
        let lower = line.to_ascii_lowercase();
        if let Some(v) = lower.strip_prefix("content-length:") {
            content_length = v.trim().parse().unwrap_or(0);
        }
        headers.push(line.to_string());
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body).unwrap();
    }
    let body = String::from_utf8_lossy(&body).to_string();

    let api_ok = auth_is(&headers, API_TOKEN);
    let web_ok = auth_is(&headers, WEB_TOKEN);

    if request_line.starts_with("POST /v1/tools/search") {
        if api_ok {
            respond_json(
                &mut conn,
                200,
                r#"{"results":[{"title":"Gemini models","url":"https://example.com/gemini","snippet":"Gemini lineup","summary":"Gemini 2.5 models exist.","content":"Gemini 2.5 Pro and Flash are listed.","site_name":"Example","date":"2026-11-01"}]}"#,
            );
        } else {
            respond_json(&mut conn, 401, r#"{"error":{"message":"Authentication Fails"}}"#);
        }
    } else if request_line.starts_with("GET /models") {
        if api_ok {
            respond_json(&mut conn, 200, r#"{"object":"list","data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}"#);
        } else {
            respond_json(&mut conn, 401, r#"{"error":{"message":"Authentication Fails"}}"#);
        }
    } else if request_line.starts_with("GET /user/balance") {
        respond_json(
            &mut conn,
            200,
            r#"{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"12.34","granted_balance":"2.00","topped_up_balance":"10.34"}]}"#,
        );
    } else if request_line.starts_with("POST /chat/completions") {
        *LAST_CHAT_BODY.lock().unwrap() = body.clone();
        if !api_ok {
            respond_json(&mut conn, 401, r#"{"error":{"message":"Authentication Fails"}}"#);
            return;
        }
        let slow = body.contains("SLOW");
        let events = [
            r#"{"choices":[{"delta":{"reasoning_content":"Сначала "}}]}"#,
            r#"{"choices":[{"delta":{"reasoning_content":"подумаю."}}]}"#,
            r#"{"choices":[{"delta":{"content":"Привет"}}]}"#,
            r#"{"choices":[{"delta":{"content":", мир"}}]}"#,
            r#"{"choices":[{"delta":{"content":"!"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"#,
        ];
        let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        conn.write_all(head.as_bytes()).unwrap();
        for (index, event) in events.iter().enumerate() {
            let line = format!("data: {event}\n\n");
            if index == 2 {
                // Simulate a TCP boundary in the middle of a `data:` line.
                let split = line.len() / 2;
                conn.write_all(&line.as_bytes()[..split]).unwrap();
                conn.flush().unwrap();
                std::thread::sleep(Duration::from_millis(5));
                conn.write_all(&line.as_bytes()[split..]).unwrap();
            } else {
                let _ = conn.write_all(line.as_bytes());
            }
            let _ = conn.flush();
            std::thread::sleep(Duration::from_millis(if slow { 60 } else { 5 }));
        }
        let _ = write!(conn, "data: [DONE]\n\n");
        let _ = conn.flush();
    } else if request_line.starts_with("GET /api/v0/users/settings") {
        if web_ok {
            respond_json(&mut conn, 200, &web_envelope(json!({"training_allowed": true})));
        } else {
            respond_json(&mut conn, 200, r#"{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"#);
        }
    } else if request_line.starts_with("POST /api/v0/chat_session/create") {
        if !web_ok {
            respond_json(&mut conn, 200, r#"{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"#);
            return;
        }
        WEB_SESSION_CREATES.fetch_add(1, Ordering::Relaxed);
        respond_json(
            &mut conn,
            200,
            &web_envelope(json!({"chat_session": {"id": WEB_SESSION}})),
        );
    } else if request_line.starts_with("POST /api/v0/chat_session/delete") {
        *LAST_WEB_DELETE.lock().unwrap() = body.clone();
        respond_json(&mut conn, 200, &web_envelope(Value::Null));
    } else if request_line.starts_with("POST /api/v0/chat/create_pow_challenge") {
        if !web_ok {
            respond_json(&mut conn, 200, r#"{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"#);
            return;
        }
        respond_json(
            &mut conn,
            200,
            &web_envelope(json!({"challenge": {
                "algorithm": "DeepSeekHashV1",
                "challenge": state.challenge_hex.clone(),
                "salt": state.salt,
                "expire_at": state.expire_at,
                "difficulty": state.difficulty,
                "signature": "test-signature",
                "target_path": "/api/v0/chat/completion"
            }})),
        );
    } else if request_line.starts_with("POST /api/v0/chat/completion") {
        *LAST_WEB_BODY.lock().unwrap() = body.clone();
        if !web_ok {
            respond_json(&mut conn, 200, r#"{"code":40003,"msg":"Authorization Failed (invalid token)","data":null}"#);
            return;
        }
        if !pow_is_valid(&headers, &state) {
            respond_json(&mut conn, 200, r#"{"code":40001,"msg":"Invalid proof of work","data":null}"#);
            return;
        }
        let turn = state.turns.fetch_add(1, Ordering::SeqCst);
        let request: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
        if request.get("parent_message_id").and_then(Value::as_i64).is_some() {
            respond_json(
                &mut conn,
                200,
                r#"{"code":40010,"msg":"ephemeral prompts must not send a parent","data":null}"#,
            );
            return;
        }
        if request.get("chat_session_id").and_then(Value::as_str) != Some(WEB_SESSION) {
            respond_json(&mut conn, 200, r#"{"code":40010,"msg":"bad session","data":null}"#);
            return;
        }

        let message_id = (turn as i64 + 1) * 42;
        let prompt = request
            .get("prompt")
            .and_then(Value::as_str)
            .unwrap_or("");
        let second = prompt.contains("Ещё");
        let transition = prompt.contains("переход");
        let no_close = prompt.contains("незакрытый");
        let (reasoning, answer) = if second {
            ("Думаю ещё.", "Второй ответ!")
        } else {
            ("Думаю про веб.", "Веб-ответ!")
        };
        let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        conn.write_all(head.as_bytes()).unwrap();

        if no_close {
            // The upstream sends FINISHED and then keeps the TCP connection
            // open (Cloudflare keep-alive). The reader must stop at FINISHED,
            // not wait for EOF.
            let _ = write_event(
                &mut conn,
                "ready",
                &json!({"response_message_id": message_id}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"v":{"response":{"message_id": message_id,"fragments":[
                    {"id":1,"type":"THINK","content":""}
                ]}}}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"p":"response/fragments","o":"APPEND","v":[
                    {"id":2,"type":"RESPONSE","content":"При"}
                ]}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"p":"response/fragments/1/content","v":"вет"}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"v":"! Рад тебя видеть"}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"p":"response","o":"BATCH","v":[
                    {"p":"response/status","o":"SET","v":"FINISHED"}
                ]}),
            );
            let _ = conn.flush();
            std::thread::sleep(Duration::from_secs(120));
            return;
        }

        if transition {
            // Real-shape transition: BATCH creates RESPONSE with "При", the
            // next data line is a content patch without "o" ("вет"), and the
            // tail is a split data line. Two events share one TCP write.
            let _ = write_event(
                &mut conn,
                "ready",
                &json!({"response_message_id": message_id}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"v":{"response":{"message_id": message_id,"fragments":[
                    {"id":1,"type":"THINK","content":""}
                ]}}}),
            );
            let batch = json!({"v":[
                {"p":"response/fragments","o":"APPEND","v":[
                    {"id":2,"type":"RESPONSE","content":"При"}
                ]}
            ]});
            let no_operator = json!({"p":"response/fragments/1/content","v":"вет"});
            let combined = format!(
                "event: message\ndata: {batch}\n\nevent: message\ndata: {no_operator}\n\n"
            );
            conn.write_all(combined.as_bytes()).unwrap();
            conn.flush().unwrap();
            let _ = write_event_split(
                &mut conn,
                "message",
                &json!({"v":"! Рад тебя видеть"}),
            );
            let _ = write_event(
                &mut conn,
                "message",
                &json!({"p":"response/status","o":"SET","v":"FINISHED"}),
            );
            let _ = write_event(&mut conn, "finish", &json!({}));
            let _ = write_event(&mut conn, "close", &json!({}));
            return;
        }

        let _ = write_event(&mut conn, "ready", &json!({"response_message_id": message_id}));
        let _ = write_event(
            &mut conn,
            "message",
            &json!({"v":{"response":{"message_id": message_id,"fragments":[
                {"id":1,"type":"THINK","content":""}
            ]}}}),
        );
        // Split one event between two TCP writes: the reader must keep a
        // partial line instead of parsing each network chunk separately.
        let _ = write_event_split(
            &mut conn,
            "message",
            &json!({"p":"response/fragments/-1/content","o":"APPEND","v":reasoning}),
        );
        // The first answer token arrives inside the fragment object; the rest
        // is appended afterwards. Neither part may be dropped at the THINK →
        // RESPONSE transition.
        let split = answer.char_indices().nth(3).map(|(i, _)| i).unwrap_or(answer.len());
        let (initial, tail) = answer.split_at(split);
        let _ = write_event(
            &mut conn,
            "message",
            &json!({"p":"response/fragments","o":"APPEND",
                    "v":[{"id":2,"type":"RESPONSE","content":initial}]}),
        );
        let _ = write_event_split(
            &mut conn,
            "message",
            &json!({"p":"response/fragments/-1/content","o":"APPEND","v":tail}),
        );
        let _ = write_event(
            &mut conn,
            "message",
            &json!({"p":"response/status","o":"SET","v":"FINISHED"}),
        );
        let _ = write_event(&mut conn, "finish", &json!({}));
        let _ = write_event(&mut conn, "close", &json!({}));
    } else {
        respond_json(&mut conn, 404, r#"{"error":{"message":"not found"}}"#);
    }
}

fn write_event(conn: &mut TcpStream, event: &str, data: &Value) -> std::io::Result<()> {
    write!(conn, "event: {event}\ndata: {data}\n\n")?;
    conn.flush()
}

/// Writes one SSE event in two chunks with a pause, simulating a TCP packet
/// boundary in the middle of the JSON (and possibly inside a UTF-8 sequence).
fn write_event_split(conn: &mut TcpStream, event: &str, data: &Value) -> std::io::Result<()> {
    let text = format!("event: {event}\ndata: {data}\n\n");
    let split = text.len() / 2;
    conn.write_all(&text.as_bytes()[..split])?;
    conn.flush()?;
    std::thread::sleep(Duration::from_millis(5));
    conn.write_all(&text.as_bytes()[split..])?;
    conn.flush()
}

fn auth_is(headers: &[String], token: &str) -> bool {
    headers
        .iter()
        .find(|h| h.to_ascii_lowercase().starts_with("authorization:"))
        .map(|h| h.splitn(2, ':').nth(1).unwrap_or("").trim() == format!("Bearer {token}"))
        .unwrap_or(false)
}

fn pow_is_valid(headers: &[String], state: &MockState) -> bool {
    let raw = headers
        .iter()
        .find(|h| h.to_ascii_lowercase().starts_with("x-ds-pow-response:"))
        .and_then(|h| h.splitn(2, ':').nth(1))
        .map(str::trim);
    let Some(raw) = raw else { return false };
    let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(raw) else {
        return false;
    };
    let Ok(payload) = serde_json::from_slice::<Value>(&bytes) else {
        return false;
    };
    payload.get("answer").and_then(Value::as_u64) == Some(WEB_ANSWER)
        && payload.get("challenge").and_then(Value::as_str) == Some(state.challenge_hex.as_str())
        && payload.get("target_path").and_then(Value::as_str)
            == Some("/api/v0/chat/completion")
}

fn web_envelope(biz_data: Value) -> String {
    json!({
        "code": 0,
        "msg": "",
        "data": {"biz_code": 0, "biz_msg": "", "biz_data": biz_data}
    })
    .to_string()
}

fn respond_json(conn: &mut TcpStream, status: u16, body: &str) {
    let head = format!(
        "HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = conn.write_all(head.as_bytes());
    let _ = conn.write_all(body.as_bytes());
}

fn wait_done(engine: &Engine, stream_id: u64) -> deepseek_core::Poll {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let poll = engine.poll(stream_id).unwrap();
        if poll.done {
            return poll;
        }
        assert!(Instant::now() < deadline, "stream did not finish in time: {poll:?}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn api_mode_validates_streams_and_survives_restart() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();

    assert!(!engine.status().has_token);
    assert_eq!(engine.status().mode, "api");

    let status = engine.set_token(API_TOKEN, "api").unwrap();
    assert!(status.has_token);
    assert_eq!(status.mode, "api");
    assert!(status.models.iter().any(|m| m == "deepseek-flash"));
    assert_eq!(status.balance.expect("balance").total, "12.34");

    let auth_file = dir.join("auth.json");
    assert!(auth_file.exists());
    let auth: Value = serde_json::from_str(&std::fs::read_to_string(&auth_file).unwrap()).unwrap();
    assert_eq!(auth["mode"], "api");
    assert_eq!(auth["token"], API_TOKEN);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&auth_file).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "credential file must be private");
    }

    let chat = engine.new_chat().unwrap();
    let start = engine.send(&chat.id, "Привет, как дела?", "max").unwrap();
    assert_eq!(start.chat.messages.len(), 1);

    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);
    assert_eq!(poll.content, "Привет, мир!");
    assert_eq!(poll.reasoning, "Сначала подумаю.");
    assert_eq!(poll.usage.unwrap().total_tokens, 15);

    let chats = engine.list_chats().unwrap();
    assert_eq!(chats.len(), 1);
    assert_eq!(chats[0].title, "Привет, как дела?");
    assert_eq!(chats[0].message_count, 2);

    drop(engine);
    let reopened = Engine::with_base(&dir, &base).unwrap();
    assert!(reopened.status().has_token);
    assert_eq!(reopened.status().mode, "api");
    let stored = reopened.get_chat(&chats[0].id).unwrap();
    assert_eq!(stored.messages.len(), 2);
    assert_eq!(stored.messages[1].content, "Привет, мир!");
    assert_eq!(stored.messages[1].reasoning, "Сначала подумаю.");
}

#[test]
fn bad_api_token_is_rejected_and_not_persisted() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();

    let err = engine.set_token("sk-wrong-token", "api").unwrap_err();
    assert_eq!(err.kind, ErrorKind::Unauthorized);
    assert!(!engine.status().has_token);
    assert!(!dir.join("auth.json").exists(), "a rejected key must never be stored");
}

#[test]
fn cancel_stops_generation_and_keeps_partial_answer() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let chat = engine.new_chat().unwrap();
    let start = engine.send(&chat.id, "SLOW please", "high").unwrap();

    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        let poll = engine.poll(start.stream_id).unwrap();
        if !poll.content.is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    engine.cancel(start.stream_id).unwrap();

    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.cancelled, "cancelled flag must be reported");
    assert!(poll.error.is_none());
    assert!(!poll.content.is_empty());

    let stored = engine.get_chat(&chat.id).unwrap();
    assert_eq!(stored.messages.len(), 2);
    assert!(stored.messages[1].content.contains("Привет"));
}

#[test]
fn deleting_a_chat_removes_it_from_history() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let chat = engine.new_chat().unwrap();
    let start = engine.send(&chat.id, "удали меня", "low").unwrap();
    wait_done(&engine, start.stream_id);
    assert_eq!(engine.list_chats().unwrap().len(), 1);

    engine.delete_chat(&chat.id).unwrap();
    assert!(engine.list_chats().unwrap().is_empty());
    assert_eq!(engine.get_chat(&chat.id).unwrap_err().kind, ErrorKind::NotFound);
}

#[test]
fn path_traversal_ids_are_refused() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    let err = engine.get_chat("../../etc/passwd").unwrap_err();
    assert_eq!(err.kind, ErrorKind::Invalid);
}

#[test]
fn web_mode_validates_pow_streams_and_continues_thread() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();

    // localStorage stores JSON; the engine must unwrap `value`.
    let wrapped = json!({"value": WEB_TOKEN, "__version": "0"}).to_string();
    let status = engine.set_token(&wrapped, "web").unwrap();
    assert_eq!(status.mode, "web");
    assert!(status.has_token);
    assert!(status.balance.is_none());

    let auth: Value =
        serde_json::from_str(&std::fs::read_to_string(dir.join("auth.json")).unwrap()).unwrap();
    assert_eq!(auth["mode"], "web");
    assert_eq!(auth["token"], WEB_TOKEN);

    let chat = engine.new_chat().unwrap();
    let start = engine.send(&chat.id, "Привет веб", "high").unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);
    assert_eq!(poll.reasoning, "Думаю про веб.");
    assert_eq!(poll.content, "Веб-ответ!");

    let stored = engine.get_chat(&chat.id).unwrap();
    assert!(stored.web_session_id.is_empty(), "web session must be ephemeral");
    assert_eq!(stored.web_parent_id, None);
    assert_eq!(stored.messages.len(), 2);
    let deleted: Value = serde_json::from_str(&LAST_WEB_DELETE.lock().unwrap()).unwrap();
    assert_eq!(deleted["chat_session_ids"][0], WEB_SESSION);

    // Every turn creates a fresh session and flattens the transcript.
    let start = engine
        .send_opts(&chat.id, "Ещё", "deepseek-flash", true, true, Vec::new(), "high")
        .unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);
    assert_eq!(poll.content, "Второй ответ!");
    let stored = engine.get_chat(&chat.id).unwrap();
    assert_eq!(stored.messages.len(), 4);
    assert!(stored.web_session_id.is_empty());
    assert_eq!(stored.web_parent_id, None);
    let web_body: Value = serde_json::from_str(&LAST_WEB_BODY.lock().unwrap()).unwrap();
    assert_eq!(web_body["search_enabled"], true);
    let prompt = web_body["prompt"].as_str().unwrap();
    assert!(prompt.contains("Привет веб"), "prompt must carry the transcript");
    assert!(prompt.contains("Ещё"));
    assert_eq!(web_body["parent_message_id"], Value::Null);

    drop(engine);
    let reopened = Engine::with_base(&dir, &base).unwrap();
    assert_eq!(reopened.status().mode, "web");
}

#[test]
fn web_transition_without_operator_keeps_second_token() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(WEB_TOKEN, "web").unwrap();

    let chat = engine.new_chat().unwrap();
    let start = engine
        .send_opts(
            &chat.id,
            "проверка перехода",
            "deepseek-flash",
            false,
            false,
            Vec::new(),
            "high",
        )
        .unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);
    assert_eq!(poll.content, "Привет! Рад тебя видеть");
    assert!(poll.reasoning.is_empty(), "reasoning: {:?}", poll.reasoning);
}

#[test]
fn local_api_stops_on_finished_without_socket_close() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(WEB_TOKEN, "web").unwrap();
    let status = engine.start_server(0).unwrap();
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap();
    let url = format!("http://127.0.0.1:{}/v1/chat/completions", status.port);

    let started = Instant::now();
    let response = client
        .post(&url)
        .bearer_auth(&status.key)
        .json(&json!({
            "messages": [{"role": "user", "content": "незакрытый"}]
        }))
        .send()
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: Value = response.json().unwrap();
    assert_eq!(
        body["choices"][0]["message"]["content"],
        "Привет! Рад тебя видеть"
    );
    assert!(
        started.elapsed() < Duration::from_millis(500),
        "non-stream waited for socket close: {:?}",
        started.elapsed()
    );

    let started = Instant::now();
    let response = client
        .post(&url)
        .bearer_auth(&status.key)
        .json(&json!({
            "messages": [{"role": "user", "content": "незакрытый"}],
            "stream": true
        }))
        .send()
        .unwrap();
    let text = response.text().unwrap();
    // The stream carries deltas, not the concatenated message.
    assert!(text.contains("\"content\":\"При\""), "stream: {text}");
    assert!(text.contains("\"content\":\"вет\""), "stream: {text}");
    assert!(text.contains("\"content\":\"! Рад тебя видеть\""), "stream: {text}");
    assert!(text.contains("data: [DONE]"), "stream: {text}");
    assert!(
        started.elapsed() < Duration::from_millis(500),
        "stream waited for socket close: {:?}",
        started.elapsed()
    );

    engine.stop_server();
}

#[test]
fn bad_web_token_is_rejected_even_with_http_200() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();

    let err = engine.set_token("bad-web-token-xxxxx", "web").unwrap_err();
    assert_eq!(err.kind, ErrorKind::Unauthorized);
    assert!(!engine.status().has_token);
    assert!(!dir.join("auth.json").exists());
}

#[test]
fn unknown_mode_is_rejected() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    let err = engine.set_token(API_TOKEN, "browser").unwrap_err();
    assert_eq!(err.kind, ErrorKind::Invalid);
}

#[test]
fn local_server_proxies_openai_requests() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let status = engine.start_server(0).unwrap();
    assert!(status.running);
    assert!(status.port > 0);
    assert!(!status.key.is_empty());
    assert!(status.base_url.starts_with("http://127.0.0.1:"));

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap();
    let url = format!("http://127.0.0.1:{}/v1/chat/completions", status.port);

    let unauthorized = client
        .post(&url)
        .json(&json!({"messages": [{"role": "user", "content": "x"}]}))
        .send()
        .unwrap();
    assert_eq!(unauthorized.status(), 401);

    let plain = client
        .post(&url)
        .bearer_auth(&status.key)
        .json(&json!({
            "model": "deepseek-flash",
            "messages": [{"role": "user", "content": "привет"}]
        }))
        .send()
        .unwrap();
    assert_eq!(plain.status(), 200);
    let body: Value = plain.json().unwrap();
    assert_eq!(body["choices"][0]["message"]["content"], "Привет, мир!");
    assert_eq!(
        body["choices"][0]["message"]["reasoning_content"],
        "Сначала подумаю."
    );

    let models = client
        .get(format!("http://127.0.0.1:{}/v1/models", status.port))
        .bearer_auth(&status.key)
        .send()
        .unwrap();
    let body: Value = models.json().unwrap();
    assert_eq!(body["data"][0]["id"], "deepseek-flash");

    let streamed = client
        .post(&url)
        .bearer_auth(&status.key)
        .json(&json!({
            "messages": [{"role": "user", "content": "привет"}],
            "stream": true
        }))
        .send()
        .unwrap();
    assert_eq!(streamed.status(), 200);
    let text = streamed.text().unwrap();
    assert!(text.contains("Привет"), "stream body: {text}");
    assert!(text.contains("data: [DONE]"), "stream body: {text}");

    engine.stop_server();
    assert!(!engine.server_status().running);
}

#[test]
fn local_server_works_with_web_session() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(WEB_TOKEN, "web").unwrap();
    let status = engine.start_server(0).unwrap();

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap();
    WEB_SESSION_CREATES.store(0, Ordering::Relaxed);
    let response = client
        .post(format!("http://127.0.0.1:{}/v1/chat/completions", status.port))
        .bearer_auth(&status.key)
        .json(&json!({
            "messages": [
                {"role": "system", "content": "Отвечай кратко."},
                {"role": "user", "content": "привет"}
            ]
        }))
        .send()
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: Value = response.json().unwrap();
    assert_eq!(body["choices"][0]["message"]["content"], "Веб-ответ!");
    assert_eq!(
        body["choices"][0]["message"]["reasoning_content"],
        "Думаю про веб."
    );

    // The local server keeps one web session for its lifetime instead of
    // creating a new one for every request.
    *LAST_WEB_DELETE.lock().unwrap() = String::new();
    let second = client
        .post(format!("http://127.0.0.1:{}/v1/chat/completions", status.port))
        .bearer_auth(&status.key)
        .json(&json!({
            "messages": [
                {"role": "system", "content": "Отвечай кратко."},
                {"role": "user", "content": "привет"}
            ]
        }))
        .send()
        .unwrap();
    assert_eq!(second.status(), 200);
    let body: Value = second.json().unwrap();
    assert_eq!(body["choices"][0]["message"]["content"], "Веб-ответ!");
    assert_eq!(WEB_SESSION_CREATES.load(Ordering::Relaxed), 1);

    engine.stop_server();
    let deleted: Value = serde_json::from_str(&LAST_WEB_DELETE.lock().unwrap()).unwrap();
    assert_eq!(deleted["chat_session_ids"][0], WEB_SESSION);
}

#[test]
fn local_server_reads_lowercase_content_length_and_continue() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();
    let status = engine.start_server(0).unwrap();

    let mut socket = TcpStream::connect(("127.0.0.1", status.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let body = json!({
        "messages": [{"role": "user", "content": "привет"}]
    })
    .to_string();
    write!(
        socket,
        "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\ncontent-length: {}\r\nexpect: 100-continue\r\nConnection: keep-alive\r\n\r\n",
        status.key,
        body.len()
    )
    .unwrap();
    socket.flush().unwrap();

    // tiny_http must answer the expectation before the body is sent.
    let mut first = [0u8; 512];
    let n = socket.read(&mut first).unwrap();
    let head = String::from_utf8_lossy(&first[..n]).to_string();
    assert!(head.contains("100 Continue"), "expected 100 Continue, got: {head}");

    write!(socket, "{body}").unwrap();
    socket.flush().unwrap();
    let mut collected = Vec::new();
    let mut buf = [0u8; 4096];
    while !String::from_utf8_lossy(&collected).contains("choices") {
        let n = socket.read(&mut buf).unwrap();
        if n == 0 {
            break;
        }
        collected.extend_from_slice(&buf[..n]);
    }
    let response = String::from_utf8_lossy(&collected);
    assert!(response.contains("200 OK"), "response: {response}");
    assert!(response.contains("Привет, мир!"), "response: {response}");

    engine.stop_server();
}

#[test]
fn local_server_streams_incrementally() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();
    let status = engine.start_server(0).unwrap();

    let mut socket = TcpStream::connect(("127.0.0.1", status.port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let body = json!({
        "messages": [{"role": "user", "content": "SLOW"}],
        "stream": true
    })
    .to_string();
    write!(
        socket,
        "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status.key,
        body.len(),
        body
    )
    .unwrap();
    socket.flush().unwrap();

    let started = Instant::now();
    let mut collected = Vec::new();
    let mut buf = [0u8; 4096];
    while started.elapsed() < Duration::from_secs(2) {
        let n = socket.read(&mut buf).unwrap();
        if n == 0 {
            break;
        }
        collected.extend_from_slice(&buf[..n]);
        if String::from_utf8_lossy(&collected).contains("data:") {
            break;
        }
    }
    let elapsed = started.elapsed();
    let text = String::from_utf8_lossy(&collected);
    assert!(text.contains("data:"), "no SSE in first read: {text}");
    assert!(
        elapsed < Duration::from_millis(250),
        "SSE was buffered until completion: {elapsed:?}"
    );

    engine.stop_server();
}

#[test]
fn offline_mode_blocks_network_and_persists() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let status = engine.set_online(false).unwrap();
    assert!(!status.online);

    let chat = engine.new_chat().unwrap();
    let err = engine.send(&chat.id, "привет", "high").unwrap_err();
    assert_eq!(err.kind, ErrorKind::Network);
    assert_eq!(engine.revalidate().unwrap_err().kind, ErrorKind::Network);
    assert_eq!(engine.start_server(0).unwrap_err().kind, ErrorKind::Network);

    drop(engine);
    let reopened = Engine::with_base(&dir, &base).unwrap();
    assert!(!reopened.status().online);
    assert!(reopened.set_online(true).unwrap().online);
}

#[test]
fn system_prompt_is_sent_first_and_not_persisted() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let chat = engine.new_chat().unwrap();
    let start = engine
        .send_opts_with_system(
            &chat.id,
            "Привет",
            "deepseek-flash",
            true,
            false,
            Vec::new(),
            "high",
            Some("Отвечай кратко"),
        )
        .unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);

    let body: Value = serde_json::from_str(&LAST_CHAT_BODY.lock().unwrap()).unwrap();
    assert_eq!(body["messages"][0]["role"], "system");
    assert_eq!(body["messages"][0]["content"], "Отвечай кратко");
    assert_eq!(body["messages"][1]["role"], "user");
    assert_eq!(body["thinking"], serde_json::json!({ "type": "enabled" }));
    assert_eq!(body["reasoning_effort"], "high");

    let stored = engine.get_chat(&chat.id).unwrap();
    assert!(stored.messages.iter().all(|m| m.role != "system"));
    assert_eq!(stored.messages.len(), 2);
}

#[test]
fn search_injects_results_and_reports_sources() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();

    let chat = engine.new_chat().unwrap();
    let start = engine
        .send_opts(
            &chat.id,
            "Какие модели Gemini актуальны?",
            "deepseek-flash",
            true,
            true,
            Vec::new(),
            "high",
        )
        .unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);
    assert_eq!(poll.sources.len(), 1);
    assert_eq!(poll.sources[0].url, "https://example.com/gemini");

    let body: Value = serde_json::from_str(&LAST_CHAT_BODY.lock().unwrap()).unwrap();
    let system = &body["messages"][0];
    assert_eq!(system["role"], "system");
    assert!(
        system["content"].as_str().unwrap_or("").contains("https://example.com/gemini"),
        "search context missing: {system}"
    );

    let stored = engine.get_chat(&chat.id).unwrap();
    assert_eq!(stored.messages[1].sources.len(), 1);
}

#[test]
fn api_images_are_sent_as_content_parts() {
    let _guard = TEST_LOCK.lock().unwrap();
    let base = start_mock();
    let dir = temp_dir();
    let engine = Engine::with_base(&dir, &base).unwrap();
    engine.set_token(API_TOKEN, "api").unwrap();
    let chat = engine.new_chat().unwrap();
    let image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z2S8AAAAASUVORK5CYII=";
    let start = engine
        .send_opts(
            &chat.id,
            "что на фото?",
            "deepseek-flash",
            true,
            false,
            vec![image.to_string()],
            "high",
        )
        .unwrap();
    let poll = wait_done(&engine, start.stream_id);
    assert!(poll.error.is_none(), "stream error: {:?}", poll.error);

    let body: Value = serde_json::from_str(&LAST_CHAT_BODY.lock().unwrap()).unwrap();
    let content = &body["messages"][0]["content"];
    assert!(content.is_array(), "image message must use content parts: {content}");
    assert_eq!(content[1]["type"], "image_url");
    assert_eq!(content[1]["image_url"]["url"], image);

    let stored = engine.get_chat(&chat.id).unwrap();
    assert_eq!(stored.messages[0].images.len(), 1);
}
