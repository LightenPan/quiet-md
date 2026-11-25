//! Nutstore WebDAV client. Basic auth is sent from a curl config on stdin, so
//! the account password never appears in a process command line.

use crate::http::{CurlTransport, HttpRequest, HttpResponse, HttpTransport};
use crate::nutstore::api::{RemoteEntry, WebDavApi, WebDavError, WebDavErrorKind, WebDavResult};
use crate::nutstore::state::{Config, clean_remote_root};
use base64::Engine as _;
use quick_xml::Reader;
use quick_xml::events::Event;
use std::sync::Arc;
use std::time::Duration;

const PROPFIND_BODY: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getetag/>
</d:prop></d:propfind>"#;

pub struct NutstoreClient {
    transport: Arc<dyn HttpTransport>,
    auth: String,
    server: String,
    root_parts: Vec<String>,
    base: String,
    base_path: String,
}

impl NutstoreClient {
    pub fn connect(config: &Config, password: &str) -> Result<Self, String> {
        let allow_http = config.server.starts_with("http://");
        Ok(Self::with_parts(config, password, Arc::new(CurlTransport { allow_http })))
    }

    pub fn connect_or_create(config: &Config, password: &str) -> Result<Self, String> {
        let client = Self::connect(config, password)?;
        client.ensure_root().map_err(|e| e.message)?;
        Ok(client)
    }

    pub fn with_parts(config: &Config, password: &str, transport: Arc<dyn HttpTransport>) -> Self {
        let auth = format!("Basic {}", base64::engine::general_purpose::STANDARD.encode(format!("{}:{password}", config.username)));
        let server = if config.server.ends_with('/') { config.server.clone() } else { format!("{}/", config.server) };
        let root_parts: Vec<String> = clean_remote_root(&config.remote_root).map(|p| p.split('/').map(str::to_string).collect()).unwrap_or_default();
        let encoded = root_parts.iter().map(|p| encode_part(p)).collect::<Vec<_>>().join("/");
        let base = if encoded.is_empty() { server.clone() } else { format!("{}{}/", server, encoded) };
        let server_path = url_path(&server);
        let mut decoded_parts = vec![server_path.trim_end_matches('/').to_string()];
        decoded_parts.extend(root_parts.iter().cloned());
        let base_path = format!("{}/", decoded_parts.join("/").trim_end_matches('/'));
        Self { transport, auth, server, root_parts, base, base_path }
    }

    fn url(&self, rel: &str, dir: bool) -> String {
        let mut url = self.base.trim_end_matches('/').to_string();
        for part in rel.split('/').filter(|p| !p.is_empty()) { url.push('/'); url.push_str(&encode_part(part)); }
        if dir { url.push('/'); }
        url
    }

    fn server_url(&self, rel: &str, dir: bool) -> String {
        let mut url = self.server.trim_end_matches('/').to_string();
        for part in rel.split('/').filter(|p| !p.is_empty()) { url.push('/'); url.push_str(&encode_part(part)); }
        if dir { url.push('/'); }
        url
    }

    fn send(&self, request: HttpRequest) -> WebDavResult<HttpResponse> {
        let mut request = request;
        request.headers.retain(|(k, _)| !k.eq_ignore_ascii_case("Authorization"));
        request.headers.push(("Authorization".into(), self.auth.clone()));
        let response = self.transport.send(&request).map_err(WebDavError::other)?;
        if response.status == 429 || response.status == 503 {
            let retry = response.header("retry-after").and_then(|s| s.parse::<u64>().ok()).map(Duration::from_secs);
            return Err(map_error(&response).with_retry(retry))
        }
        if response.status >= 400 { return Err(map_error(&response)) }
        Ok(response)
    }

    fn propfind(&self, rel: &str, depth: &str, dir: bool) -> WebDavResult<Vec<RemoteEntry>> {
        self.propfind_at(self.url(rel, dir), depth)
    }

    fn propfind_at(&self, url: String, depth: &str) -> WebDavResult<Vec<RemoteEntry>> {
        let response = self.send(
            HttpRequest::get(url)
                .method("PROPFIND")
                .header("Depth", depth)
                .header("Content-Type", "application/xml; charset=utf-8")
                .body(PROPFIND_BODY.as_bytes().to_vec())
                .timeout(Duration::from_secs(120)),
        )?;
        parse_multistatus(&response.body, &self.base_path)
    }

    fn stat(&self, rel: &str) -> WebDavResult<RemoteEntry> {
        let entries = self.propfind(rel, "0", false)?;
        entries.into_iter().find(|e| e.rel == rel).ok_or_else(|| WebDavError::other("WebDAV did not return the file's properties"))
    }

    fn scan_deep(&self) -> WebDavResult<Vec<RemoteEntry>> {
        self.propfind("", "infinity", true)
    }

    fn scan_levels(&self) -> WebDavResult<Vec<RemoteEntry>> {
        let mut out = vec![];
        let mut queue = vec![String::new()];
        while let Some(dir) = queue.pop() {
            let entries = self.propfind(&dir, "1", true)?;
            if entries.len() >= 750 {
                return Err(WebDavError::other("坚果云单个文件夹超过 750 项，WebDAV 无法完整分页；请拆分文件夹"));
            }
            for entry in entries {
                if entry.rel == dir { continue }
                if entry.is_dir { queue.push(entry.rel.clone()); }
                out.push(entry);
            }
        }
        Ok(out)
    }
}

impl WebDavApi for NutstoreClient {
    fn probe(&self) -> WebDavResult<()> { self.propfind("", "0", true).map(|_| ()) }

    fn ensure_root(&self) -> WebDavResult<()> {
        if self.propfind("", "0", true).is_ok() { return Ok(()) }
        for i in 0..self.root_parts.len() {
            let rel = self.root_parts[..=i].join("/");
            let url = self.server_url(&rel, true);
            match self.propfind_at(url.clone(), "0") {
                Ok(_) => continue,
                Err(e) if e.kind == WebDavErrorKind::NotFound => {
                    match self.send(HttpRequest::get(url).method("MKCOL").timeout(Duration::from_secs(60))) {
                        Ok(_) => {}
                        Err(e) if e.status == Some(405) => {}
                        Err(e) => return Err(e),
                    }
                }
                Err(e) => return Err(e),
            }
        }
        self.probe()
    }

    fn scan(&self) -> WebDavResult<Vec<RemoteEntry>> {
        match self.scan_deep() {
            Ok(entries) if entries.len() < 750 => Ok(entries),
            Ok(_) => self.scan_levels(),
            Err(e) if matches!(e.status, Some(400 | 403 | 405 | 501 | 507)) || matches!(e.kind, WebDavErrorKind::Other | WebDavErrorKind::NotFound) => self.scan_levels(),
            Err(e) => Err(e),
        }
    }

    fn get(&self, rel: &str) -> WebDavResult<Vec<u8>> {
        let response = self.send(HttpRequest::get(self.url(rel, false)).timeout(Duration::from_secs(300)))?;
        Ok(response.body)
    }

    fn put(&self, rel: &str, bytes: &[u8], expected_etag: Option<&str>) -> WebDavResult<RemoteEntry> {
        let mut request = HttpRequest::put(self.url(rel, false))
            .header("Content-Type", "application/octet-stream")
            .body(bytes.to_vec())
            .timeout(Duration::from_secs(300));
        request = match expected_etag {
            Some("") => request,
            Some(etag) => request.header("If-Match", etag),
            None => request.header("If-None-Match", "*"),
        };
        let response = self.send(request)?;
        // Most servers return the new ETag with the PUT; asking again for the
        // same properties would double the requests each upload costs.
        let Some(etag) = response.header("etag") else { return self.stat(rel) };
        Ok(RemoteEntry {
            rel: rel.into(),
            is_dir: false,
            etag: Some(etag.to_string()),
            size: response.header("content-length").and_then(|s| s.parse().ok()),
            modified: response.header("last-modified").map(str::to_string),
        })
    }

    fn create_dir(&self, rel: &str) -> WebDavResult<()> {
        match self.send(HttpRequest::get(self.url(rel, true)).method("MKCOL").timeout(Duration::from_secs(60))) {
            Ok(_) => Ok(()),
            Err(e) if e.status == Some(405) => Ok(()),
            Err(e) => Err(e),
        }
    }

    fn remove(&self, rel: &str, is_dir: bool, expected_etag: Option<&str>) -> WebDavResult<()> {
        let mut request = HttpRequest::get(self.url(rel, is_dir)).method("DELETE").timeout(Duration::from_secs(120));
        if let Some(etag) = expected_etag.filter(|e| !e.is_empty()) { request = request.header("If-Match", etag); }
        match self.send(request) {
            Ok(_) => Ok(()),
            Err(e) if e.kind == WebDavErrorKind::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    }

    fn move_entry(&self, from: &str, to: &str, is_dir: bool) -> WebDavResult<()> {
        self.send(
            HttpRequest::get(self.url(from, is_dir))
                .method("MOVE")
                .header("Destination", self.url(to, is_dir))
                .header("Overwrite", "F")
                .timeout(Duration::from_secs(120)),
        )
        .map(|_| ())
    }
}

fn map_error(response: &HttpResponse) -> WebDavError {
    let kind = match response.status {
        401 => WebDavErrorKind::Auth,
        403 => WebDavErrorKind::Auth,
        404 => WebDavErrorKind::NotFound,
        409 | 412 | 423 => WebDavErrorKind::Conflict,
        429 | 503 => WebDavErrorKind::RateLimited,
        500..=599 => WebDavErrorKind::Server,
        _ => WebDavErrorKind::Other,
    };
    let fallback = format!("坚果云 WebDAV 返回 HTTP {}", response.status);
    let body = String::from_utf8_lossy(&response.body);
    let message = body.lines().map(str::trim).find(|l| !l.is_empty() && !l.starts_with('<') && !l.starts_with("<?xml")).unwrap_or(&fallback).to_string();
    WebDavError::new(kind, message).with_status(response.status)
}

fn parse_multistatus(body: &[u8], base_path: &str) -> WebDavResult<Vec<RemoteEntry>> {
    let mut reader = Reader::from_reader(body);
    reader.config_mut().trim_text(true);
    let mut buf = Vec::new();
    let mut current: Option<PartialEntry> = None;
    let mut text_tag: Option<String> = None;
    let mut out = std::collections::BTreeMap::<String, RemoteEntry>::new();
    loop {
        match reader.read_event_into(&mut buf).map_err(|e| WebDavError::other(format!("WebDAV XML: {e}")))? {
            Event::Start(e) => {
                let tag = local_name(e.name().as_ref());
                match tag.as_str() {
                    "response" => current = Some(PartialEntry::default()),
                    "collection" => { if let Some(c) = &mut current { c.is_dir = true } }
                    "href" | "getetag" | "getcontentlength" | "getlastmodified" => text_tag = Some(tag),
                    _ => {}
                }
            }
            Event::Empty(e) => {
                if local_name(e.name().as_ref()) == "collection" && let Some(c) = &mut current { c.is_dir = true }
            }
            Event::Text(e) => {
                if let Some(tag) = &text_tag {
                    let value = e.unescape().map_err(|e| WebDavError::other(format!("WebDAV XML text: {e}")))?.trim().to_string();
                    if let Some(c) = &mut current {
                        match tag.as_str() {
                            "href" => c.href = value,
                            "getetag" => c.etag = Some(value).filter(|s| !s.is_empty()),
                            "getcontentlength" => c.size = value.parse().ok(),
                            "getlastmodified" => c.modified = Some(value).filter(|s| !s.is_empty()),
                            _ => {}
                        }
                    }
                }
            }
            Event::End(e) => {
                let tag = local_name(e.name().as_ref());
                if tag == "href" || tag == "getetag" || tag == "getcontentlength" || tag == "getlastmodified" { text_tag = None; }
                if tag == "response"
                    && let Some(c) = current.take() {
                    let Some(rel) = href_to_rel(&c.href, base_path) else { continue };
                    if rel.is_empty() { continue }
                    if !crate::nutstore::state::safe_remote_path(&rel) { return Err(WebDavError::other(format!("WebDAV 返回了不安全的路径：{rel}"))) }
                    if let Some(prev) = out.get(&rel) && prev.is_dir != c.is_dir {
                        return Err(WebDavError::other(format!("WebDAV 同一路径同时是文件和文件夹：{rel}")))
                    }
                    out.insert(rel.clone(), RemoteEntry { rel, is_dir: c.is_dir, etag: c.etag, size: c.size, modified: c.modified });
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    Ok(out.into_values().collect())
}

#[derive(Default)]
struct PartialEntry {
    href: String,
    is_dir: bool,
    etag: Option<String>,
    size: Option<u64>,
    modified: Option<String>,
}

fn local_name(raw: &[u8]) -> String {
    let name = raw.rsplit(|b| *b == b':').next().unwrap_or(raw);
    String::from_utf8_lossy(name).to_ascii_lowercase()
}

fn href_to_rel(href: &str, base_path: &str) -> Option<String> {
    let href = href.trim();
    if href.is_empty() { return None }
    let path = if let Some(rest) = href.split_once("://").map(|(_, r)| r) {
        rest.find('/').map(|i| &rest[i..]).unwrap_or("/")
    } else { href };
    let path = path.split(['?', '#']).next().unwrap_or(path);
    let decoded = percent_decode(path);
    let base = base_path.trim_end_matches('/');
    let rel = decoded.strip_prefix(base)?;
    let rel = crate::sync_util::nfc_path(rel.trim_matches('/'));
    Some(rel)
}

fn url_path(url: &str) -> String {
    let after = url.split_once("://").map(|(_, r)| r).unwrap_or(url);
    let path = after.find('/').map(|i| &after[i..]).unwrap_or("/");
    percent_decode(path.split(['?', '#']).next().unwrap_or(path))
}

fn encode_part(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') { out.push(b as char) }
        else { out.push_str(&format!("%{b:02X}")) }
    }
    out
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len()
            && let Ok(v) = u8::from_str_radix(std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or(""), 16) {
            out.push(v); i += 3; continue
        }
        out.push(b[i]); i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

trait MethodExt {
    fn method(self, method: &str) -> Self;
}
impl MethodExt for HttpRequest {
    fn method(mut self, method: &str) -> Self { self.method = method.to_string(); self }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::HttpResponse;
    use std::sync::Mutex;

    struct Mock {
        replies: Mutex<Vec<HttpResponse>>,
        seen: Mutex<Vec<HttpRequest>>,
    }
    impl HttpTransport for Mock {
        fn send(&self, request: &HttpRequest) -> Result<HttpResponse, String> {
            self.seen.lock().unwrap().push(request.clone());
            Ok(self.replies.lock().unwrap().remove(0))
        }
    }

    fn response(status: u16, body: &str) -> HttpResponse { HttpResponse { status, headers: vec![], body: body.as_bytes().to_vec() } }
    fn config() -> Config { Config { server: "https://dav.example/dav/".into(), username: "a@b".into(), remote_root: "Quiet.md".into() } }
    fn client(mock: Arc<Mock>) -> NutstoreClient { NutstoreClient::with_parts(&config(), "app-password", mock) }

    const LIST: &str = r#"<?xml version="1.0"?>
<D:multistatus xmlns:D="DAV:">
  <D:response><D:href>/dav/Quiet.md/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/Quiet.md/Work/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/dav/Quiet.md/Work/%E5%91%A8%E6%8A%A5.md</D:href><D:propstat><D:prop><D:resourcetype/><D:getetag>"e1"</D:getetag><D:getcontentlength>12</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"#;

    #[test]
    fn parses_multistatus_paths_and_collections() {
        let entries = parse_multistatus(LIST.as_bytes(), "/dav/Quiet.md/").unwrap();
        assert!(entries.iter().any(|e| e.rel == "Work" && e.is_dir));
        let file = entries.iter().find(|e| e.rel == "Work/周报.md").unwrap();
        assert!(!file.is_dir);
        assert_eq!(file.etag.as_deref(), Some("\"e1\""));
        assert_eq!(file.size, Some(12));
    }

    #[test]
    fn basic_auth_is_a_header_and_the_password_is_not_in_the_url() {
        let mock = Arc::new(Mock {
            replies: Mutex::new(vec![response(207, LIST)]),
            seen: Mutex::new(vec![]),
        });
        client(mock.clone()).probe().unwrap();
        let seen = mock.seen.lock().unwrap();
        assert!(seen[0].headers.iter().any(|(k, v)| k == "Authorization" && v.starts_with("Basic ")));
        assert!(!seen[0].url.contains("app-password"));
        assert!(seen[0].url.starts_with("https://dav.example/dav/Quiet.md/"));
    }

    #[test]
    fn put_uses_if_match_and_create_uses_if_none_match() {
        let mock = Arc::new(Mock {
            replies: Mutex::new(vec![
                response(204, ""),
                response(207, r#"<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/Quiet.md/a.md</D:href><D:propstat><D:prop><D:resourcetype/><D:getetag>"new"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"#),
                response(201, ""),
                response(207, r#"<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/Quiet.md/b.md</D:href><D:propstat><D:prop><D:resourcetype/><D:getetag>"new2"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"#),
            ]),
            seen: Mutex::new(vec![]),
        });
        let c = client(mock.clone());
        c.put("a.md", b"a", Some("\"old\"")).unwrap();
        c.put("b.md", b"b", None).unwrap();
        let seen = mock.seen.lock().unwrap();
        assert_eq!(seen[0].header_value("If-Match"), Some("\"old\""));
        assert_eq!(seen[2].header_value("If-None-Match"), Some("*"));
    }

    #[test]
    fn put_uses_the_response_etag_instead_of_asking_again() {
        let mock = Arc::new(Mock {
            replies: Mutex::new(vec![HttpResponse {
                status: 201,
                headers: vec![
                    ("ETag".into(), "\"new\"".into()),
                    ("Content-Length".into(), "1".into()),
                    ("Last-Modified".into(), "Wed, 25 Nov 2026 15:00:00 GMT".into()),
                ],
                body: vec![],
            }]),
            seen: Mutex::new(vec![]),
        });
        let entry = client(mock.clone()).put("a.md", b"a", None).unwrap();
        assert_eq!(entry.etag.as_deref(), Some("\"new\""));
        assert_eq!(entry.size, Some(1));
        assert_eq!(mock.seen.lock().unwrap().len(), 1);
    }

    #[test]
    fn a_503_is_a_rate_limit_with_the_server_retry_after() {
        let mock = Arc::new(Mock {
            replies: Mutex::new(vec![HttpResponse {
                status: 503,
                headers: vec![("Retry-After".into(), "42".into())],
                body: b"<?xml version=\"1.0\"?><d:error xmlns:d=\"DAV:\" xmlns:s=\"http://ns.jianguoyun.com\"><s:exception>BlockedTemporarily</s:exception><s:message>Too many requests are received recently</s:message></d:error>".to_vec(),
            }]),
            seen: Mutex::new(vec![]),
        });
        let error = client(mock).probe().unwrap_err();
        assert_eq!(error.kind, WebDavErrorKind::RateLimited);
        assert_eq!(error.status, Some(503));
        assert_eq!(error.retry_after, Some(Duration::from_secs(42)));
    }

    #[test]
    fn missing_remote_folders_are_created_segment_by_segment() {
        let mock = Arc::new(Mock {
            replies: Mutex::new(vec![
                response(404, ""),
                response(404, ""),
                response(201, ""),
                response(404, ""),
                response(201, ""),
                response(207, LIST),
            ]),
            seen: Mutex::new(vec![]),
        });
        let cfg = Config { remote_root: "Quiet.md/Notes".into(), ..config() };
        let c = NutstoreClient::with_parts(&cfg, "pw", mock.clone());
        c.ensure_root().unwrap();
        let seen = mock.seen.lock().unwrap();
        assert_eq!(seen[2].method, "MKCOL");
        assert!(seen[2].url.ends_with("/Quiet.md/"));
        assert_eq!(seen[4].method, "MKCOL");
        assert!(seen[4].url.ends_with("/Quiet.md/Notes/"));
    }
}

