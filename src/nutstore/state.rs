//! Local, per-root Nutstore WebDAV state. The document library never contains
//! credentials, ETags, cursors or conflict baselines.

use crate::sync_util::{BaseStore, quarantine, safe_relative, valid_name, write_atomic};
use anyhow::{Context as _, Result, bail};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

pub const STATE_VERSION: u32 = 1;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Config {
    pub server: String,
    pub username: String,
    pub remote_root: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileState {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub etag: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modified: Option<String>,
    #[serde(default)]
    pub sha256: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct State {
    #[serde(default = "state_version")]
    pub v: u32,
    #[serde(default)]
    pub server: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub remote_root: String,
    #[serde(default)]
    pub scan_complete: bool,
    #[serde(default)]
    pub files: BTreeMap<String, FileState>,
    #[serde(default)]
    pub folders: BTreeSet<String>,
}

fn state_version() -> u32 { STATE_VERSION }

impl Default for State {
    fn default() -> Self {
        Self { v: STATE_VERSION, server: String::new(), username: String::new(), remote_root: String::new(), scan_complete: false, files: BTreeMap::new(), folders: BTreeSet::new() }
    }
}

impl State {
    pub fn referenced_hashes(&self) -> BTreeSet<&str> {
        self.files.values().map(|f| f.sha256.as_str()).filter(|h| !h.is_empty()).collect()
    }
}

pub struct LoadedState {
    pub state: State,
    pub damaged: Option<PathBuf>,
}

pub struct StateStore {
    dir: PathBuf,
    bases: BaseStore,
}

impl StateStore {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        let dir = dir.into();
        Self { bases: BaseStore::new(&dir), dir }
    }

    pub fn dir(&self) -> &Path { &self.dir }
    pub fn state_path(&self) -> PathBuf { self.dir.join("state.json") }
    pub fn config_path(&self) -> PathBuf { self.dir.join("config.json") }
    pub fn bases_dir(&self) -> PathBuf { self.bases.bases_dir() }

    pub fn load_config(&self) -> Result<Config> {
        let path = self.config_path();
        match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes).with_context(|| format!("读取坚果云配置 {}", path.display())),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Config::default()),
            Err(e) => Err(e).with_context(|| format!("读取坚果云配置 {}", path.display())),
        }
    }

    pub fn save_config(&self, config: &Config) -> Result<()> {
        fs::create_dir_all(&self.dir)?;
        write_atomic(&self.config_path(), &serde_json::to_vec_pretty(config)?)
    }

    pub fn load(&self) -> Result<LoadedState> {
        let path = self.state_path();
        let bytes = match fs::read(&path) {
            Ok(bytes) => bytes,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(LoadedState { state: State::default(), damaged: None }),
            Err(e) => return Err(e).with_context(|| format!("读取坚果云同步状态 {}", path.display())),
        };
        match serde_json::from_slice::<State>(&bytes) {
            Ok(mut state) => {
                if state.v > STATE_VERSION { bail!("坚果云同步状态由更新版本的 Quiet.md 创建") }
                state.v = STATE_VERSION;
                Ok(LoadedState { state, damaged: None })
            }
            Err(_) => {
                fs::create_dir_all(&self.dir)?;
                let damaged = self.dir.join(format!("state.json.corrupt-{}", crate::time::now_ms()));
                let _ = fs::rename(&path, &damaged);
                Ok(LoadedState { state: State::default(), damaged: Some(damaged) })
            }
        }
    }

    pub fn save(&self, state: &State) -> Result<()> {
        fs::create_dir_all(&self.dir)?;
        let mut state = state.clone();
        state.v = STATE_VERSION;
        write_atomic(&self.state_path(), &serde_json::to_vec_pretty(&state)?)
    }

    pub fn base(&self, hash: &str) -> Result<Vec<u8>> { self.bases.base(hash) }
    pub fn put_base(&self, hash: &str, bytes: &[u8]) -> Result<()> { self.bases.put_base(hash, bytes) }

    pub fn prune_bases(&self, state: &State) -> Result<usize> {
        let live = state.referenced_hashes();
        let Ok(read) = fs::read_dir(self.bases_dir()) else { return Ok(0) };
        let mut removed = 0;
        for entry in read.flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if name.len() != 64 || !name.bytes().all(|b| b.is_ascii_hexdigit()) || live.contains(name.as_str()) { continue }
            if entry.file_type().is_ok_and(|t| t.is_file()) && fs::remove_file(entry.path()).is_ok() { removed += 1; }
        }
        Ok(removed)
    }

    pub fn quarantine(&self, root: &Path, rel: &str) -> Result<PathBuf> { quarantine(root, &self.dir, rel) }
}

/// Validate a user-entered remote folder. It is a WebDAV path below the
/// server root, not a URL and not an absolute filesystem path.
pub fn clean_remote_root(input: &str) -> Result<String> {
    let normalized = input.replace('\\', "/");
    let mut parts = Vec::new();
    for part in normalized.split('/') {
        let part = part.trim();
        if part.is_empty() || part == "." { continue }
        if part == ".." { bail!("远程文件夹不能包含 ..") }
        if !valid_name(part) { bail!("远程文件夹名无法安全使用：{part}") }
        parts.push(part);
    }
    if parts.is_empty() { bail!("请填写坚果云里的远程文件夹名") }
    Ok(parts.join("/"))
}

pub fn normalize_server(input: &str) -> Result<String> {
    let input = input.trim();
    let url = if input.is_empty() { "https://dav.jianguoyun.com/dav/" } else { input };
    if !(url.starts_with("https://") || url.starts_with("http://")) { bail!("WebDAV 地址必须以 http:// 或 https:// 开头") }
    if url.contains(['\r', '\n', ' ']) { bail!("WebDAV 地址格式不正确") }
    let authority = url.split_once("://").map(|(_, rest)| rest.split('/').next().unwrap_or("")).unwrap_or("");
    if authority.is_empty() { bail!("WebDAV 地址缺少服务器名称") }
    if authority.contains('@') { bail!("WebDAV 地址不能包含账号或密码；请分开填写") }
    if url.contains(['?', '#']) { bail!("WebDAV 地址不应包含查询参数或片段") }
    Ok(if url.ends_with('/') { url.to_string() } else { format!("{url}/") })
}

pub fn safe_remote_path(rel: &str) -> bool { rel.is_empty() || safe_relative(rel) }

