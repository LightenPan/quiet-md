//! The WebDAV surface the path-based sync engine depends on.

use std::fmt;
use std::time::Duration;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RemoteEntry {
    /// NFC path below the configured remote root, without a leading slash.
    pub rel: String,
    pub is_dir: bool,
    pub etag: Option<String>,
    pub size: Option<u64>,
    pub modified: Option<String>,
}

impl RemoteEntry {
    #[cfg(test)]
    pub fn dir(rel: impl Into<String>) -> Self {
        Self { rel: rel.into(), is_dir: true, etag: None, size: None, modified: None }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WebDavErrorKind {
    Auth,
    NotFound,
    Conflict,
    RateLimited,
    Server,
    Other,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WebDavError {
    pub kind: WebDavErrorKind,
    pub status: Option<u16>,
    pub message: String,
    pub retry_after: Option<Duration>,
}

impl WebDavError {
    pub fn new(kind: WebDavErrorKind, message: impl Into<String>) -> Self {
        Self { kind, status: None, message: message.into(), retry_after: None }
    }
    pub fn other(message: impl Into<String>) -> Self { Self::new(WebDavErrorKind::Other, message) }
    pub fn with_status(mut self, status: u16) -> Self { self.status = Some(status); self }
    pub fn with_retry(mut self, after: Option<Duration>) -> Self { self.retry_after = after; self }
}

impl fmt::Display for WebDavError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(&self.message) }
}

impl std::error::Error for WebDavError {}

pub type WebDavResult<T> = Result<T, WebDavError>;

pub trait WebDavApi: Send + Sync {
    /// Verify the configured remote root exists and is reachable.
    fn probe(&self) -> WebDavResult<()>;
    /// Create missing remote-root path segments; existing ones are left alone.
    fn ensure_root(&self) -> WebDavResult<()>;
    /// All entries below the remote root, folders included. Paths are relative
    /// to that root; the root itself is omitted.
    fn scan(&self) -> WebDavResult<Vec<RemoteEntry>>;
    fn get(&self, rel: &str) -> WebDavResult<Vec<u8>>;
    /// `None` creates only (`If-None-Match: *`); `Some("")` updates without a
    /// conditional header (the server gave no ETag); `Some(etag)` uses
    /// `If-Match`.
    fn put(&self, rel: &str, bytes: &[u8], expected_etag: Option<&str>) -> WebDavResult<RemoteEntry>;
    fn create_dir(&self, rel: &str) -> WebDavResult<()>;
    fn remove(&self, rel: &str, is_dir: bool, expected_etag: Option<&str>) -> WebDavResult<()>;
    fn move_entry(&self, from: &str, to: &str, is_dir: bool) -> WebDavResult<()>;
}

