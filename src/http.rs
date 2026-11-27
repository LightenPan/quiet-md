//! Small blocking HTTP surface for WebDAV sync. Production uses the system
//! `curl` (also used by updates and fonts); tests can replace the transport.

use std::io::Write;
use std::process::Stdio;
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone)]
pub struct HttpRequest {
    pub method: String,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    pub timeout: Duration,
}

impl HttpRequest {
    pub fn get(url: impl Into<String>) -> Self {
        Self { method: "GET".into(), url: url.into(), headers: vec![], body: vec![], timeout: Duration::from_secs(60) }
    }
    #[cfg(test)]
    pub fn post(url: impl Into<String>) -> Self {
        Self { method: "POST".into(), url: url.into(), headers: vec![], body: vec![], timeout: Duration::from_secs(60) }
    }
    pub fn put(url: impl Into<String>) -> Self {
        Self { method: "PUT".into(), url: url.into(), headers: vec![], body: vec![], timeout: Duration::from_secs(60) }
    }
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }
    pub fn body(mut self, body: Vec<u8>) -> Self {
        self.body = body;
        self
    }
    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }
    #[cfg(test)]
    pub fn header_value(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

#[derive(Clone)]
pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

pub trait HttpTransport: Send + Sync {
    fn send(&self, request: &HttpRequest) -> Result<HttpResponse, String>;
}

#[derive(Clone, Copy, Debug, Default)]
pub struct CurlTransport {
    /// Test servers may speak plain HTTP; production never does.
    pub allow_http: bool,
}

impl HttpTransport for CurlTransport {
    fn send(&self, request: &HttpRequest) -> Result<HttpResponse, String> {
        pace();
        let tag = uuid::Uuid::new_v4();
        let dir = std::env::temp_dir();
        let request_body = dir.join(format!("quiet-md-http-{tag}.request"));
        let response_body = dir.join(format!("quiet-md-http-{tag}.response"));
        let response_headers = dir.join(format!("quiet-md-http-{tag}.headers"));
        let cleanup = || {
            let _ = std::fs::remove_file(&request_body);
            let _ = std::fs::remove_file(&response_body);
            let _ = std::fs::remove_file(&response_headers);
        };
        if !request.body.is_empty() { write_private(&request_body, &request.body).map_err(|e| format!("curl request body: {e}"))?; }
        // Pre-create response files so curl keeps the private mode on Unix.
        write_private(&response_body, &[]).map_err(|e| format!("curl response body: {e}"))?;
        write_private(&response_headers, &[]).map_err(|e| format!("curl response headers: {e}"))?;

        // `curl_command` carries the platform proxy setup. Its `-fsSL` would
        // hide error bodies, so replace it with `-sS -L`.
        let base = crate::desktop::update::curl_command(self.allow_http);
        let mut command = crate::desktop::update::command(base.get_program());
        for arg in base.get_args() {
            let text = arg.to_string_lossy();
            if text != "-fsSL" { command.arg(arg); }
        }
        command.args(["-sS", "-L", "--request"]).arg(&request.method)
            .arg("--connect-timeout").arg("15")
            .arg("--max-time").arg(request.timeout.as_secs().max(1).to_string())
            .arg("--output").arg(&response_body)
            .arg("--dump-header").arg(&response_headers)
            .arg("--write-out").arg("%{http_code}")
            .arg("--config").arg("-")
            .arg("--url").arg(&request.url)
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
        if !request.body.is_empty() {
            command.arg("--data-binary").arg(format!("@{}", request_body.display()));
        }

        let mut child = command.spawn().map_err(|e| {
            cleanup();
            format!("curl: {e}")
        })?;
        if let Some(mut stdin) = child.stdin.take() {
            for (name, value) in &request.headers {
                if name.contains(['\r', '\n']) || value.contains(['\r', '\n']) {
                    cleanup();
                    return Err("HTTP header contains a newline".into());
                }
                let quoted = value.replace('\\', "\\\\").replace('"', "\\\"");
                writeln!(stdin, "header = \"{}: {}\"", name, quoted).map_err(|e| e.to_string())?;
            }
        }
        let output = child.wait_with_output().map_err(|e| format!("curl: {e}"))?;
        let code = String::from_utf8_lossy(&output.stdout).trim().parse::<u16>().unwrap_or(0);
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if code == 0 {
            cleanup();
            return Err(if stderr.is_empty() { "curl returned no HTTP status".into() } else { stderr });
        }
        let headers = std::fs::read(&response_headers).ok().map(|b| parse_headers(&String::from_utf8_lossy(&b))).unwrap_or_default();
        let body = std::fs::read(&response_body).unwrap_or_default();
        cleanup();
        Ok(HttpResponse { status: code, headers, body })
    }
}

/// Nutstore allows about 600 WebDAV requests per 30 minutes (1500 on paid
/// plans). Space requests apart so a large first sync cannot use up the window
/// in one burst and get the account blocked (`BlockedTemporarily`).
const MIN_REQUEST_GAP: Duration = Duration::from_secs(4);

fn pace() {
    if cfg!(test) { return }
    static LAST: Mutex<Option<Instant>> = Mutex::new(None);
    let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(at) = *last {
        let wait = MIN_REQUEST_GAP.saturating_sub(at.elapsed());
        if !wait.is_zero() { std::thread::sleep(wait); }
    }
    *last = Some(Instant::now());
}

fn parse_headers(text: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if line.starts_with("HTTP/") {
            // Redirects leave several response heads in the dump; only the
            // headers of the final response belong to the body we kept.
            out.clear();
            continue;
        }
        if line.is_empty() || line.starts_with(' ') || line.starts_with('\t') { continue }
        if let Some((name, value)) = line.split_once(':') {
            let name = name.trim();
            out.push((name.to_string(), value.trim().to_string()));
        }
    }
    out
}

fn write_private(path: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write as _;
    let mut options = std::fs::OpenOptions::new();
    options.create(true).truncate(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    #[test]
    fn parses_headers_case_insensitively() {
        let headers = parse_headers("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nRetry-After: 3\r\n\r\n");
        assert_eq!(headers, vec![("Content-Type".into(), "application/json".into()), ("Retry-After".into(), "3".into())]);
    }

    #[test]
    fn curl_transport_sends_a_request_without_a_token_in_argv() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0u8; 8192];
            let _ = stream.read(&mut buf);
            stream.write_all(b"HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nX-Test: yes\r\nContent-Length: 8\r\n\r\n{\"ok\":1}").unwrap();
            String::from_utf8_lossy(&buf).contains("Authorization: Bearer do-not-log")
        });
        let request = HttpRequest::post(format!("http://{addr}/x"))
            .header("Authorization", "Bearer do-not-log")
            .header("Content-Type", "application/json")
            .body(br#"{"a":1}"#.to_vec());
        let response = CurlTransport { allow_http: true }.send(&request).unwrap();
        assert_eq!(response.status, 201);
        assert_eq!(response.header("x-test"), Some("yes"));
        assert_eq!(response.body, br#"{"ok":1}"#);
        assert!(server.join().unwrap());
    }
}

