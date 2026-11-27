//! Desktop platform code: the pieces the mobile ports (docs/design/mobile.md,
//! decisions M1 and M10) leave out. Nothing here is iOS-shaped: the quick-note
//! window and its global hotkeys, the Windows notification-area icon, PDF and
//! printing through the system web views, in-app updates (mobile ships through
//! the stores instead) and the macOS native sidebar material. Modules are
//! cfg-gated inside themselves, so the crate still builds everywhere.

pub mod quick_note;
pub mod tray;
pub mod printing;
pub mod update;
#[cfg(target_os = "macos")]
pub mod native_header;
#[cfg(not(target_os = "macos"))]
#[path = "native_header_fallback.rs"]
pub mod native_header;
