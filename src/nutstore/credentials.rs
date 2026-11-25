//! Nutstore WebDAV application password storage. The password lives in the OS
//! credential store and never in the library.

use crate::sync_credentials as secure;
use std::path::{Path, PathBuf};

const SERVICE: &str = "Quiet.md Nutstore";

fn override_path() -> Option<PathBuf> {
    std::env::var_os("QUIET_MD_NUTSTORE_PASSWORD_FILE").map(PathBuf::from)
}

fn fallback(cache: &Path) -> PathBuf { cache.join("password") }

pub fn load(root_key: &str, cache: &Path) -> Result<Option<String>, String> {
    let bytes = if let Some(path) = override_path() {
        match std::fs::read(&path) {
            Ok(b) => Some(b),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(e) => return Err(e.to_string()),
        }
    } else {
        secure::load_blob(SERVICE, root_key, &fallback(cache))?
    };
    let Some(bytes) = bytes else { return Ok(None) };
    String::from_utf8(bytes).map(|s| s.trim_end_matches(['\r', '\n']).to_string()).map(Some).map_err(|e| format!("Nutstore password: {e}"))
}

pub fn save(root_key: &str, cache: &Path, password: &str) -> Result<(), String> {
    if let Some(path) = override_path() {
        if let Some(parent) = path.parent() { std::fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
        return std::fs::write(path, password).map_err(|e| e.to_string())
    }
    secure::save_blob(SERVICE, root_key, &fallback(cache), password.as_bytes())
}

pub fn delete(root_key: &str, cache: &Path) -> Result<(), String> {
    if let Some(path) = override_path() {
        return match std::fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
    secure::delete_blob(SERVICE, root_key, &fallback(cache))
}

