//! Content-level pieces shared by path-based sync backends: the local file
//! whitelist, content hashing, atomic downloads, conflict names and three-way
//! document merges. Remote transports decide which files changed.

use anyhow::{Context as _, Result, bail};
use fdl_core::{docxml, markdown, merge};
use sha2::{Digest as _, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::Write as _;
use std::path::{Path, PathBuf};
use unicode_normalization::UnicodeNormalization as _;

pub(crate) const WARN_BYTES: u64 = 50 * 1024 * 1024;
pub(crate) const MAX_BYTES: u64 = 100 * 1024 * 1024;

pub(crate) fn sha256(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for b in digest { out.push_str(&format!("{b:02x}")); }
    out
}

fn check_hash(hash: &str) -> Result<()> {
    if hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()) { Ok(()) } else { bail!("无效的内容哈希") }
}

pub(crate) fn nfc_path(path: &str) -> String {
    let path = path.replace('\\', "/");
    path.nfc().collect::<String>().split('/').filter(|p| !p.is_empty() && *p != ".").collect::<Vec<_>>().join("/")
}

pub(crate) fn path_key(path: &str) -> String { nfc_path(path).to_lowercase() }

pub(crate) fn safe_relative(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.starts_with('\\') || path.contains('\0') { return false }
    path.split(['/', '\\']).all(|p| !p.is_empty() && p != "." && p != "..")
}

pub(crate) fn valid_name(name: &str) -> bool {
    if name.is_empty() || name == "." || name == ".." || name.len() > 240 || name.ends_with(' ') || name.ends_with('.') { return false }
    if name.chars().any(|c| c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || c == '\0') { return false }
    let stem = name.split('.').next().unwrap_or("").to_ascii_uppercase();
    !(matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (stem.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT")) && stem.as_bytes()[3].is_ascii_digit() && stem.as_bytes()[3] != b'0'))
}

pub(crate) fn join(root: &Path, rel: &str) -> Result<PathBuf> {
    if !safe_relative(rel) { bail!("不安全的相对路径：{rel}") }
    let mut out = root.to_path_buf();
    for part in rel.split('/') { out.push(part); }
    if !out.starts_with(root) { bail!("路径逃出同步根目录：{rel}") }
    Ok(out)
}

pub(crate) fn quarantine(root: &Path, cache: &Path, rel: &str) -> Result<PathBuf> {
    let from = join(root, rel)?;
    if !from.exists() { bail!("要移开的文件不存在：{rel}") }
    let stamp = crate::time::now_ms();
    let mut to = cache.join("removed").join(stamp.to_string()).join(rel);
    let mut n = 2;
    while to.exists() {
        to = cache.join("removed").join(stamp.to_string()).join(format!("{n}-{rel}"));
        n += 1;
    }
    if let Some(parent) = to.parent() { fs::create_dir_all(parent)?; }
    fs::rename(&from, &to).with_context(|| format!("移开 {}", from.display()))?;
    Ok(to)
}

/// Content-addressed baseline files shared by path-based sync backends.
pub(crate) struct BaseStore { dir: PathBuf }

impl BaseStore {
    pub(crate) fn new(dir: impl Into<PathBuf>) -> Self { Self { dir: dir.into() } }
    pub(crate) fn bases_dir(&self) -> PathBuf { self.dir.join("bases") }

    pub(crate) fn base(&self, hash: &str) -> Result<Vec<u8>> {
        check_hash(hash)?;
        let path = self.bases_dir().join(hash);
        fs::read(&path).with_context(|| format!("读取同步基准 {}", path.display()))
    }

    pub(crate) fn put_base(&self, hash: &str, bytes: &[u8]) -> Result<()> {
        check_hash(hash)?;
        if sha256(bytes) != hash { bail!("同步基准的哈希不匹配") }
        fs::create_dir_all(self.bases_dir())?;
        let path = self.bases_dir().join(hash);
        if path.is_file() { return Ok(()) }
        write_atomic(&path, bytes)
    }
}

#[derive(Clone, Debug)]
pub(crate) struct LocalFile {
    pub size: u64,
    pub sha256: String,
}

#[derive(Default)]
pub(crate) struct LocalScan {
    pub dirs: BTreeSet<String>,
    pub files: BTreeMap<String, LocalFile>,
    pub warnings: Vec<String>,
}

pub(crate) fn scan_local(root: &Path) -> Result<LocalScan> {
    let mut out = LocalScan::default();
    walk_local(root, "", &mut out)?;
    let mut files = BTreeSet::new();
    for path in out.files.keys() {
        if !files.insert(path_key(path)) { bail!("本机有仅大小写不同的同名文件，暂停同步：{path}") }
    }
    let mut dirs = BTreeSet::new();
    for path in &out.dirs {
        if !dirs.insert(path_key(path)) { bail!("本机有仅大小写不同的同名文件夹，暂停同步：{path}") }
    }
    Ok(out)
}

fn walk_local(root: &Path, rel: &str, out: &mut LocalScan) -> Result<()> {
    let dir = if rel.is_empty() { root.to_path_buf() } else { root.join(rel) };
    let rd = fs::read_dir(&dir).with_context(|| format!("读取 {}", dir.display()))?;
    for entry in rd {
        let entry = entry?;
        let raw = entry.file_name();
        let Some(name) = raw.to_str().map(str::to_owned) else {
            out.warnings.push(format!("跳过无法用 UTF-8 表示的文件名：{}", raw.to_string_lossy()));
            continue
        };
        let child = nfc_path(&format!("{rel}/{name}"));
        let ty = entry.file_type()?;
        if ty.is_symlink() { continue }
        if ty.is_dir() {
            if name.starts_with('.') && !(rel.is_empty() && name == ".assets") { continue }
            if child.starts_with(".assets/thumbs") { continue }
            out.dirs.insert(child.clone());
            walk_local(root, &child, out)?;
        } else if ty.is_file() {
            if name.starts_with('.') || name.ends_with(".tmp") || name.ends_with(".sqlite-journal") { continue }
            let synced = if child.starts_with(".assets/") { !child.starts_with(".assets/thumbs/") } else { is_synced_file(&child, &name) };
            if !synced { continue }
            let size = entry.metadata()?.len();
            if size > MAX_BYTES { out.warnings.push(format!("{child} 超过 100 MB，未同步")); continue }
            if size > WARN_BYTES { out.warnings.push(format!("{child} 超过 50 MB，同步可能较慢")); }
            let bytes = fs::read(entry.path())?;
            out.files.insert(child, LocalFile { size, sha256: sha256(&bytes) });
        }
    }
    Ok(())
}

pub(crate) fn is_synced_file(rel: &str, name: &str) -> bool {
    if name.starts_with('.') || name.ends_with(".tmp") || name.ends_with(".sqlite-journal") { return false }
    if rel.starts_with(".assets/") { return !rel.starts_with(".assets/thumbs/") }
    matches!(name.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).as_deref(), Some("md" | "markdown" | "mdown" | "xml" | "excalidraw" | "sqlite"))
}

pub(crate) fn is_text_document(rel: &str) -> bool {
    let ext = rel.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default();
    matches!(ext.as_str(), "md" | "markdown" | "mdown" | "xml")
}

pub(crate) fn parent_rel(rel: &str) -> String { rel.rsplit_once('/').map_or(String::new(), |(d, _)| d.to_string()) }
pub(crate) fn base_name(rel: &str) -> &str { rel.rsplit_once('/').map_or(rel, |(_, n)| n) }
pub(crate) fn stem(rel: &str) -> &str {
    let name = base_name(rel);
    name.rsplit_once('.').filter(|(s, _)| !s.is_empty()).map_or(name, |(s, _)| s)
}
pub(crate) fn extension(rel: &str) -> &str { base_name(rel).rsplit_once('.').map_or("", |(_, e)| e) }

pub(crate) fn read_limited(path: &Path, max: u64) -> Result<Vec<u8>> {
    let meta = fs::metadata(path).with_context(|| format!("读取 {}", path.display()))?;
    if meta.len() > max { bail!("{} 超过同步上限", path.display()) }
    fs::read(path).with_context(|| format!("读取 {}", path.display()))
}

pub(crate) fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(format!(".{}.tmp", uuid::Uuid::new_v4()));
    let tmp = PathBuf::from(tmp);
    let result = (|| -> Result<()> {
        let mut file = fs::OpenOptions::new().write(true).create_new(true).open(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&tmp, path)?;
        Ok(())
    })();
    if result.is_err() { let _ = fs::remove_file(&tmp); }
    result
}

pub(crate) struct Merged {
    pub bytes: Vec<u8>,
    pub conflict_bytes: Option<Vec<u8>>,
}

pub(crate) fn merge_bytes(rel: &str, base: &[u8], ours: &[u8], theirs: &[u8], root: &Path) -> Result<Merged> {
    let ext = extension(rel).to_ascii_lowercase();
    if ext == "xml" {
        let b = docxml::parse(std::str::from_utf8(base)?)?;
        let o = docxml::parse(std::str::from_utf8(ours)?)?;
        let t = docxml::parse(std::str::from_utf8(theirs)?)?;
        let merged = merge::merge3(&b, &o, &t);
        let bytes = docxml::serialize(merged.document()).into_bytes();
        return Ok(Merged { bytes, conflict_bytes: matches!(merged, merge::Merge::Conflicts { .. }).then(|| theirs.to_vec()) })
    }
    if matches!(ext.as_str(), "md" | "markdown" | "mdown") {
        let title = stem(rel);
        let b = markdown::parse_file(std::str::from_utf8(base)?, title, Some(root));
        let o = markdown::parse_file(std::str::from_utf8(ours)?, title, Some(root));
        let t = markdown::parse_file(std::str::from_utf8(theirs)?, title, Some(root));
        let merged = merge::merge3(&b, &o, &t);
        let front = front_matter(std::str::from_utf8(ours)?);
        let text = markdown::write_file(merged.document(), &front, Some(root));
        return Ok(Merged { bytes: text.into_bytes(), conflict_bytes: matches!(merged, merge::Merge::Conflicts { .. }).then(|| theirs.to_vec()) })
    }
    Ok(Merged { bytes: ours.to_vec(), conflict_bytes: Some(theirs.to_vec()) })
}

fn front_matter(text: &str) -> String {
    let text = text.trim_start_matches('\u{feff}');
    let Some(rest) = text.strip_prefix("---\n") else { return String::new() };
    let Some(end) = rest.find("\n---\n") else { return String::new() };
    format!("---\n{}---\n", &rest[..end + 1])
}

pub(crate) fn fresh_conflict_path<'a>(root: &Path, rel: &str, device: &str, known: impl Iterator<Item = &'a String>) -> Result<String> {
    let (year, month, day, hour, minute) = fdl_core::time::civil(crate::time::now());
    let parent = parent_rel(rel);
    let ext = extension(rel);
    let device: String = conflict_component(device).chars().take(40).collect();
    let title: String = conflict_component(stem(rel)).chars().take(120).collect();
    let base = format!("{}（冲突 · {} · {:04}-{:02}-{:02} {:02}-{:02}）", title, device, year, month, day, hour, minute);
    let known: BTreeSet<String> = known.map(|p| path_key(p)).collect();
    for n in 1.. {
        let stem = if n == 1 { base.clone() } else { format!("{base} ({n})") };
        let name = if ext.is_empty() { stem } else { format!("{stem}.{ext}") };
        let candidate = if parent.is_empty() { name } else { format!("{parent}/{name}") };
        if !join(root, &candidate)?.exists() && !known.contains(&path_key(&candidate)) { return Ok(candidate) }
    }
    unreachable!()
}

fn conflict_component(s: &str) -> String {
    let mut out: String = s.chars().map(|c| if c.is_control() || matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') { ' ' } else { c }).collect();
    out = out.split_whitespace().collect::<Vec<_>>().join(" ");
    out = out.trim_matches(['.', ' ']).to_string();
    if out.is_empty() { "Quiet.md".into() } else { out }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_and_names_reject_escapes() {
        assert!(safe_relative("Work/a.md"));
        assert!(!safe_relative("../a"));
        assert!(!safe_relative("Work/../../a"));
        assert!(!safe_relative("/a"));
        assert!(valid_name("周报.md"));
        assert!(!valid_name("CON.md"));
        assert!(!valid_name("a:b.md"));
        assert!(!valid_name("a."));
        assert_eq!(nfc_path(r"Work\\a.md"), "Work/a.md");
        assert_eq!(nfc_path("Cafe\u{301}.md"), nfc_path("Café.md"));
    }

    #[test]
    fn baselines_are_content_addressed() {
        let root = std::env::temp_dir().join(format!("quiet-md-sync-bases-{}", uuid::Uuid::new_v4()));
        let store = BaseStore::new(&root);
        let bytes = b"hello";
        let hash = sha256(bytes);
        store.put_base(&hash, bytes).unwrap();
        assert_eq!(store.base(&hash).unwrap(), bytes);
        assert!(store.put_base(&"0".repeat(64), bytes).is_err());
        let _ = fs::remove_dir_all(root);
    }
}

