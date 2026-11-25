//! Secrets at rest for sync backends. The document library never holds
//! credentials; macOS and Windows use the system credential store, other
//! platforms use a private local file.

use std::path::Path;

pub(crate) fn load_blob(service: &str, account: &str, fallback: &Path) -> Result<Option<Vec<u8>>, String> {
    let _ = (service, account, fallback);
    #[cfg(target_os = "macos")]
    { mac::load(service, account) }
    #[cfg(target_os = "windows")]
    { win::load(service, account) }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    { read_bytes(fallback) }
}

pub(crate) fn save_blob(service: &str, account: &str, fallback: &Path, bytes: &[u8]) -> Result<(), String> {
    let _ = (service, account, fallback);
    #[cfg(target_os = "macos")]
    { mac::save(service, account, bytes) }
    #[cfg(target_os = "windows")]
    { win::save(service, account, bytes) }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    { write_bytes(fallback, bytes) }
}

pub(crate) fn delete_blob(service: &str, account: &str, fallback: &Path) -> Result<(), String> {
    let _ = (service, account, fallback);
    #[cfg(target_os = "macos")]
    { mac::delete(service, account) }
    #[cfg(target_os = "windows")]
    { win::delete(service, account) }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        match std::fs::remove_file(fallback) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
}

#[cfg(any(not(any(target_os = "macos", target_os = "windows")), test))]
fn read_bytes(path: &Path) -> Result<Option<Vec<u8>>, String> {
    match std::fs::read(path) {
        Ok(b) => Ok(Some(b)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[cfg(any(not(any(target_os = "macos", target_os = "windows")), test))]
fn write_bytes(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let dir = path.parent().ok_or_else(|| "secret path has no parent".to_string())?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
    }
    let tmp = path.with_extension(format!("tmp-{}", uuid::Uuid::new_v4()));
    std::fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
    }
    std::fs::rename(&tmp, path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        e.to_string()
    })
}

#[cfg(target_os = "windows")]
mod win {
    use std::ptr;
    use windows::Win32::Foundation::ERROR_NOT_FOUND;
    use windows::Win32::Security::Credentials::{
        CRED_MAX_CREDENTIAL_BLOB_SIZE, CRED_PERSIST_LOCAL_MACHINE, CRED_TYPE_GENERIC, CREDENTIALW, CredDeleteW,
        CredFree, CredReadW, CredWriteW,
    };
    use windows::core::{PCWSTR, PWSTR};

    fn target(service: &str, account: &str) -> Vec<u16> { format!("{service}:{account}").encode_utf16().chain(Some(0)).collect() }

    pub fn load(service: &str, account: &str) -> Result<Option<Vec<u8>>, String> {
        let target = target(service, account);
        let mut credential: *mut CREDENTIALW = ptr::null_mut();
        match unsafe { CredReadW(PCWSTR::from_raw(target.as_ptr()), CRED_TYPE_GENERIC, None, &mut credential) } {
            Ok(()) => {}
            Err(e) if e.code() == ERROR_NOT_FOUND.to_hresult() => return Ok(None),
            Err(e) => return Err(e.to_string()),
        }
        if credential.is_null() { return Ok(None) }
        let bytes = unsafe {
            let c = &*credential;
            if c.CredentialBlob.is_null() || c.CredentialBlobSize == 0 { Vec::new() }
            else { std::slice::from_raw_parts(c.CredentialBlob, c.CredentialBlobSize as usize).to_vec() }
        };
        unsafe { CredFree(credential.cast()) };
        Ok(Some(bytes))
    }

    pub fn save(service: &str, account: &str, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() > CRED_MAX_CREDENTIAL_BLOB_SIZE as usize {
            return Err(format!("secret is {} bytes; Windows Credential Manager allows {CRED_MAX_CREDENTIAL_BLOB_SIZE}", bytes.len()));
        }
        let mut target = target(service, account);
        let mut user: Vec<u16> = "Quiet.md".encode_utf16().chain(Some(0)).collect();
        let mut blob = bytes.to_vec();
        let credential = CREDENTIALW {
            LastWritten: Default::default(),
            Type: CRED_TYPE_GENERIC,
            TargetName: PWSTR::from_raw(target.as_mut_ptr()),
            CredentialBlobSize: blob.len() as u32,
            CredentialBlob: blob.as_mut_ptr(),
            Persist: CRED_PERSIST_LOCAL_MACHINE,
            UserName: PWSTR::from_raw(user.as_mut_ptr()),
            ..Default::default()
        };
        unsafe { CredWriteW(&credential, 0) }.map_err(|e| e.to_string())
    }

    pub fn delete(service: &str, account: &str) -> Result<(), String> {
        let target = target(service, account);
        match unsafe { CredDeleteW(PCWSTR::from_raw(target.as_ptr()), CRED_TYPE_GENERIC, None) } {
            Ok(()) => Ok(()),
            Err(e) if e.code() == ERROR_NOT_FOUND.to_hresult() => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
}

#[cfg(target_os = "macos")]
mod mac {
    use objc2_core_foundation::{CFData, CFDictionary, CFRetained, CFString, CFType, kCFBooleanTrue};
    use objc2_security::{
        SecItemAdd, SecItemCopyMatching, SecItemDelete, SecItemUpdate, errSecDuplicateItem, errSecItemNotFound,
        errSecSuccess, kSecAttrAccount, kSecAttrService, kSecClass, kSecClassGenericPassword, kSecReturnData,
        kSecValueData,
    };
    use std::ptr;

    fn query(service: &CFString, account: &CFString) -> CFRetained<CFDictionary<CFString, CFType>> {
        let (class_key, service_key, account_key, password_class) = unsafe {
            (kSecClass, kSecAttrService, kSecAttrAccount, kSecClassGenericPassword)
        };
        CFDictionary::from_slices(&[class_key, service_key, account_key], &[password_class, service, account])
    }

    fn check(action: &str, status: i32) -> Result<(), String> {
        if status == errSecSuccess { Ok(()) } else { Err(format!("Keychain {action} failed with OSStatus {status}")) }
    }

    pub fn load(service: &str, account: &str) -> Result<Option<Vec<u8>>, String> {
        let service = CFString::from_str(service);
        let account = CFString::from_str(account);
        let (true_value, data_key) = unsafe {
            let Some(true_value) = kCFBooleanTrue else { return Err("Core Foundation true value unavailable".into()) };
            (true_value, kSecReturnData)
        };
        let (class_key, service_key, account_key, password_class) = unsafe {
            (kSecClass, kSecAttrService, kSecAttrAccount, kSecClassGenericPassword)
        };
        let query = CFDictionary::from_slices(
            &[class_key, service_key, account_key, data_key],
            &[password_class, &service, &account, true_value],
        );
        let mut result = ptr::null();
        let status = unsafe { SecItemCopyMatching(query.as_opaque(), &mut result) };
        if status == errSecItemNotFound { return Ok(None) }
        check("read", status)?;
        let result = ptr::NonNull::new(result.cast_mut()).ok_or_else(|| "Keychain returned no item".to_string())?;
        let value = unsafe { CFRetained::from_raw(result) }
            .downcast::<CFData>()
            .map_err(|_| "Keychain item was not data".to_string())?;
        Ok(Some(value.to_vec()))
    }

    pub fn save(service: &str, account: &str, bytes: &[u8]) -> Result<(), String> {
        let service = CFString::from_str(service);
        let account = CFString::from_str(account);
        let data = CFData::from_bytes(bytes);
        let (data_key,) = unsafe { (kSecValueData,) };
        let attributes = CFDictionary::from_slices(&[data_key], &[&data as &CFType]);
        let mut status = unsafe { SecItemUpdate(query(&service, &account).as_opaque(), attributes.as_opaque()) };
        if status == errSecItemNotFound {
            let (class_key, service_key, account_key, password_class, data_key) = unsafe {
                (kSecClass, kSecAttrService, kSecAttrAccount, kSecClassGenericPassword, kSecValueData)
            };
            let item = CFDictionary::from_slices(
                &[class_key, service_key, account_key, data_key],
                &[password_class, &service, &account, &data as &CFType],
            );
            status = unsafe { SecItemAdd(item.as_opaque(), ptr::null_mut()) };
            if status == errSecDuplicateItem { status = errSecSuccess }
        }
        check("write", status)
    }

    pub fn delete(service: &str, account: &str) -> Result<(), String> {
        let service = CFString::from_str(service);
        let account = CFString::from_str(account);
        let status = unsafe { SecItemDelete(query(&service, &account).as_opaque()) };
        if status == errSecItemNotFound { Ok(()) } else { check("delete", status) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_file_round_trips_and_is_deletable() {
        let dir = std::env::temp_dir().join(format!("quiet-md-secret-{}", uuid::Uuid::new_v4()));
        let path = dir.join("secret");
        write_bytes(&path, b"secret").unwrap();
        assert_eq!(read_bytes(&path).unwrap(), Some(b"secret".to_vec()));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
}
