//! DeepSeek V4.1 Flash (1017) client core.
//!
//! Everything that must be fast and reliable lives here: token validation,
//! streaming with reasoning, and a crash-safe local conversation store.

pub mod api;
pub mod engine;
pub mod pow;
pub mod server;
pub mod store;
pub mod types;
pub mod web;

pub use engine::{Engine, ServerStatus, Status};
pub use types::{
    Balance, ChatSummary, Conversation, Error, ErrorKind, Message, Poll, SendStart, Usage,
    DEFAULT_BASE_URL, MODEL_ID, MODEL_LABEL, MODEL_VERSION, Source,
};
