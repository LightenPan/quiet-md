//! Application-level Nutstore sync: settings status, connection, polling and
//! handing changed files back to open windows.

use crate::nutstore::client::NutstoreClient;
use crate::nutstore::credentials;
use crate::nutstore::engine::{InitialChoice, InitialPolicy, SyncEngine, SyncOptions, SyncOutcome};
use crate::nutstore::state::{Config, StateStore, clean_remote_root, normalize_server};
use crate::settings::tr;
use gpui::{App, AppContext as _, AsyncApp};
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, UNIX_EPOCH};

const POLL_ACTIVE: Duration = Duration::from_secs(5 * 60);
const POLL_IDLE: Duration = Duration::from_secs(15 * 60);
const MIN_AUTO_GAP: Duration = Duration::from_secs(120);
const SAVE_DELAY: Duration = Duration::from_secs(120);
const RETRY_DELAY: Duration = Duration::from_secs(5 * 60);

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SyncSummary {
    pub uploaded: usize,
    pub downloaded: usize,
    pub merged: usize,
    pub conflicts: usize,
    pub remote_deleted: usize,
    pub local_moved_to_recovery: usize,
    pub at: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Phase {
    SignedOut,
    Connecting,
    Idle,
    Syncing,
    Synced(SyncSummary),
    NeedsInitialChoice(InitialChoice),
    Error(String),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InitialAction { MergeBoth }

#[derive(Clone, Debug)]
pub struct RootStatus {
    pub root_name: String,
    pub configured: bool,
    pub connected: bool,
    pub enabled: bool,
    pub account: String,
    pub remote_root: String,
    pub last_sync: Option<u64>,
    pub phase: Phase,
    pub warnings: Vec<String>,
}

impl RootStatus {
    fn new(root_name: &str, configured: bool, connected: bool) -> Self {
        let phase = if connected { Phase::Idle } else { Phase::SignedOut };
        Self { root_name: root_name.into(), configured, connected, enabled: false, account: String::new(), remote_root: String::new(), last_sync: None, phase, warnings: vec![] }
    }
}

#[derive(Clone, Debug)]
struct RootInfo {
    key: String,
    name: String,
    path: PathBuf,
    error: Option<String>,
    cache: PathBuf,
    config: Config,
    has_password: bool,
    state_mtime: Option<u64>,
    enabled: bool,
}

struct Slot {
    status: RootStatus,
    busy: bool,
    next_policy: InitialPolicy,
    saved_at: Option<Instant>,
    retry_after: Option<Instant>,
    last_auto: Option<Instant>,
}

#[derive(Default)]
struct Manager {
    roots: BTreeMap<String, Slot>,
    epoch: u64,
}

thread_local! {
    static APP: RefCell<Option<AsyncApp>> = const { RefCell::new(None) };
    static MANAGER: RefCell<Manager> = RefCell::new(Manager::default());
}

static POLL_STARTED: AtomicBool = AtomicBool::new(false);

fn with_manager<R>(f: impl FnOnce(&mut Manager) -> R) -> R { MANAGER.with(|m| f(&mut m.borrow_mut())) }
fn refresh(cx: &mut App) { cx.refresh_windows(); }

pub fn init(cx: &mut App) {
    APP.with(|a| *a.borrow_mut() = Some(cx.to_async()));
    ensure_roots(cx);
    if POLL_STARTED.swap(true, Ordering::AcqRel) { return }
    let app = cx.to_async();
    app.spawn(async move |cx| {
        loop {
            cx.background_executor().timer(Duration::from_secs(60)).await;
            cx.update(poll);
        }
    })
    .detach();
}

fn cache_dir(key: &str) -> PathBuf {
    if let Some(dir) = std::env::var_os("QUIET_MD_NUTSTORE_DIR") { return PathBuf::from(dir).join(key) }
    dirs::data_local_dir().unwrap_or_else(fdl_core::vfs::temp_dir).join("quiet-md").join("nutstore").join(key)
}

fn may_read_credentials() -> bool {
    std::env::var_os("QUIET_MD_HOME").is_none() || std::env::var_os("QUIET_MD_NUTSTORE_PASSWORD_FILE").is_some()
}

pub fn status_for(cx: &App, root_key: &str) -> Option<RootStatus> {
    ensure_roots(cx);
    with_manager(|m| m.roots.get(root_key).map(|s| s.status.clone()))
}

pub fn config_for(cx: &App, root_key: &str) -> Option<Config> {
    let store = cx.try_global::<crate::shared_store::SharedStore>()?;
    store.roots().into_iter().find(|(k, _, _, _)| k == root_key)?;
    StateStore::new(cache_dir(root_key)).load_config().ok()
}

fn ensure_roots(cx: &App) {
    let Ok(store) = cx.try_global::<crate::shared_store::SharedStore>().ok_or(()) else { return };
    let known = with_manager(|m| m.roots.keys().cloned().collect::<BTreeSet<_>>());
    let mut infos = vec![];
    for (key, name, path, error) in store.roots() {
        if known.contains(&key) {
            infos.push(RootInfo { key, name, path, error, cache: PathBuf::new(), config: Config::default(), has_password: false, state_mtime: None, enabled: false });
            continue
        }
        let cache = cache_dir(&key);
        let state = StateStore::new(&cache);
        let config = state.load_config().unwrap_or_default();
        let has_password = may_read_credentials() && credentials::load(&key, &cache).ok().flatten().is_some();
        let state_path = state.state_path();
        let state_mtime = std::fs::metadata(&state_path).ok().and_then(|m| m.modified().ok()).and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs());
        let enabled = state.load().ok().is_some_and(|s| s.state.scan_complete);
        infos.push(RootInfo { key, name, path, error, cache, config, has_password, state_mtime, enabled });
    }
    with_manager(|m| {
        let keep: BTreeSet<&str> = infos.iter().map(|i| i.key.as_str()).collect();
        m.roots.retain(|k, _| keep.contains(k.as_str()));
        for info in infos {
            let entry = m.roots.entry(info.key.clone()).or_insert_with(|| {
                let configured = !info.config.server.is_empty() && info.has_password;
                Slot { status: RootStatus::new(&info.name, configured, configured), busy: false, next_policy: InitialPolicy::Ask, saved_at: None, retry_after: None, last_auto: None }
            });
            entry.status.root_name = info.name;
            if info.cache.as_os_str().is_empty() { continue }
            entry.status.configured = !info.config.server.is_empty() && info.has_password;
            entry.status.connected = entry.status.configured;
            entry.status.account = info.config.username.clone();
            entry.status.remote_root = info.config.remote_root.clone();
            entry.status.enabled = entry.status.connected && info.enabled;
            if entry.status.last_sync.is_none() { entry.status.last_sync = info.state_mtime; }
            if let Some(error) = info.error {
                entry.status.phase = Phase::Error(error);
            } else if matches!(entry.status.phase, Phase::SignedOut | Phase::Idle) {
                entry.status.phase = if entry.status.connected { Phase::Idle } else { Phase::SignedOut };
            }
        }
    });
}

fn root_info(cx: &App, key: &str) -> Result<RootInfo, String> {
    let store = cx.try_global::<crate::shared_store::SharedStore>().ok_or_else(|| tr("还没有打开任何文件夹", "No folder is open yet").to_string())?;
    let (_, name, path, error) = store.roots().into_iter().find(|(k, _, _, _)| k == key)
        .ok_or_else(|| tr("这个文件夹已经不在工作区里", "That folder is no longer in the workspace").to_string())?;
    if let Some(e) = error { return Err(e) }
    let cache = cache_dir(key);
    let state = StateStore::new(&cache);
    let config = state.load_config().unwrap_or_default();
    let has_password = may_read_credentials() && credentials::load(key, &cache).ok().flatten().is_some();
    let enabled = state.load().ok().is_some_and(|s| s.state.scan_complete);
    Ok(RootInfo { key: key.into(), name, path, error: None, cache, config, has_password, state_mtime: None, enabled })
}

pub fn connect(root_key: &str, config: Config, password: Option<String>, cx: &mut App) {
    let key = root_key.to_string();
    cx.defer(move |cx| start_connect(&key, config, password, cx));
}

fn start_connect(root_key: &str, config: Config, password: Option<String>, cx: &mut App) {
    ensure_roots(cx);
    let normalized = (|| -> Result<Config, String> {
        Ok(Config {
            server: normalize_server(&config.server).map_err(|e| e.to_string())?,
            username: config.username.trim().to_string(),
            remote_root: clean_remote_root(&config.remote_root).map_err(|e| e.to_string())?,
        })
    })();
    let config = match normalized {
        Ok(c) if !c.username.is_empty() => c,
        Ok(_) => { set_phase(root_key, Phase::Error(tr("请填写坚果云账号邮箱", "Enter your Nutstore account email").to_string())); refresh(cx); return }
        Err(e) => { set_phase(root_key, Phase::Error(e)); refresh(cx); return }
    };
    let info = match root_info(cx, root_key) {
        Ok(i) => i,
        Err(e) => { set_phase(root_key, Phase::Error(e)); refresh(cx); return }
    };
    if let Err(e) = StateStore::new(&info.cache).save_config(&config) {
        set_phase(root_key, Phase::Error(format!("无法保存坚果云配置：{e}")));
        refresh(cx);
        return
    }
    let password = match password.filter(|p| !p.is_empty()).or_else(|| credentials::load(root_key, &info.cache).ok().flatten()) {
        Some(p) => p,
        None => { set_phase(root_key, Phase::Error(tr("请填写坚果云第三方应用密码", "Enter the Nutstore app password").to_string())); refresh(cx); return }
    };
    if cx.try_global::<crate::shared_store::SharedStore>().is_some_and(|s| s.file_task_busy()) { return }
    if !flush_open_documents(cx) {
        set_phase(root_key, Phase::Error(tr("有文档没有保存成功，坚果云连接已暂停", "A document could not be saved, so the Nutstore connection is paused").to_string()));
        refresh(cx);
        return
    }
    let Some(_guard) = cx.try_global::<crate::shared_store::SharedStore>().and_then(|s| s.begin_file_task()) else { return };
    let busy = with_manager(|m| {
        let Some(slot) = m.roots.get_mut(root_key) else { return true };
        if slot.busy { return true }
        slot.busy = true;
        slot.status.phase = Phase::Connecting;
        slot.status.warnings.clear();
        false
    });
    if busy { return }
    refresh(cx);
    let Some(app) = APP.with(|a| a.borrow().clone()) else {
        with_manager(|m| if let Some(s) = m.roots.get_mut(root_key) { s.busy = false; });
        return
    };
    let epoch = with_manager(|m| m.epoch);
    let key = root_key.to_string();
    let policy = with_manager(|m| m.roots.get(root_key).map(|s| s.next_policy).unwrap_or(InitialPolicy::Ask));
    app.spawn(async move |cx| {
        let options = SyncOptions { local_root: info.path, cache_dir: info.cache.clone(), config: config.clone(), device_name: device_name() };
        let result = cx.background_spawn(async move { connect_and_sync(options, config, password, policy) }).await;
        drop(_guard);
        cx.update(|cx| finish_connect(&key, epoch, result, cx));
    })
    .detach();
}

fn connect_and_sync(options: SyncOptions, config: Config, password: String, policy: InitialPolicy) -> Result<(SyncOutcome, Config), String> {
    let client = NutstoreClient::connect_or_create(&config, &password)?;
    let root_key = options.cache_dir.file_name().and_then(|s| s.to_str()).unwrap_or_default().to_string();
    credentials::save(&root_key, &options.cache_dir, &password)?;
    let store = StateStore::new(&options.cache_dir);
    let old = store.load().map_err(|e| e.to_string())?;
    if !old.state.server.is_empty()
        && (old.state.server != config.server || old.state.username != config.username || old.state.remote_root != config.remote_root) {
        let _ = std::fs::rename(store.state_path(), store.dir().join(format!("state.json.reset-{}", crate::time::now_ms())));
    }
    store.save_config(&config).map_err(|e| e.to_string())?;
    let mut engine = SyncEngine::open(SyncOptions { config: config.clone(), ..options }).map_err(|e| e.to_string())?;
    let outcome = engine.sync(&client, policy).map_err(|e| e.to_string())?;
    Ok((outcome, config))
}

fn finish_connect(root_key: &str, epoch: u64, result: Result<(SyncOutcome, Config), String>, cx: &mut App) {
    let stale = with_manager(|m| {
        if m.epoch == epoch { return false }
        if let Some(slot) = m.roots.get_mut(root_key) { slot.busy = false; }
        true
    });
    if stale { refresh(cx); return }
    match result {
        Ok((outcome, config)) => {
            with_manager(|m| {
                let Some(slot) = m.roots.get_mut(root_key) else { return };
                slot.busy = false;
                slot.status.configured = true;
                slot.status.connected = true;
                slot.status.account = config.username;
                slot.status.remote_root = config.remote_root;
                apply_outcome(slot, outcome);
            });
        }
        Err(error) => {
            with_manager(|m| {
                let Some(slot) = m.roots.get_mut(root_key) else { return };
                slot.busy = false;
                slot.status.phase = Phase::Error(readable(error));
                slot.retry_after = Some(Instant::now() + RETRY_DELAY);
            });
        }
    }
    refresh(cx);
}

pub fn sync_now(root_key: &str, cx: &mut App) {
    let key = root_key.to_string();
    cx.defer(move |cx| start_sync(&key, cx));
}

fn start_sync(root_key: &str, cx: &mut App) {
    ensure_roots(cx);
    let Some(info) = root_info(cx, root_key).ok() else { return };
    let (connected, busy) = with_manager(|m| m.roots.get(root_key).map(|s| (s.status.connected, s.busy)).unwrap_or((false, true)));
    if !connected || busy { return }
    let password = match credentials::load(root_key, &info.cache) {
        Ok(Some(p)) => p,
        _ => { set_phase(root_key, Phase::SignedOut); refresh(cx); return }
    };
    if cx.try_global::<crate::shared_store::SharedStore>().is_some_and(|s| s.file_task_busy()) { return }
    if !flush_open_documents(cx) {
        set_phase(root_key, Phase::Error(tr("有文档没有保存成功，坚果云同步已暂停", "A document could not be saved, so Nutstore sync is paused").to_string()));
        refresh(cx);
        return
    }
    let Some(guard) = cx.try_global::<crate::shared_store::SharedStore>().and_then(|s| s.begin_file_task()) else { return };
    let policy = with_manager(|m| {
        let slot = m.roots.get_mut(root_key).unwrap();
        slot.busy = true;
        slot.retry_after = None;
        slot.last_auto = Some(Instant::now());
        slot.status.warnings.clear();
        slot.status.phase = Phase::Syncing;
        slot.next_policy
    });
    refresh(cx);
    let Some(app) = APP.with(|a| a.borrow().clone()) else {
        with_manager(|m| if let Some(s) = m.roots.get_mut(root_key) { s.busy = false; });
        return
    };
    let epoch = with_manager(|m| m.epoch);
    let key = root_key.to_string();
    app.spawn(async move |cx| {
        let options = SyncOptions { local_root: info.path, cache_dir: info.cache, config: info.config, device_name: device_name() };
        let result = cx.background_spawn(async move { run_sync(options, password, policy) }).await;
        drop(guard);
        cx.update(|cx| finish_sync(&key, epoch, result, cx));
    })
    .detach();
}

fn run_sync(options: SyncOptions, password: String, policy: InitialPolicy) -> Result<(SyncOutcome, String), String> {
    let client = NutstoreClient::connect(&options.config, &password)?;
    let mut engine = SyncEngine::open(options).map_err(|e| e.to_string())?;
    let outcome = engine.sync(&client, policy).map_err(|e| e.to_string())?;
    Ok((outcome, engine.state().username.clone()))
}

fn finish_sync(root_key: &str, epoch: u64, result: Result<(SyncOutcome, String), String>, cx: &mut App) {
    let mut changed = None;
    with_manager(|m| {
        if m.epoch != epoch {
            if let Some(slot) = m.roots.get_mut(root_key) { slot.busy = false; }
            return
        }
        let Some(slot) = m.roots.get_mut(root_key) else { return };
        slot.busy = false;
        slot.next_policy = InitialPolicy::Ask;
        match result {
            Ok((outcome, account)) => {
                slot.status.account = account;
                if let SyncOutcome::Synced(report) = outcome {
                    changed = Some(report.changed_paths.clone());
                    apply_outcome(slot, SyncOutcome::Synced(report));
                } else {
                    apply_outcome(slot, outcome);
                }
            }
            Err(error) => {
                let error = readable(error);
                if auth_lost(&error) {
                    m.epoch = m.epoch.wrapping_add(1);
                    slot.status.phase = Phase::SignedOut;
                    slot.status.connected = false;
                    slot.status.enabled = false;
                }
                else { slot.status.phase = Phase::Error(error); }
                slot.retry_after = Some(Instant::now() + RETRY_DELAY);
            }
        }
    });
    if let Some(paths) = changed {
        let handles: Vec<_> = cx.windows().into_iter().filter_map(|w| w.downcast::<crate::app::Workspace>()).collect();
        for handle in handles { let _ = handle.update(cx, |ws, _, cx| ws.after_remote_sync(root_key, &paths, cx)); }
    }
    refresh(cx);
}

fn apply_outcome(slot: &mut Slot, outcome: SyncOutcome) {
    match outcome {
        SyncOutcome::Synced(report) => {
            let summary = SyncSummary {
                uploaded: report.uploaded, downloaded: report.downloaded, merged: report.merged, conflicts: report.conflicts,
                remote_deleted: report.remote_deleted, local_moved_to_recovery: report.local_moved_to_recovery, at: crate::time::now(),
            };
            slot.status.enabled = true;
            slot.status.last_sync = Some(summary.at);
            slot.status.phase = Phase::Synced(summary);
            slot.status.warnings = report.warnings;
        }
        SyncOutcome::NeedsInitialChoice(choice) => {
            slot.status.enabled = false;
            slot.status.warnings.clear();
            slot.status.phase = Phase::NeedsInitialChoice(choice);
        }
    }
}

pub fn choose_initial(root_key: &str, action: InitialAction, cx: &mut App) {
    with_manager(|m| if let Some(slot) = m.roots.get_mut(root_key) { slot.next_policy = match action { InitialAction::MergeBoth => InitialPolicy::MergeBoth }; });
    sync_now(root_key, cx);
}

pub fn merge_both(root_key: &str, cx: &mut App) { choose_initial(root_key, InitialAction::MergeBoth, cx); }

pub fn logout(cx: &mut App) {
    let keys: Vec<(String, PathBuf)> = with_manager(|m| m.roots.keys().map(|k| (k.clone(), cache_dir(k))).collect());
    let mut error = None;
    for (key, cache) in keys {
        if let Err(e) = credentials::delete(&key, &cache) { error = Some(e) }
    }
    with_manager(|m| {
        m.epoch = m.epoch.wrapping_add(1);
        for slot in m.roots.values_mut() {
            slot.status.connected = false;
            slot.status.enabled = false;
            slot.status.account.clear();
            slot.status.warnings.clear();
            slot.status.phase = error.as_ref().map_or(Phase::SignedOut, |e| Phase::Error(readable(e.clone())));
        }
    });
    refresh(cx);
}

pub fn reset_root(root_key: &str, cx: &mut App) {
    let busy = with_manager(|m| m.roots.get(root_key).is_some_and(|s| s.busy));
    if busy { return }
    let path = StateStore::new(cache_dir(root_key)).state_path();
    if path.is_file() {
        let aside = path.with_file_name(format!("state.json.reset-{}", crate::time::now_ms()));
        if let Err(e) = std::fs::rename(&path, aside) {
            set_phase(root_key, Phase::Error(format!("无法重置坚果云状态：{e}")));
            refresh(cx);
            return
        }
    }
    with_manager(|m| if let Some(slot) = m.roots.get_mut(root_key) {
        slot.status.enabled = false;
        slot.status.last_sync = None;
        slot.status.warnings.clear();
        slot.saved_at = None;
        slot.retry_after = None;
        slot.status.phase = if slot.status.connected { Phase::Idle } else { Phase::SignedOut };
    });
    refresh(cx);
}

pub fn on_window_activated(cx: &mut App) {
    ensure_roots(cx);
    let active = cx.try_global::<crate::shared_store::SharedStore>().and_then(|s| s.active_root());
    let Some(key) = active else { return };
    let due = with_manager(|m| m.roots.get(&key).is_some_and(|s| {
        s.status.enabled && !s.busy && s.retry_after.is_none_or(|t| Instant::now() >= t)
            && s.last_auto.is_none_or(|t| t.elapsed() >= MIN_AUTO_GAP)
    }));
    if due { sync_now(&key, cx); }
}

pub fn on_saved(root_key: &str, cx: &mut App) {
    if !with_manager(|m| m.roots.contains_key(root_key)) { ensure_roots(cx); }
    let enabled = with_manager(|m| {
        let Some(slot) = m.roots.get_mut(root_key) else { return false };
        if !slot.status.enabled { return false }
        slot.saved_at = Some(Instant::now());
        true
    });
    if !enabled { return }
    let Some(app) = APP.with(|a| a.borrow().clone()) else { return };
    let key = root_key.to_string();
    app.spawn(async move |cx| {
        cx.background_executor().timer(SAVE_DELAY).await;
        cx.update(|cx| sync_if_saved(&key, cx));
    })
    .detach();
}

fn sync_if_saved(root_key: &str, cx: &mut App) {
    let due = with_manager(|m| m.roots.get(root_key).is_some_and(|s| s.saved_at.is_some_and(|t| t.elapsed() >= SAVE_DELAY)));
    if due { sync_now(root_key, cx); }
}

fn poll(cx: &mut App) {
    ensure_roots(cx);
    let active = cx.windows().into_iter().filter_map(|w| w.downcast::<crate::app::Workspace>()).any(|w| w.is_active(cx).unwrap_or(false));
    let interval = if active { POLL_ACTIVE } else { POLL_IDLE };
    let due: Vec<String> = with_manager(|m| {
        let now = Instant::now();
        m.roots.iter().filter(|(_, s)| {
            s.status.enabled && !s.busy && s.retry_after.is_none_or(|t| now >= t)
                && s.last_auto.is_none_or(|t| t.elapsed() >= MIN_AUTO_GAP)
                && s.status.last_sync.is_none_or(|last| crate::time::now().saturating_sub(last) >= interval.as_secs())
        }).map(|(k, _)| k.clone()).collect()
    });
    for key in due { sync_now(&key, cx); }
}

fn flush_open_documents(cx: &mut App) -> bool {
    let handles: Vec<_> = cx.windows().into_iter().filter_map(|w| w.downcast::<crate::app::Workspace>()).collect();
    let mut saved = true;
    for handle in handles { if !handle.update(cx, |ws, _, cx| ws.flush(cx)).unwrap_or(false) { saved = false; } }
    saved
}

fn set_phase(root_key: &str, phase: Phase) {
    with_manager(|m| if let Some(slot) = m.roots.get_mut(root_key) { slot.status.phase = phase; });
}

fn device_name() -> String {
    std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME"))
        .ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
        .unwrap_or_else(|| tr("这台电脑", "This computer").to_string())
}

fn readable(error: impl Into<String>) -> String {
    let error = error.into();
    if error.contains("401") || error.to_ascii_lowercase().contains("unauthorized") {
        tr("坚果云账号或应用密码不正确，请重新连接", "The Nutstore email or app password is incorrect; connect again").to_string()
    } else if error.to_ascii_lowercase().contains("timeout") || error.contains("curl:") {
        tr("无法连接坚果云，请检查网络和 WebDAV 地址", "Could not reach Nutstore; check the network and WebDAV address").to_string()
    } else { error }
}

fn auth_lost(error: &str) -> bool { error.contains("应用密码不正确") || error.contains("incorrect") || error.contains("Unauthorized") }

pub fn summary_text(summary: &SyncSummary) -> String {
    let mut parts = Vec::new();
    if summary.uploaded > 0 { parts.push(crate::localized_format!("上传 {}", "uploaded {}", summary.uploaded)); }
    if summary.downloaded > 0 { parts.push(crate::localized_format!("下载 {}", "downloaded {}", summary.downloaded)); }
    if summary.merged > 0 { parts.push(crate::localized_format!("合并 {}", "merged {}", summary.merged)); }
    if summary.conflicts > 0 { parts.push(crate::localized_format!("冲突副本 {}", "conflict copies {}", summary.conflicts)); }
    if parts.is_empty() { tr("没有变化", "No changes").to_string() } else { parts.join(" · ") }
}

pub fn last_sync_text(at: Option<u64>) -> String {
    let Some(at) = at else { return tr("尚未同步", "Not synced yet").to_string() };
    let (_, _, _, hour, minute) = fdl_core::time::civil(at);
    format!("{hour:02}:{minute:02}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summaries_and_phases_are_stable() {
        let summary = SyncSummary { uploaded: 2, downloaded: 1, merged: 3, conflicts: 1, ..Default::default() };
        let text = summary_text(&summary);
        assert!(text.contains('2') && text.contains('1') && text.contains('3'));
        assert_eq!(RootStatus::new("Notes", true, false).phase, Phase::SignedOut);
        assert_eq!(last_sync_text(None), tr("尚未同步", "Not synced yet"));
    }

    #[test]
    fn server_and_remote_folder_are_normalized() {
        assert_eq!(normalize_server("https://dav.jianguoyun.com/dav").unwrap(), "https://dav.jianguoyun.com/dav/");
        assert!(normalize_server("https://user:pass@dav.example/dav/").is_err());
        assert_eq!(clean_remote_root(r"Quiet.md\Notes").unwrap(), "Quiet.md/Notes");
        assert!(clean_remote_root("../Notes").is_err());
    }
}

