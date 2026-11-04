//! Thin JNI surface. Every call returns a JSON envelope:
//! `{"ok":true,"data":…}` or `{"ok":false,"error":{"kind":…,"message":…}}`.
//! The Kotlin/Java shell never parses domain data, and the WebView does the
//! JSON dance once.

use std::sync::{LazyLock, Mutex};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};

use deepseek_core::{Engine, Error};

static ENGINE: LazyLock<Mutex<Option<Engine>>> = LazyLock::new(|| Mutex::new(None));

fn engine_guard() -> std::sync::MutexGuard<'static, Option<Engine>> {
    ENGINE.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn with_engine<T>(f: impl FnOnce(&Engine) -> Result<T, Error>) -> Result<T, Error>
where
    T: serde::Serialize,
{
    let guard = engine_guard();
    let engine = guard
        .as_ref()
        .ok_or_else(|| Error::invalid("Движок не инициализирован"))?;
    f(engine)
}

fn ok<T: serde::Serialize>(value: T) -> Value {
    json!({ "ok": true, "data": serde_json::to_value(value).unwrap_or(Value::Null) })
}

fn fail(e: Error) -> Value {
    json!({ "ok": false, "error": { "kind": e.kind.code(), "message": e.message } })
}

fn reply(env: &mut JNIEnv, value: Value) -> jstring {
    match env.new_string(value.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn text(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(|s| s.into()).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jstring {
    let path = text(&mut env, &dir);
    let result = Engine::open(path).map(|engine| {
        *engine_guard() = Some(engine);
    });
    let payload = match result {
        Ok(()) => match with_engine(|e| Ok(e.status())) {
            Ok(status) => ok(status),
            Err(e) => fail(e),
        },
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| Ok(e.status())) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeSetToken(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
    mode: JString,
) -> jstring {
    let token = text(&mut env, &token);
    let mode = text(&mut env, &mode);
    let payload = match with_engine(|e| e.set_token(&token, &mode)) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeRevalidate(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| e.revalidate()) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeClearToken(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| e.clear_token()) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeListChats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| e.list_chats()) {
        Ok(chats) => ok(chats),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeGetChat(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    let id = text(&mut env, &id);
    let payload = match with_engine(|e| e.get_chat(&id)) {
        Ok(chat) => ok(chat),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeDeleteChat(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
) -> jstring {
    let id = text(&mut env, &id);
    let payload = match with_engine(|e| e.delete_chat(&id)) {
        Ok(()) => ok(Value::Null),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeSend(
    mut env: JNIEnv,
    _class: JClass,
    chat_id: JString,
    message: JString,
    model: JString,
    effort: JString,
    thinking: jboolean,
    search: jboolean,
    images: JString,
    system_prompt: JString,
) -> jstring {
    let chat_id = text(&mut env, &chat_id);
    let message = text(&mut env, &message);
    let model = text(&mut env, &model);
    let effort = text(&mut env, &effort);
    let images_json = text(&mut env, &images);
    let system_prompt = text(&mut env, &system_prompt);
    let images: Vec<String> = serde_json::from_str(&images_json).unwrap_or_default();
    let payload = match with_engine(|e| {
        e.send_opts_with_system(
            &chat_id,
            &message,
            &model,
            thinking != 0,
            search != 0,
            images,
            &effort,
            Some(&system_prompt),
        )
    }) {
        Ok(start) => ok(start),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativePoll(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: jlong,
) -> jstring {
    let payload = match with_engine(|e| e.poll(stream_id as u64)) {
        Ok(poll) => ok(poll),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeCancel(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: jlong,
) -> jstring {
    let payload = match with_engine(|e| e.cancel(stream_id as u64)) {
        Ok(()) => ok(Value::Null),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeStartServer(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jstring {
    let payload = match with_engine(|e| e.start_server(port as u16)) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeStopServer(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| {
        e.stop_server();
        Ok(serde_json::Value::Null)
    }) {
        Ok(value) => ok(value),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeServerStatus(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let payload = match with_engine(|e| Ok(e.server_status())) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}

#[no_mangle]
pub extern "system" fn Java_dev_localapi_deepseek_Rust_nativeSetOnline(
    mut env: JNIEnv,
    _class: JClass,
    online: jboolean,
) -> jstring {
    let payload = match with_engine(|e| e.set_online(online != 0)) {
        Ok(status) => ok(status),
        Err(e) => fail(e),
    };
    reply(&mut env, payload)
}
