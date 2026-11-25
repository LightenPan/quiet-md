//! Path-based Nutstore WebDAV sync. Remote identity is the NFC relative path;
//! ETags guard writes, while content hashes and baseline snapshots drive
//! three-way merges. No GPUI and no HTTP here.

use crate::nutstore::api::{RemoteEntry, WebDavApi};
use crate::nutstore::state::{Config, FileState, State, StateStore};
use crate::sync_util::{
    self, LocalScan, MAX_BYTES, WARN_BYTES, base_name, fresh_conflict_path, is_synced_file,
    is_text_document, join, merge_bytes, nfc_path, parent_rel, path_key, read_limited, sha256,
    valid_name,
};
use anyhow::{Context as _, Result, bail};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Debug)]
pub struct SyncOptions {
    pub local_root: PathBuf,
    /// `quiet-md/nutstore/<root-key>`, beside the library.
    pub cache_dir: PathBuf,
    pub config: Config,
    pub device_name: String,
    /// Shared with the window's file tasks; held only around local writes.
    pub local_lock: Option<Arc<AtomicBool>>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum InitialPolicy {
    #[default]
    Ask,
    UploadLocal,
    DownloadRemote,
    MergeBoth,
}

#[derive(Clone, Debug, Default)]
pub struct SyncReport {
    pub uploaded: usize,
    pub downloaded: usize,
    pub merged: usize,
    pub conflicts: usize,
    pub remote_deleted: usize,
    pub local_moved_to_recovery: usize,
    pub changed_paths: BTreeSet<String>,
    pub warnings: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InitialChoice {
    pub local_files: usize,
    pub remote_files: usize,
}

#[derive(Clone, Debug)]
pub enum SyncOutcome {
    Synced(SyncReport),
    NeedsInitialChoice(InitialChoice),
}

pub struct SyncEngine {
    options: SyncOptions,
    store: StateStore,
    state: State,
    load_warning: Option<String>,
    unsaved: u32,
    lock_depth: u32,
}

struct LocalLockGuard(Arc<AtomicBool>);
impl Drop for LocalLockGuard {
    fn drop(&mut self) { self.0.store(false, Ordering::Release); }
}

impl SyncEngine {
    pub fn open(options: SyncOptions) -> Result<Self> {
        if !options.local_root.is_dir() { bail!("同步的本地文件夹不存在：{}", options.local_root.display()) }
        let store = StateStore::new(&options.cache_dir);
        let loaded = store.load()?;
        Ok(Self {
            options,
            store,
            state: loaded.state,
            load_warning: loaded.damaged.map(|p| format!("坚果云同步状态已损坏，旧文件保留在 {}", p.display())),
            unsaved: 0,
            lock_depth: 0,
        })
    }

    pub fn state(&self) -> &State { &self.state }

    /// Runs `f` while the window's file tasks are held off. Network waits and
    /// uploads stay outside: a first sync can take hours and the window must
    /// keep working for all of them. Nested calls reuse the outer hold.
    fn with_local_lock<T>(&mut self, f: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        if self.lock_depth > 0 { return f(self) }
        let Some(flag) = self.options.local_lock.clone() else { return f(self) };
        while flag.compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed).is_err() {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        let guard = LocalLockGuard(flag);
        self.lock_depth = 1;
        let result = f(self);
        self.lock_depth = 0;
        drop(guard);
        result
    }
    pub fn sync(&mut self, api: &dyn WebDavApi, policy: InitialPolicy) -> Result<SyncOutcome> {
        let result = self.sync_inner(api, policy);
        // Keep what this run already uploaded or downloaded: the next attempt
        // continues it instead of starting the whole transfer again.
        if result.is_err() { let _ = self.store.save(&self.state); }
        result
    }
    fn sync_inner(&mut self, api: &dyn WebDavApi, policy: InitialPolicy) -> Result<SyncOutcome> {
        api.probe()?;
        let config = &self.options.config;
        if !self.state.server.is_empty()
            && (self.state.server != config.server || self.state.username != config.username || self.state.remote_root != config.remote_root) {
            bail!("坚果云账号或远程文件夹已经改变；请先重置这个文件夹的坚果云同步")
        }
        self.state.server = config.server.clone();
        self.state.username = config.username.clone();
        self.state.remote_root = config.remote_root.clone();
        let mut report = SyncReport { warnings: self.load_warning.take().into_iter().collect(), ..Default::default() };
        let remote = scan_remote(api)?;
        let local = sync_util::scan_local(&self.options.local_root)?;
        report.warnings.extend(remote.warnings.iter().cloned());
        report.warnings.extend(local.warnings.iter().cloned());
        if self.state.scan_complete && remote.files.is_empty() && !self.state.files.is_empty() && !local.files.is_empty() {
            bail!("坚果云远程文件夹突然为空；为保护本地文件已暂停同步。如果确实要清空，请先备份本地文件，再在设置中重新扫描")
        }
        if !self.state.scan_complete {
            if let Some(outcome) = self.initial_sync(api, &remote, &local, policy, &mut report)? {
                self.store.save(&self.state)?;
                return Ok(outcome);
            }
        } else {
            self.reconcile(api, &remote, &local, &mut report)?;
            let after = sync_util::scan_local(&self.options.local_root)?;
            report.warnings.extend(after.warnings.iter().cloned());
            self.push_local(api, &after, &mut report)?;
        }
        self.state.scan_complete = true;
        self.store.save(&self.state)?;
        if let Err(e) = self.store.prune_bases(&self.state) { report.warnings.push(format!("清理旧同步基准失败：{e}")); }
        Ok(SyncOutcome::Synced(report))
    }

    fn initial_sync(&mut self, api: &dyn WebDavApi, remote: &RemoteTree, local: &LocalScan, policy: InitialPolicy, report: &mut SyncReport) -> Result<Option<SyncOutcome>> {
        let remote_has = !remote.files.is_empty();
        let local_has = !local.files.is_empty();
        if remote_has && local_has && policy == InitialPolicy::Ask {
            return Ok(Some(SyncOutcome::NeedsInitialChoice(InitialChoice { local_files: local.files.len(), remote_files: remote.files.len() })));
        }
        if policy == InitialPolicy::UploadLocal && remote_has { bail!("坚果云远程文件夹已有文件；若要保留两边，请选择合并两边") }
        if policy == InitialPolicy::DownloadRemote && local_has { bail!("本地文件夹已有文件；若要保留两边，请选择合并两边") }

        self.state.folders = remote.dirs.clone();
        // The scan is complete before the transfer starts, so an interrupted
        // first sync resumes through reconcile instead of asking to merge again.
        self.state.scan_complete = true;
        self.store.save(&self.state)?;

        if remote_has && local_has && policy == InitialPolicy::MergeBoth {
            for (rel, entry) in &remote.files {
                let bytes = api.get(rel)?;
                let path = join(&self.options.local_root, rel)?;
                if !path.is_file() {
                    self.write_download(entry, rel, &bytes, report)?;
                    continue;
                }
                let ours = read_limited(&path, MAX_BYTES)?;
                let remote_sha = sha256(&bytes);
                if sha256(&ours) == remote_sha {
                    self.store.put_base(&remote_sha, &bytes)?;
                    self.remember(rel, entry, remote_sha)?;
                } else {
                    let known = self.state.files.keys().cloned().collect::<Vec<_>>();
                    let conflict = fresh_conflict_path(&self.options.local_root, rel, &self.options.device_name, known.iter())?;
                    let conflict_path = join(&self.options.local_root, &conflict)?;
                    self.with_local_lock(|_| sync_util::write_atomic(&conflict_path, &bytes))?;
                    self.upload_new(api, &conflict, &bytes, report)?;
                    self.remember_without_base(rel, entry)?;
                    report.conflicts += 1;
                    report.changed_paths.insert(conflict);
                }
            }
            let after = sync_util::scan_local(&self.options.local_root)?;
            self.push_local(api, &after, report)?;
            return Ok(None)
        }

        for (rel, entry) in &remote.files {
            if remote_has && !local_has {
                let bytes = api.get(rel)?;
                self.write_download(entry, rel, &bytes, report)?;
            }
        }
        if local_has && (!remote_has || policy == InitialPolicy::UploadLocal) {
            self.upload_missing_local(api, local, report)?;
        }
        Ok(None)
    }

    fn reconcile(&mut self, api: &dyn WebDavApi, remote: &RemoteTree, local: &LocalScan, report: &mut SyncReport) -> Result<()> {
        let old_dirs = self.state.folders.clone();
        let old_paths: BTreeSet<String> = self.state.files.keys().cloned().collect();
        let remote_moves = self.remote_moves(remote, local);
        for (old, new) in &remote_moves {
            if let (Some(path), Some(entry)) = (local.files.get(old), remote.files.get(new)) {
                let old_abs = join(&self.options.local_root, old)?;
                let new_abs = join(&self.options.local_root, new)?;
                self.with_local_lock(|e| {
                    if new_abs.exists() { bail!("坚果云改名后的目标位置已有本机文件，暂停同步：{new}") }
                    if path.sha256 == e.state.files.get(old).map(|s| s.sha256.as_str()).unwrap_or("") {
                        if let Some(parent) = new_abs.parent() { fs::create_dir_all(parent)?; }
                        fs::rename(&old_abs, &new_abs).with_context(|| format!("移动 {} 到 {}", old_abs.display(), new_abs.display()))?;
                        if let Some(state) = e.state.files.remove(old) {
                            e.state.files.insert(new.clone(), FileState {
                                etag: entry.etag.clone(), size: entry.size, modified: entry.modified.clone(), sha256: state.sha256,
                            });
                        }
                        report.changed_paths.insert(old.clone());
                        report.changed_paths.insert(new.clone());
                    }
                    Ok(())
                })?;
            }
        }
        self.state.folders = remote.dirs.clone();

        for (rel, entry) in &remote.files {
            if remote_moves.values().any(|new| new == rel) { continue }
            self.apply_remote(api, rel, entry, report)?;
        }

        let moved_old: BTreeSet<String> = remote_moves.keys().cloned().collect();
        for rel in old_paths {
            if remote.files.contains_key(&rel) || moved_old.contains(&rel) { continue }
            self.drop_remote(&rel, report)?;
        }

        self.cleanup_dirs(&old_dirs)?;
        Ok(())
    }

    fn remote_moves(&self, remote: &RemoteTree, local: &LocalScan) -> BTreeMap<String, String> {
        let old_missing: Vec<(&String, &FileState)> = self.state.files.iter().filter(|(p, _)| !remote.files.contains_key(*p)).collect();
        let new_remote: Vec<(&String, &RemoteEntry)> = remote.files.iter().filter(|(p, _)| !self.state.files.contains_key(*p)).collect();
        let mut old_sig: BTreeMap<String, Vec<&String>> = BTreeMap::new();
        let mut new_sig: BTreeMap<String, Vec<&String>> = BTreeMap::new();
        for (rel, state) in old_missing {
            if local.files.get(rel.as_str()).is_some_and(|l| l.sha256 == state.sha256) {
                old_sig.entry(remote_sig_state(state)).or_default().push(rel);
            }
        }
        for (rel, entry) in new_remote {
            new_sig.entry(remote_sig(entry)).or_default().push(rel);
        }
        let mut out = BTreeMap::new();
        for (sig, olds) in old_sig {
            if olds.len() != 1 { continue }
            if let Some(news) = new_sig.get(&sig) && news.len() == 1 {
                out.insert(olds[0].clone(), news[0].clone());
            }
        }
        out
    }

    fn apply_remote(&mut self, api: &dyn WebDavApi, rel: &str, entry: &RemoteEntry, report: &mut SyncReport) -> Result<()> {
        if entry.size.is_some_and(|s| s > MAX_BYTES) {
            report.warnings.push(format!("{rel} 超过 100 MB，未下载"));
            return Ok(())
        }
        let old = self.state.files.get(rel).cloned();
        if old.is_some() && !remote_changed(old.as_ref().unwrap(), entry) {
            if let Some(old) = old {
                self.remember(rel, entry, old.sha256)?;
            }
            return Ok(())
        }
        let bytes = api.get(rel)?;
        self.with_local_lock(|e| e.apply_remote_bytes(api, rel, entry, &bytes, old, report))
    }

    fn apply_remote_bytes(&mut self, api: &dyn WebDavApi, rel: &str, entry: &RemoteEntry, bytes: &[u8], old: Option<FileState>, report: &mut SyncReport) -> Result<()> {
        let path = join(&self.options.local_root, rel)?;
        let remote_sha = sha256(bytes);
        if !path.is_file() {
            self.write_download(entry, rel, bytes, report)?;
            return Ok(())
        }
        let ours = read_limited(&path, MAX_BYTES)?;
        if sha256(&ours) == remote_sha {
            self.store.put_base(&remote_sha, bytes)?;
            self.remember(rel, entry, remote_sha)?;
            return Ok(())
        }
        let Some(old) = old else {
            let known = self.state.files.keys().cloned().collect::<Vec<_>>();
            let conflict = fresh_conflict_path(&self.options.local_root, rel, &self.options.device_name, known.iter())?;
            let conflict_path = join(&self.options.local_root, &conflict)?;
            sync_util::write_atomic(&conflict_path, bytes)?;
            if let Err(e) = self.upload_new(api, &conflict, bytes, report) {
                report.warnings.push(format!("{conflict} 只保存在本机：{e}"));
            }
            self.remember_without_base(rel, entry)?;
            report.changed_paths.insert(conflict);
            report.conflicts += 1;
            return Ok(())
        };
        let local_changed = sha256(&ours) != old.sha256;
        if !local_changed {
            self.write_download(entry, rel, bytes, report)?;
            return Ok(())
        }
        let base = self.store.base(&old.sha256).ok();
        if is_text_document(rel)
            && let Some(base) = base {
            self.merge_remote(api, rel, entry, &base, &ours, bytes, report)?;
            return Ok(())
        }
        let known = self.state.files.keys().cloned().collect::<Vec<_>>();
        let conflict = fresh_conflict_path(&self.options.local_root, rel, &self.options.device_name, known.iter())?;
        let conflict_path = join(&self.options.local_root, &conflict)?;
        sync_util::write_atomic(&conflict_path, bytes)?;
        if let Err(e) = self.upload_new(api, &conflict, bytes, report) {
            report.warnings.push(format!("{conflict} 只保存在本机：{e}"));
        }
        self.remember_without_base(rel, entry)?;
        report.changed_paths.insert(conflict);
        report.conflicts += 1;
        Ok(())
    }

    fn write_download(&mut self, entry: &RemoteEntry, rel: &str, bytes: &[u8], report: &mut SyncReport) -> Result<()> {
        self.with_local_lock(|e| {
            let path = join(&e.options.local_root, rel)?;
            sync_util::write_atomic(&path, bytes)?;
            let hash = sha256(bytes);
            e.store.put_base(&hash, bytes)?;
            e.remember(rel, entry, hash)?;
            report.downloaded += 1;
            report.changed_paths.insert(rel.to_string());
            Ok(())
        })
    }

    fn merge_remote(&mut self, api: &dyn WebDavApi, rel: &str, entry: &RemoteEntry, base: &[u8], ours: &[u8], theirs: &[u8], report: &mut SyncReport) -> Result<()> {
        let merged = match merge_bytes(rel, base, ours, theirs, &self.options.local_root) {
            Ok(m) => m,
            Err(e) => {
                report.warnings.push(format!("{rel} 无法做文档合并，保留两边为冲突副本：{e}"));
                sync_util::Merged { bytes: ours.to_vec(), conflict_bytes: Some(theirs.to_vec()) }
            }
        };
        let path = join(&self.options.local_root, rel)?;
        sync_util::write_atomic(&path, &merged.bytes)?;
        let hash = sha256(&merged.bytes);
        self.store.put_base(&hash, &merged.bytes)?;
        let updated = api.put(rel, &merged.bytes, entry.etag.as_deref())?;
        self.remember(rel, &updated, hash)?;
        report.merged += 1;
        report.changed_paths.insert(rel.to_string());
        if let Some(theirs) = merged.conflict_bytes {
            let known = self.state.files.keys().cloned().collect::<Vec<_>>();
            let conflict = fresh_conflict_path(&self.options.local_root, rel, &self.options.device_name, known.iter())?;
            let conflict_path = join(&self.options.local_root, &conflict)?;
            sync_util::write_atomic(&conflict_path, &theirs)?;
            if let Err(e) = self.upload_new(api, &conflict, &theirs, report) {
                report.warnings.push(format!("{conflict} 只保存在本机：{e}"));
            }
            report.changed_paths.insert(conflict);
            report.conflicts += 1;
        }
        Ok(())
    }

    fn push_local(&mut self, api: &dyn WebDavApi, local: &LocalScan, report: &mut SyncReport) -> Result<()> {
        let known: BTreeMap<String, String> = self.state.files.keys().map(|rel| (path_key(rel), rel.clone())).collect();
        let mut handled = BTreeSet::new();
        let missing: Vec<String> = self.state.files.keys().filter(|rel| !local.files.contains_key(*rel)).cloned().collect();
        for old in missing {
            let Some(state) = self.state.files.get(&old).cloned() else { continue };
            let Some((new, info)) = local.files.iter().find(|(p, info)| {
                !known.contains_key(&path_key(p)) && !handled.contains(*p) && info.sha256 == state.sha256
            }) else { continue };
            self.ensure_remote_dir(api, &parent_rel(new))?;
            api.move_entry(&old, new, false)?;
            self.state.files.remove(&old);
            self.state.files.insert(new.clone(), FileState {
                etag: state.etag.clone(), size: Some(info.size), modified: state.modified.clone(), sha256: info.sha256.clone(),
            });
            handled.insert(new.clone());
            report.changed_paths.insert(old);
            report.changed_paths.insert(new.clone());
        }

        let mut new_files: Vec<&String> = local.files.keys()
            .filter(|p| !known.contains_key(&path_key(p)) && !handled.contains(*p)).collect();
        new_files.sort();
        for rel in new_files {
            let info = &local.files[rel];
            if info.size > MAX_BYTES { report.warnings.push(format!("{rel} 超过 100 MB，未上传")); continue }
            if info.size > WARN_BYTES { report.warnings.push(format!("{rel} 超过 50 MB，上传可能较慢")); }
            self.upload_existing(api, rel, None, report)?;
        }

        let changed: Vec<(String, FileState)> = self.state.files.iter()
            .filter_map(|(rel, s)| {
                let local = local.files.get(rel)?;
                (local.sha256 != s.sha256).then(|| (rel.clone(), s.clone()))
            }).collect();
        for (rel, _) in changed {
            if local.files.get(&rel).is_some_and(|f| f.size <= MAX_BYTES) {
                self.upload_existing(api, &rel, Some(&rel), report)?;
            }
        }

        let gone: Vec<String> = self.state.files.keys()
            .filter(|rel| !local.files.contains_key(*rel) && !handled.contains(*rel)).cloned().collect();
        for rel in gone {
            let etag = self.state.files.get(&rel).and_then(|s| s.etag.as_deref());
            api.remove(&rel, false, etag)?;
            self.state.files.remove(&rel);
            report.remote_deleted += 1;
        }
        Ok(())
    }

    fn upload_missing_local(&mut self, api: &dyn WebDavApi, local: &LocalScan, report: &mut SyncReport) -> Result<()> {
        let mut files: Vec<&String> = local.files.keys().filter(|p| !self.state.files.contains_key(*p)).collect();
        files.sort();
        for rel in files {
            let info = &local.files[rel];
            if info.size > MAX_BYTES { report.warnings.push(format!("{rel} 超过 100 MB，未上传")); continue }
            if info.size > WARN_BYTES { report.warnings.push(format!("{rel} 超过 50 MB，上传可能较慢")); }
            self.upload_existing(api, rel, None, report)?;
        }
        Ok(())
    }

    fn upload_existing(&mut self, api: &dyn WebDavApi, rel: &str, existing: Option<&str>, report: &mut SyncReport) -> Result<()> {
        let path = join(&self.options.local_root, rel)?;
        let bytes = read_limited(&path, MAX_BYTES)?;
        let hash = sha256(&bytes);
        self.ensure_remote_dir(api, &parent_rel(rel))?;
        let expected = existing.map(|_| self.state.files.get(rel).and_then(|s| s.etag.as_deref()).unwrap_or(""));
        let entry = api.put(rel, &bytes, expected)?;
        self.store.put_base(&hash, &bytes)?;
        self.remember(rel, &entry, hash)?;
        report.uploaded += 1;
        Ok(())
    }

    fn upload_new(&mut self, api: &dyn WebDavApi, rel: &str, bytes: &[u8], report: &mut SyncReport) -> Result<()> {
        self.ensure_remote_dir(api, &parent_rel(rel))?;
        let entry = api.put(rel, bytes, None)?;
        let hash = sha256(bytes);
        self.store.put_base(&hash, bytes)?;
        self.remember(rel, &entry, hash)?;
        report.uploaded += 1;
        Ok(())
    }

    fn ensure_remote_dir(&mut self, api: &dyn WebDavApi, rel: &str) -> Result<()> {
        let rel = nfc_path(rel);
        if rel.is_empty() || self.state.folders.contains(&rel) { return Ok(()) }
        let parent = parent_rel(&rel);
        self.ensure_remote_dir(api, &parent)?;
        api.create_dir(&rel)?;
        self.state.folders.insert(rel);
        Ok(())
    }

    fn drop_remote(&mut self, rel: &str, report: &mut SyncReport) -> Result<()> {
        self.with_local_lock(|e| {
            let Some(old) = e.state.files.remove(rel) else { return Ok(()) };
            let path = join(&e.options.local_root, rel)?;
            if !path.is_file() { return Ok(()) }
            let bytes = read_limited(&path, MAX_BYTES)?;
            if sha256(&bytes) != old.sha256 {
                report.warnings.push(format!("{rel} 在坚果云被删除，但本机有修改；保留本机版本并重新上传"));
                return Ok(())
            }
            e.store.quarantine(&e.options.local_root, rel)?;
            report.changed_paths.insert(rel.to_string());
            report.local_moved_to_recovery += 1;
            Ok(())
        })
    }

    fn cleanup_dirs(&self, old: &BTreeSet<String>) -> Result<()> {
        let mut paths: Vec<&String> = old.iter().filter(|p| !self.state.folders.contains(*p)).collect();
        paths.sort_by_key(|p| std::cmp::Reverse(p.matches('/').count()));
        for rel in paths {
            let path = join(&self.options.local_root, rel)?;
            if path.is_dir() { let _ = fs::remove_dir(path); }
        }
        Ok(())
    }

    fn remember(&mut self, rel: &str, entry: &RemoteEntry, hash: String) -> Result<()> {
        let rel = nfc_path(rel);
        let unchanged = self.state.files.get(&rel).is_some_and(|old| {
            old.etag == entry.etag && old.size == entry.size && old.modified == entry.modified && old.sha256 == hash
        });
        if unchanged { return Ok(()) }
        self.state.files.insert(rel, FileState {
            etag: entry.etag.clone(), size: entry.size, modified: entry.modified.clone(), sha256: hash,
        });
        // A first sync of a large library runs for a long time; keep its
        // progress instead of losing it if the app closes or the network drops.
        self.unsaved += 1;
        if self.unsaved >= 25 {
            let _ = self.store.save(&self.state);
            self.unsaved = 0;
        }
        Ok(())
    }

    fn remember_without_base(&mut self, rel: &str, entry: &RemoteEntry) -> Result<()> {
        let old = self.state.files.get(&nfc_path(rel)).map(|s| s.sha256.clone()).unwrap_or_default();
        self.remember(rel, entry, old)
    }
}

fn remote_changed(old: &FileState, entry: &RemoteEntry) -> bool {
    match (&old.etag, &entry.etag) {
        (Some(a), Some(b)) => a != b,
        _ => old.size != entry.size || old.modified != entry.modified,
    }
}

fn remote_sig(entry: &RemoteEntry) -> String {
    entry.etag.clone().unwrap_or_else(|| format!("{:?}:{:?}", entry.size, entry.modified))
}

fn remote_sig_state(state: &FileState) -> String {
    state.etag.clone().unwrap_or_else(|| format!("{:?}:{:?}", state.size, state.modified))
}

#[derive(Default)]
struct RemoteTree {
    dirs: BTreeSet<String>,
    files: BTreeMap<String, RemoteEntry>,
    warnings: Vec<String>,
}

fn scan_remote(api: &dyn WebDavApi) -> Result<RemoteTree> {
    let mut raw = BTreeMap::new();
    for entry in api.scan()? {
        let rel = nfc_path(&entry.rel);
        if rel.is_empty() { continue }
        if raw.insert(rel.clone(), RemoteEntry { rel: rel.clone(), ..entry }).is_some() { bail!("坚果云返回了重复路径：{rel}") }
    }
    let mut tree = RemoteTree::default();
    let mut keys = BTreeSet::new();
    let mut paths: Vec<&String> = raw.keys().collect();
    paths.sort_by_key(|p| p.matches('/').count());
    for rel in paths {
        let entry = raw.get(rel).unwrap();
        let parent = parent_rel(rel);
        if !parent.is_empty() && !tree.dirs.contains(&parent) { continue }
        for part in rel.split('/') {
            if !valid_name(part) { bail!("坚果云有无法在本机使用的名称：{rel}") }
        }
        if !keys.insert(path_key(rel)) { bail!("坚果云有仅大小写不同的同名条目：{rel}") }
        if entry.is_dir {
            let name = base_name(rel);
            if name.starts_with('.') && !(parent.is_empty() && name == ".assets") { continue }
            if rel.starts_with(".assets/thumbs") { continue }
            tree.dirs.insert(rel.clone());
        } else {
            let name = base_name(rel);
            if is_synced_file(rel, name) { tree.files.insert(rel.clone(), entry.clone()); }
        }
    }
    if raw.len() >= 750 { tree.warnings.push("坚果云单次目录结果达到 750 项上限，已改用逐层扫描".into()); }
    Ok(tree)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nutstore::api::{WebDavError, WebDavErrorKind, WebDavResult};
    use std::path::Path;
    use std::sync::Mutex;

    #[derive(Clone)]
    struct FakeFile { bytes: Vec<u8>, etag: String }

    #[derive(Default)]
    struct Fake {
        files: Mutex<BTreeMap<String, FakeFile>>,
        dirs: Mutex<BTreeSet<String>>,
        n: Mutex<u64>,
        fail_puts: Mutex<Option<u32>>,
        local_lock: Mutex<Option<Arc<AtomicBool>>>,
    }

    impl Fake {
        fn put_file(&self, rel: &str, bytes: &[u8]) {
            let parents = parent_rel(rel);
            let mut dirs = self.dirs.lock().unwrap();
            let mut cur = String::new();
            for part in parents.split('/').filter(|p| !p.is_empty()) {
                if !cur.is_empty() { cur.push('/'); }
                cur.push_str(part);
                dirs.insert(cur.clone());
            }
            let mut n = self.n.lock().unwrap();
            *n += 1;
            self.files.lock().unwrap().insert(rel.into(), FakeFile { bytes: bytes.to_vec(), etag: format!("\"e{n}\"") });
        }
        fn remove_tree(&self, rel: &str) {
            self.files.lock().unwrap().retain(|p, _| p != rel && !p.starts_with(&format!("{rel}/")));
        }
        fn bytes(&self, rel: &str) -> Vec<u8> { self.files.lock().unwrap().get(rel).unwrap().bytes.clone() }
    }

    impl WebDavApi for Fake {
        fn probe(&self) -> WebDavResult<()> { Ok(()) }
        fn ensure_root(&self) -> WebDavResult<()> { Ok(()) }
        fn scan(&self) -> WebDavResult<Vec<RemoteEntry>> {
            let mut out = vec![];
            for dir in self.dirs.lock().unwrap().iter() { out.push(RemoteEntry::dir(dir.clone())); }
            for (rel, f) in self.files.lock().unwrap().iter() {
                out.push(RemoteEntry { rel: rel.clone(), is_dir: false, etag: Some(f.etag.clone()), size: Some(f.bytes.len() as u64), modified: None });
            }
            Ok(out)
        }
        fn get(&self, rel: &str) -> WebDavResult<Vec<u8>> {
            self.files.lock().unwrap().get(rel).map(|f| f.bytes.clone()).ok_or_else(|| WebDavError::new(WebDavErrorKind::NotFound, "missing"))
        }
        fn put(&self, rel: &str, bytes: &[u8], expected: Option<&str>) -> WebDavResult<RemoteEntry> {
            if let Some(flag) = self.local_lock.lock().unwrap().as_ref() {
                assert!(!flag.load(Ordering::Acquire), "an upload held the window's file task lock");
            }
            if let Some(remaining) = self.fail_puts.lock().unwrap().as_mut() {
                if *remaining == 0 {
                    return Err(WebDavError::new(WebDavErrorKind::RateLimited, "Too many requests").with_status(503))
                }
                *remaining -= 1;
            }
            let files = self.files.lock().unwrap();
            match expected {
                Some("") => {}
                Some(expected) if files.get(rel).map(|f| f.etag.as_str()) != Some(expected) => {
                    return Err(WebDavError::new(WebDavErrorKind::Conflict, "etag changed"))
                }
                None if files.contains_key(rel) => return Err(WebDavError::new(WebDavErrorKind::Conflict, "already exists")),
                _ => {}
            }
            drop(files);
            self.put_file(rel, bytes);
            Ok(RemoteEntry { rel: rel.into(), is_dir: false, etag: Some(self.files.lock().unwrap()[rel].etag.clone()), size: Some(bytes.len() as u64), modified: None })
        }
        fn create_dir(&self, rel: &str) -> WebDavResult<()> { self.dirs.lock().unwrap().insert(rel.into()); Ok(()) }
        fn remove(&self, rel: &str, is_dir: bool, _expected_etag: Option<&str>) -> WebDavResult<()> {
            if is_dir { self.remove_tree(rel); } else { self.files.lock().unwrap().remove(rel); }
            Ok(())
        }
        fn move_entry(&self, from: &str, to: &str, is_dir: bool) -> WebDavResult<()> {
            let mut files = self.files.lock().unwrap();
            if is_dir {
                let moved: Vec<(String, FakeFile)> = files.iter().filter(|(p, _)| p.starts_with(&format!("{from}/"))).map(|(p, f)| (p.clone(), f.clone())).collect();
                for (p, f) in moved { files.remove(&p); files.insert(format!("{}{}", to, &p[from.len()..]), f); }
            } else {
                let f = files.remove(from).ok_or_else(|| WebDavError::new(WebDavErrorKind::NotFound, "missing"))?;
                files.insert(to.into(), f);
            }
            Ok(())
        }
    }

    fn temp(name: &str) -> (PathBuf, PathBuf) {
        let base = std::env::temp_dir().join(format!("quiet-md-nutstore-{}-{name}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        let root = base.join("lib");
        fs::create_dir_all(&root).unwrap();
        (root, base.join("cache"))
    }

    fn opts(root: &Path, cache: &Path) -> SyncOptions {
        SyncOptions { local_root: root.into(), cache_dir: cache.into(), config: Config { server: "https://dav.example/dav/".into(), username: "a@b".into(), remote_root: "Quiet.md".into() }, device_name: "Test".into(), local_lock: None }
    }

    fn write(root: &Path, rel: &str, bytes: &[u8]) {
        let p = join(root, rel).unwrap();
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, bytes).unwrap();
    }

    #[test]
    fn first_upload_then_incremental_download() {
        let (root, cache) = temp("first");
        write(&root, "a.md", b"# A\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::UploadLocal).unwrap() else { panic!() };
        assert_eq!(r.uploaded, 1);
        assert_eq!(fake.bytes("a.md"), b"# A\n");

        let (root2, cache2) = temp("first-download");
        let mut e2 = SyncEngine::open(opts(&root2, &cache2)).unwrap();
        let SyncOutcome::Synced(r) = e2.sync(&fake, InitialPolicy::DownloadRemote).unwrap() else { panic!() };
        assert_eq!(r.downloaded, 1);
        assert_eq!(fs::read(root2.join("a.md")).unwrap(), b"# A\n");
    }

    #[test]
    fn uploads_leave_the_window_lock_free() {
        let (root, cache) = temp("upload-lock");
        write(&root, "a.md", b"# A\n");
        let flag = Arc::new(AtomicBool::new(false));
        let fake = Fake::default();
        *fake.local_lock.lock().unwrap() = Some(flag.clone());
        let options = SyncOptions { local_lock: Some(flag.clone()), ..opts(&root, &cache) };
        let mut e = SyncEngine::open(options).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        assert!(fake.files.lock().unwrap().contains_key("a.md"));
        assert!(!flag.load(Ordering::Acquire));
    }

    #[test]
    fn an_interrupted_first_upload_resumes_from_where_it_stopped() {
        let (root, cache) = temp("resume");
        write(&root, "a.md", b"# A\n");
        write(&root, "b.md", b"# B\n");
        let fake = Fake::default();
        *fake.fail_puts.lock().unwrap() = Some(1);
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        assert!(e.sync(&fake, InitialPolicy::UploadLocal).is_err());
        assert!(fake.files.lock().unwrap().contains_key("a.md"));
        assert!(!fake.files.lock().unwrap().contains_key("b.md"));

        *fake.fail_puts.lock().unwrap() = None;
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::Ask).unwrap() else { panic!() };
        assert_eq!(r.uploaded, 1);
        assert!(fake.files.lock().unwrap().contains_key("b.md"));
    }

    #[test]
    fn both_sides_ask_and_merge_cleanly() {
        let (root, cache) = temp("merge-clean");
        let fake = Fake::default();
        fake.put_file("a.md", b"one\n\ntwo\n");
        write(&root, "a.md", b"one\n\ntwo\n");
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        assert!(matches!(e.sync(&fake, InitialPolicy::Ask).unwrap(), SyncOutcome::NeedsInitialChoice(_)));
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::MergeBoth).unwrap() else { panic!() };
        assert_eq!(r.conflicts, 0);
        assert_eq!(fake.bytes("a.md"), b"one\n\ntwo\n");
    }

    #[test]
    fn remote_and_local_edits_merge_without_losing_a_side() {
        let (root, cache) = temp("three-way");
        write(&root, "a.xml", b"<title>A</title><p>one</p><p>two</p>\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        let remote = fake.bytes("a.xml").windows(3).position(|w| w == b"two").unwrap();
        let mut theirs = fake.bytes("a.xml"); theirs.splice(remote..remote + 3, b"TWO".iter().copied());
        fake.put_file("a.xml", &theirs);
        write(&root, "a.xml", b"<title>A</title><p>ONE</p><p>two</p>\n");
        let SyncOutcome::Synced(_) = e.sync(&fake, InitialPolicy::Ask).unwrap() else { panic!() };
        let merged = String::from_utf8(fake.bytes("a.xml")).unwrap();
        assert!(merged.contains("ONE") && merged.contains("TWO"), "{merged}");
    }

    #[test]
    fn same_block_conflict_keeps_a_copy() {
        let (root, cache) = temp("conflict");
        write(&root, "a.md", b"one\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fake.put_file("a.md", b"remote\n");
        write(&root, "a.md", b"local\n");
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::Ask).unwrap() else { panic!() };
        assert_eq!(r.conflicts, 1);
        assert!(fs::read_dir(&root).unwrap().flatten().any(|e| e.file_name().to_string_lossy().contains("冲突")));
    }

    #[test]
    fn local_rename_uses_move_and_keeps_the_remote_file() {
        let (root, cache) = temp("move");
        write(&root, "Work/a.md", b"same\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fs::rename(root.join("Work/a.md"), root.join("Work/b.md")).unwrap();
        e.sync(&fake, InitialPolicy::Ask).unwrap();
        assert!(!fake.files.lock().unwrap().contains_key("Work/a.md"));
        assert_eq!(fake.bytes("Work/b.md"), b"same\n");
    }

    #[test]
    fn remote_delete_moves_an_unchanged_local_copy_to_recovery() {
        let (root, cache) = temp("remote-delete");
        write(&root, "a.md", b"same\n");
        write(&root, "keep.md", b"keep\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fake.files.lock().unwrap().remove("a.md");
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::Ask).unwrap() else { panic!() };
        assert_eq!(r.local_moved_to_recovery, 1);
        assert!(!root.join("a.md").exists());
    }

    #[test]
    fn only_managed_files_are_uploaded() {
        let (root, cache) = temp("whitelist");
        write(&root, "a.md", b"a");
        write(&root, "skip.txt", b"skip");
        write(&root, ".quiet/index.json", b"{}");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        let files = fake.files.lock().unwrap();
        assert!(files.contains_key("a.md"));
        assert!(!files.contains_key("skip.txt"));
        assert!(!files.keys().any(|p| p.starts_with(".quiet/")));
    }

    #[test]
    fn remote_folder_rename_moves_the_local_file() {
        let (root, cache) = temp("remote-move");
        write(&root, "Work/a.md", b"same\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        let moved = fake.files.lock().unwrap().remove("Work/a.md").unwrap();
        fake.files.lock().unwrap().insert("Archive/a.md".into(), moved);
        fake.dirs.lock().unwrap().insert("Archive".into());
        assert!(e.sync(&fake, InitialPolicy::Ask).is_ok());
        assert!(!root.join("Work/a.md").exists());
        assert_eq!(fs::read(root.join("Archive/a.md")).unwrap(), b"same\n");
    }

    #[test]
    fn local_delete_removes_the_remote_file() {
        let (root, cache) = temp("local-delete");
        write(&root, "a.md", b"same\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fs::remove_file(root.join("a.md")).unwrap();
        e.sync(&fake, InitialPolicy::Ask).unwrap();
        assert!(!fake.files.lock().unwrap().contains_key("a.md"));
    }

    #[test]
    fn empty_remote_folders_are_not_recreated_locally() {
        let (root, cache) = temp("empty-dir");
        let fake = Fake::default();
        fake.dirs.lock().unwrap().insert("Empty".into());
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::Ask).unwrap();
        assert!(!root.join("Empty").exists());
    }

    #[test]
    fn binary_conflict_keeps_both_sides() {
        let (root, cache) = temp("binary");
        write(&root, ".assets/a.png", b"local-old");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fake.put_file(".assets/a.png", b"remote-new");
        write(&root, ".assets/a.png", b"local-new");
        let SyncOutcome::Synced(r) = e.sync(&fake, InitialPolicy::Ask).unwrap() else { panic!() };
        assert_eq!(r.conflicts, 1);
        assert_eq!(fs::read(root.join(".assets/a.png")).unwrap(), b"local-new");
        let conflict = fake.files.lock().unwrap().keys().find(|p| p.contains("冲突")).cloned();
        assert!(conflict.is_some());
    }

    #[test]
    fn a_suddenly_empty_remote_folder_does_not_clear_the_library() {
        let (root, cache) = temp("remote-empty");
        write(&root, "a.md", b"same\n");
        let fake = Fake::default();
        let mut e = SyncEngine::open(opts(&root, &cache)).unwrap();
        e.sync(&fake, InitialPolicy::UploadLocal).unwrap();
        fake.files.lock().unwrap().clear();
        assert!(e.sync(&fake, InitialPolicy::Ask).is_err());
        assert_eq!(fs::read(root.join("a.md")).unwrap(), b"same\n");
    }
}

