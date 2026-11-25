//! Nutstore connection dialog and the Workspace-specific settings controls.

use crate::app::Workspace;
use crate::input::{TextField, TextFieldEvent};
use crate::nutstore::state::{Config, clean_remote_root, normalize_server};
use crate::settings::{button, group, row, tr};
use crate::theme::{self, Theme};
use crate::trash::{close_button, dialog, primary_button, scrim, secondary_button};
use gpui::*;

pub struct ConnectDialog {
    pub root_key: String,
    pub server: Entity<TextField>,
    pub username: Entity<TextField>,
    pub password: Entity<TextField>,
    pub remote: Entity<TextField>,
    pub error: Option<String>,
    _subs: Vec<Subscription>,
}

impl Workspace {
    pub(crate) fn open_nutstore_connect(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(root_key) = self.store.active_root() else { return };
        let cfg = crate::nutstore::manager::config_for(cx, &root_key).unwrap_or_default();
        let server_text = if cfg.server.is_empty() { "https://dav.jianguoyun.com/dav/".to_string() } else { cfg.server };
        let root_name = self.store.roots().into_iter().find(|(k, _, _, _)| k == &root_key).map(|(_, n, _, _)| n).unwrap_or_else(|| "Quiet.md".into());
        let suggested = if root_name.eq_ignore_ascii_case("Quiet.md") { "Quiet.md".to_string() } else { format!("Quiet.md/{root_name}") };
        let suggested = if clean_remote_root(&suggested).is_ok() { suggested } else { format!("Quiet.md/{root_key}") };
        let remote_text = if cfg.remote_root.is_empty() { suggested } else { cfg.remote_root };
        let server = cx.new(|cx| {
            let mut f = TextField::new("https://dav.jianguoyun.com/dav/", cx);
            f.tab_events = true;
            f.max_len = Some(2048);
            f.set_text(&server_text, cx);
            f
        });
        let username = cx.new(|cx| {
            let mut f = TextField::new(tr("坚果云账号邮箱", "Nutstore account email"), cx);
            f.tab_events = true;
            f.max_len = Some(320);
            f.set_text(&cfg.username, cx);
            f
        });
        let password = cx.new(|cx| {
            let mut f = TextField::new(tr("第三方应用密码", "Third-party app password"), cx);
            f.tab_events = true;
            f.masked = true;
            f.max_len = Some(256);
            f
        });
        let remote = cx.new(|cx| {
            let mut f = TextField::new("Quiet.md", cx);
            f.tab_events = true;
            f.max_len = Some(512);
            f.set_text(&remote_text, cx);
            f
        });
        let subs = vec![
            Self::nutstore_field_sub(window, &server, 0, cx),
            Self::nutstore_field_sub(window, &username, 1, cx),
            Self::nutstore_field_sub(window, &password, 2, cx),
            Self::nutstore_field_sub(window, &remote, 3, cx),
        ];
        let focus = server.focus_handle(cx);
        window.focus(&focus, cx);
        self.nutstore_dlg = Some(ConnectDialog { root_key, server, username, password, remote, error: None, _subs: subs });
        cx.notify();
    }

    fn nutstore_field_sub(window: &mut Window, field: &Entity<TextField>, index: usize, cx: &mut Context<Self>) -> Subscription {
        cx.subscribe_in(field, window, move |w, _, ev: &TextFieldEvent, window, cx| {
            match ev {
                TextFieldEvent::Submit => w.submit_nutstore_connect(window, cx),
                TextFieldEvent::Cancel => w.close_nutstore_connect(window, cx),
                TextFieldEvent::Tab => w.focus_nutstore_field(index, 1, window, cx),
                TextFieldEvent::ShiftTab => w.focus_nutstore_field(index, -1, window, cx),
                _ => cx.notify(),
            }
        })
    }

    fn focus_nutstore_field(&mut self, index: usize, delta: isize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(d) = self.nutstore_dlg.as_ref() else { return };
        let fields = [&d.server, &d.username, &d.password, &d.remote];
        let next = (index as isize + delta).clamp(0, fields.len() as isize - 1) as usize;
        let focus = fields[next].focus_handle(cx);
        window.focus(&focus, cx);
        cx.notify();
    }

    pub(crate) fn close_nutstore_connect(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.nutstore_dlg = None;
        if let Some(ed) = &self.editor { let focus = ed.read(cx).focus.clone(); window.focus(&focus, cx); }
        cx.notify();
    }

    pub(crate) fn submit_nutstore_connect(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(d) = self.nutstore_dlg.as_ref() else { return };
        let config = Config {
            server: d.server.read(cx).text.trim().to_string(),
            username: d.username.read(cx).text.trim().to_string(),
            remote_root: d.remote.read(cx).text.trim().to_string(),
        };
        let password = d.password.read(cx).text.clone();
        let error = if config.username.is_empty() {
            Some(tr("请填写坚果云账号邮箱", "Enter your Nutstore account email").to_string())
        } else {
            normalize_server(&config.server).err().map(|e| e.to_string())
                .or_else(|| clean_remote_root(&config.remote_root).err().map(|e| e.to_string()))
        };
        if let Some(error) = error {
            if let Some(d) = &mut self.nutstore_dlg { d.error = Some(error); }
            cx.notify();
            return
        }
        let root_key = d.root_key.clone();
        crate::nutstore::manager::connect(&root_key, config, Some(password), cx);
        self.nutstore_dlg = None;
        if let Some(ed) = &self.editor { let focus = ed.read(cx).focus.clone(); window.focus(&focus, cx); }
        cx.notify();
    }

    pub(crate) fn render_nutstore_connect(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let d = self.nutstore_dlg.as_ref()?;
        let field = |title: &'static str, f: Entity<TextField>, hint: &'static str| {
            div().flex().flex_col().gap(px(7.))
                .child(div().text_size(px(theme::ui_font_small())).text_color(Theme::text_caption()).child(title))
                .child(div().h(px(36.)).px(px(10.)).flex().items_center().rounded(px(8.))
                    .border_1().border_color(Theme::line_border()).bg(crate::theme::raised(Theme::bg_body()))
                    .child(f))
                .child(div().text_size(px(theme::ui_font_small())).line_height(px(theme::ui_font_small() + 5.)).text_color(Theme::text_caption()).child(hint))
        };
        let body = div().w(px(560.)).flex().flex_col()
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
            .child(div().flex().items_center().justify_between()
                .child(div().text_size(px(16.)).line_height(px(24.)).font_weight(FontWeight::SEMIBOLD).child(tr("连接坚果云", "Connect Nutstore")))
                .child(close_button("nutstore-close").on_click(cx.listener(|w, _, window, cx| w.close_nutstore_connect(window, cx)))))
            .child(div().mt(px(18.)).flex().flex_col().gap(px(14.))
                .child(field(tr("WebDAV 地址", "WebDAV address"), d.server.clone(), tr("默认 https://dav.jianguoyun.com/dav/", "Default: https://dav.jianguoyun.com/dav/")))
                .child(field(tr("账号", "Account"), d.username.clone(), tr("坚果云登录邮箱", "Your Nutstore email")))
                .child(field(tr("第三方应用密码", "Third-party app password"), d.password.clone(), tr("在坚果云 安全选项 中生成；不是登录密码；留空沿用已保存密码", "Generate an app password under Security; not your login password; leave blank to reuse the saved one")))
                .child(field(tr("远程文件夹", "Remote folder"), d.remote.clone(), tr("会在 WebDAV 根目录下使用或创建这个文件夹", "This folder is used or created below the WebDAV root"))))
            .children(d.error.as_ref().map(|e| div().mt(px(12.)).text_size(px(theme::ui_font_small())).text_color(Theme::danger()).child(e.clone())))
            .child(div().mt(px(22.)).flex().justify_end().gap(px(10.))
                .child(secondary_button("nutstore-cancel", tr("取消", "Cancel")).on_click(cx.listener(|w, _, window, cx| w.close_nutstore_connect(window, cx))))
                .child(primary_button("nutstore-ok", tr("连接并同步", "Connect and sync")).on_click(cx.listener(|w, _, window, cx| w.submit_nutstore_connect(window, cx)))));
        let body = dialog("nutstore-connect").p(px(24.)).child(body);
        Some(
            deferred(
                div().id("nutstore-mask").absolute().inset_0().flex().items_center().justify_center().bg(scrim())
                    .on_mouse_down(MouseButton::Left, cx.listener(|w, _, window, cx| w.close_nutstore_connect(window, cx)))
                    .child(body),
            )
            .with_priority(4)
            .into_any_element(),
        )
    }

    pub(crate) fn nutstore_group(&self, cx: &mut Context<Self>) -> AnyElement {
        use crate::nutstore::manager::{self, Phase};
        let Some(key) = self.store.active_root() else { return div().into_any_element() };
        let name = self.store.roots().into_iter().find(|(k, _, _, _)| k == &key).map(|(_, n, _, _)| n).unwrap_or_else(|| tr("当前文件夹", "Current folder").to_string());
        let Some(status) = manager::status_for(cx, &key) else { return div().into_any_element() };
        let (text, danger) = match &status.phase {
            Phase::SignedOut => (crate::localized_format!(
                "{} · 连接后同步正文、文件夹和附件；置顶、排序和历史记录暂留本机。",
                "{} · Sign in to sync documents, folders and attachments; pins, order and history stay on this computer.",
                name
            ), false),
            Phase::Connecting => (tr("正在连接坚果云并创建远程文件夹…", "Connecting to Nutstore and preparing the remote folder…").to_string(), false),
            Phase::Idle if status.connected => (crate::localized_format!(
                "{} · 已连接到 {}。",
                "{} · Connected to {}.",
                name,
                if status.remote_root.is_empty() { "Quiet.md" } else { &status.remote_root }
            ), false),
            Phase::Syncing => (crate::localized_format!("{} · 正在同步…", "{} · Syncing…", name), false),
            Phase::Synced(summary) => {
                let account = if status.account.is_empty() { String::new() } else { format!(" · {}", status.account) };
                (crate::localized_format!("{} · {} · {}{}", "{} · {} · {}{}", name, manager::summary_text(summary), crate::localized_format!("上次同步 {}", "Last synced {}", manager::last_sync_text(status.last_sync)), account), false)
            }
            Phase::NeedsInitialChoice(choice) => (crate::localized_format!(
                "本机有 {} 个文件，坚果云有 {} 个文件。两边都保留时会为不同内容生成冲突副本。",
                "{} files are on this computer and {} in Nutstore. Keeping both creates conflict copies where contents differ.",
                choice.local_files,
                choice.remote_files
            ), false),
            Phase::Error(error) => (crate::localized_format!("{} · {}", "{} · {}", name, error), true),
            Phase::Idle => (crate::localized_format!("{} · 坚果云还没有连接。", "{} · Nutstore is not connected.", name), false),
        };
        let mut actions = vec![];
        match &status.phase {
            Phase::SignedOut => actions.push(button(SharedString::from(format!("nutstore-connect-{key}")), tr("连接坚果云", "Connect Nutstore"))
                .on_click(cx.listener(|w, _, window, cx| w.open_nutstore_connect(window, cx))).into_any_element()),
            Phase::Idle if status.connected => {
                let k = key.clone();
                actions.push(button(SharedString::from(format!("nutstore-sync-{key}")), tr("开始同步", "Start syncing"))
                    .on_click(cx.listener(move |_, _, _, cx| manager::sync_now(&k, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-edit-{key}")), tr("设置", "Settings"))
                    .on_click(cx.listener(|w, _, window, cx| w.open_nutstore_connect(window, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-signout-{key}")), tr("退出账号", "Sign out"))
                    .on_click(cx.listener(|_, _, _, cx| manager::logout(cx))).into_any_element());
            }
            Phase::Synced(_) => {
                let k = key.clone();
                let r = key.clone();
                actions.push(button(SharedString::from(format!("nutstore-sync-{key}")), tr("立即同步", "Sync now"))
                    .on_click(cx.listener(move |_, _, _, cx| manager::sync_now(&k, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-settings-{key}")), tr("设置", "Settings"))
                    .on_click(cx.listener(|w, _, window, cx| w.open_nutstore_connect(window, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-reset-{key}")), tr("重新扫描", "Rescan"))
                    .on_click(cx.listener(move |_, _, _, cx| manager::reset_root(&r, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-signout-{key}")), tr("退出账号", "Sign out"))
                    .on_click(cx.listener(|_, _, _, cx| manager::logout(cx))).into_any_element());
            }
            Phase::NeedsInitialChoice(_) => {
                let k = key.clone();
                actions.push(button(SharedString::from(format!("nutstore-merge-{key}")), tr("合并两边", "Merge both"))
                    .on_click(cx.listener(move |_, _, _, cx| manager::merge_both(&k, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-signout-{key}")), tr("退出账号", "Sign out"))
                    .on_click(cx.listener(|_, _, _, cx| manager::logout(cx))).into_any_element());
            }
            Phase::Error(_) => {
                let k = key.clone();
                let r = key.clone();
                actions.push(button(SharedString::from(format!("nutstore-retry-{key}")), if status.connected { tr("重试", "Retry") } else { tr("连接", "Connect") })
                    .on_click(cx.listener(move |w, _, window, cx| {
                        if w.nutstore_dlg.is_none() && !status.connected { w.open_nutstore_connect(window, cx) } else { manager::sync_now(&k, cx); }
                    })).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-reset-{key}")), tr("重新扫描", "Rescan"))
                    .on_click(cx.listener(move |_, _, _, cx| manager::reset_root(&r, cx))).into_any_element());
                actions.push(button(SharedString::from(format!("nutstore-settings-{key}")), tr("设置", "Settings"))
                    .on_click(cx.listener(|w, _, window, cx| w.open_nutstore_connect(window, cx))).into_any_element());
            }
            _ => {}
        }
        let mut rows = vec![row(tr("WebDAV 同步", "WebDAV sync"), Some(text.into()),
            div().flex_none().flex().items_center().gap(px(8.)).children(actions)).into_any_element()];
        for warning in status.warnings.iter().take(4) {
            rows.push(div().px(px(4.)).text_size(px(theme::ui_font_small())).line_height(px(theme::ui_font_small() + 6.))
                .text_color(if danger { Theme::danger() } else { Theme::text_caption() }).child(warning.clone()).into_any_element());
        }
        group(Some(tr("坚果云", "Nutstore")), rows).into_any_element()
    }
}

