//! Windows taskbar notification-area icon: 关闭窗口 › 最小化到任务栏 hides the
//! main window and keeps Quiet.md running; clicking the icon brings it back.

use gpui::{Context, Window};

#[cfg(target_os = "windows")]
mod imp {
    use gpui::{Context, Window};
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use std::{cell::RefCell, sync::atomic::{AtomicBool, Ordering}, time::Duration};
    use tray_icon::{menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem}, Icon, MouseButton, TrayIcon, TrayIconBuilder, TrayIconEvent};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{ShowWindow, SW_HIDE, SW_SHOW};

    use crate::app::Workspace;

    thread_local! {
        static TRAY: RefCell<Option<TrayIcon>> = const { RefCell::new(None) };
    }
    static POLLING: AtomicBool = AtomicBool::new(false);

    fn icon() -> Result<Icon, String> {
        let bytes = crate::assets::get("branding/app-icon.png").ok_or("missing app icon")?;
        let image = image::load_from_memory(bytes).map_err(|e| e.to_string())?;
        let image = image::imageops::resize(&image.into_rgba8(), 32, 32, image::imageops::FilterType::Lanczos3);
        let (width, height) = image.dimensions();
        Icon::from_rgba(image.into_raw(), width, height).map_err(|e| e.to_string())
    }

    fn ensure(show: &str, quit: &str) -> Result<(), String> {
        TRAY.with(|slot| {
            let mut slot = slot.borrow_mut();
            if slot.is_some() { return Ok(()) }
            let menu = Menu::new();
            let show_item = MenuItem::with_id("show", show, true, None);
            let quit_item = MenuItem::with_id("quit", quit, true, None);
            menu.append_items(&[&show_item, &PredefinedMenuItem::separator(), &quit_item]).map_err(|e| e.to_string())?;
            let tray = TrayIconBuilder::new()
                .with_menu(Box::new(menu))
                .with_icon(icon()?)
                .with_tooltip("Quiet.md")
                .with_menu_on_left_click(false)
                .build()
                .map_err(|e| e.to_string())?;
            *slot = Some(tray);
            Ok(())
        })
    }

    fn hwnd(window: &Window) -> Option<HWND> {
        match HasWindowHandle::window_handle(window).ok()?.as_raw() {
            RawWindowHandle::Win32(handle) => Some(HWND(handle.hwnd.get() as *mut _)),
            _ => None,
        }
    }

    pub(super) fn hide(window: &Window, show: &str, quit: &str) -> Result<(), String> {
        ensure(show, quit)?;
        let hwnd = hwnd(window).ok_or("missing window handle")?;
        unsafe { let _ = ShowWindow(hwnd, SW_HIDE); }
        Ok(())
    }

    pub(super) fn show(window: &mut Window) {
        let Some(hwnd) = hwnd(window) else { return };
        unsafe { let _ = ShowWindow(hwnd, SW_SHOW); }
        window.activate_window();
    }

    pub(super) fn poll(cx: &mut Context<Workspace>) {
        if POLLING.swap(true, Ordering::SeqCst) { return; }
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_millis(100)).await;
                let mut show_window = false;
                let mut quit_app = false;
                while let Ok(event) = TrayIconEvent::receiver().try_recv() {
                    if matches!(
                        event,
                        TrayIconEvent::Click { button: MouseButton::Left, .. }
                            | TrayIconEvent::DoubleClick { button: MouseButton::Left, .. }
                    ) { show_window = true; }
                }
                while let Ok(event) = MenuEvent::receiver().try_recv() {
                    if event.id == "show" { show_window = true; }
                    if event.id == "quit" { quit_app = true; }
                }
                if show_window {
                    let _ = this.update_in(cx, |_, window, cx| { show(window); cx.notify(); });
                }
                if quit_app {
                    // A failed save asks in the window, so bring it back before quitting.
                    let _ = this.update_in(cx, |_, window, cx| { show(window); cx.defer(crate::app::quit); });
                    break;
                }
            }
            POLLING.store(false, Ordering::SeqCst);
        }).detach();
    }
}

pub(crate) const fn available() -> bool {
    cfg!(target_os = "windows")
}

#[cfg(target_os = "windows")]
pub(crate) fn hide(window: &Window, show: &str, quit: &str) -> Result<(), String> {
    imp::hide(window, show, quit)
}

#[cfg(not(target_os = "windows"))]
pub(crate) fn hide(_: &Window, _: &str, _: &str) -> Result<(), String> {
    Err("system tray is unavailable".into())
}

#[cfg(target_os = "windows")]
pub(crate) fn start_poll(cx: &mut Context<crate::app::Workspace>) {
    imp::poll(cx);
}

#[cfg(not(target_os = "windows"))]
pub(crate) fn start_poll(_: &mut Context<crate::app::Workspace>) {}
