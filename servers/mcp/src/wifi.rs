// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//! Phones on the same Wi-Fi (roadmap R24, ADR-042).
//!
//! With `LATCH_WIFI=1` the gateway also listens on the local network with
//! TLS, so a phone on the same Wi-Fi can connect to a gateway running on the
//! owner's computer, for example the one an AI app starts with
//! `latch-gateway stdio`. Nothing then leaves the home network.
//!
//! A home network has no certificate authority, so the gateway makes its own
//! certificate once and keeps it in `LATCH_DATA_DIR/wifi/`. The phone trusts
//! exactly that certificate: the address the owner enters carries the
//! SHA-256 of its public key (`#pin=…`), and the app accepts no other. The
//! pin is not a secret; the private key never leaves the data directory.

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::service::TowerToHyperService;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;

/// Default address of the Wi-Fi listener.
pub const DEFAULT_BIND: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 8788);
/// How long a TLS handshake may take before the connection is dropped.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// The address a phone used on the Wi-Fi listener, e.g. `https://192.168.1.5:8788`.
/// File links for that phone are built on it, so they match its paired address.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WifiOrigin(pub String);

/// The listener's TLS setup and the pin phones check.
pub struct WifiTls {
    pub config: Arc<rustls::ServerConfig>,
    /// Base64url (no padding) SHA-256 of the certificate's SubjectPublicKeyInfo.
    pub pin: String,
}

/// The pin of a public key: base64url of the SHA-256 of its SubjectPublicKeyInfo DER.
pub fn pin_of(spki_der: &[u8]) -> String {
    crate::oauth::base64url(&Sha256::digest(spki_der))
}

/// Loads the certificate from `data_dir/wifi`, or makes and saves one.
pub fn load_or_create(data_dir: &Path) -> io::Result<WifiTls> {
    let dir = data_dir.join("wifi");
    let cert_path = dir.join("cert.der");
    let key_path = dir.join("key.der");
    let other = |e: rcgen::Error| io::Error::other(e.to_string());
    let (cert_der, key_der) = match (std::fs::read(&cert_path), std::fs::read(&key_path)) {
        (Ok(cert), Ok(key)) => (cert, key),
        _ => {
            let key = rcgen::KeyPair::generate().map_err(other)?;
            let params = rcgen::CertificateParams::new(vec!["latch-gateway.local".to_owned()])
                .map_err(other)?;
            let cert = params.self_signed(&key).map_err(other)?;
            std::fs::create_dir_all(&dir)?;
            write_private(&key_path, &key.serialize_der())?;
            std::fs::write(&cert_path, cert.der())?;
            (cert.der().to_vec(), key.serialize_der())
        }
    };
    let key = rcgen::KeyPair::try_from(key_der.as_slice()).map_err(other)?;
    let pin = pin_of(&rcgen::PublicKeyData::subject_public_key_info(&key));
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(io::Error::other)?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(cert_der)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der)),
        )
        .map_err(io::Error::other)?;
    // WebSockets need HTTP/1.1.
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(WifiTls {
        config: Arc::new(config),
        pin,
    })
}

/// The pin of the certificate a running gateway made, without making one.
pub fn existing_pin(data_dir: &Path) -> io::Result<String> {
    let key_der = std::fs::read(data_dir.join("wifi").join("key.der"))?;
    let key = rcgen::KeyPair::try_from(key_der.as_slice())
        .map_err(|e| io::Error::other(e.to_string()))?;
    Ok(pin_of(&rcgen::PublicKeyData::subject_public_key_info(&key)))
}

/// Writes a file only its owner can read, from the moment it exists.
fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)?.write_all(bytes)
}

/// This computer's address on the local network, if it has one: the source
/// address of the default route, found without sending a packet.
pub fn lan_ip() -> Option<Ipv4Addr> {
    let socket = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    // TEST-NET-1: connecting a UDP socket only picks a route, nothing is sent.
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if ip.is_private() || ip.is_link_local() => Some(ip),
        _ => None,
    }
}

/// The address the owner enters in the Latch app, with the pin.
pub fn phone_address(ip: Ipv4Addr, port: u16, pin: &str) -> String {
    format!("https://{ip}:{port}#pin={pin}")
}

/// Serves `app` over TLS on `listener` until the process ends. Each request
/// carries the [`WifiOrigin`] its connection used.
pub async fn serve(listener: TcpListener, tls: Arc<rustls::ServerConfig>, app: Router) {
    let acceptor = TlsAcceptor::from(tls);
    loop {
        let (tcp, _) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(_) => {
                // Out of file descriptors and the like: wait instead of spinning.
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let origin = tcp
            .local_addr()
            .ok()
            .map(|a| WifiOrigin(format!("https://{a}")));
        let acceptor = acceptor.clone();
        let app = app.clone();
        tokio::spawn(async move {
            let Ok(Ok(stream)) =
                tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(tcp)).await
            else {
                return;
            };
            let app = match origin {
                Some(origin) => app.layer(axum::Extension(origin)),
                None => app,
            };
            let service = TowerToHyperService::new(app);
            let _ = hyper_util::server::conn::auto::Builder::new(TokioExecutor::new())
                .serve_connection_with_upgrades(TokioIo::new(stream), service)
                .await;
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_certificate_is_made_once_and_its_pin_stays() {
        let dir = std::env::temp_dir().join(format!(
            "latch-wifi-test-{}",
            crate::secret::hex(&crate::secret::random_bytes::<8>())
        ));
        let first = load_or_create(&dir).expect("create");
        let again = load_or_create(&dir).expect("load");
        assert_eq!(first.pin, again.pin, "the phone's pin survives a restart");
        assert_eq!(existing_pin(&dir).expect("pin"), first.pin);
        assert_eq!(first.pin.len(), 43, "base64url of 32 bytes");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.join("wifi/key.der"))
                .expect("key")
                .permissions()
                .mode();
            assert_eq!(
                mode & 0o077,
                0,
                "the private key is readable by its owner only"
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A client that, like the app, trusts exactly the pinned key.
    #[derive(Debug)]
    struct Pinned(Vec<u8>, Arc<rustls::crypto::CryptoProvider>);

    impl rustls::client::danger::ServerCertVerifier for Pinned {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _: &[CertificateDer<'_>],
            _: &rustls::pki_types::ServerName<'_>,
            _: &[u8],
            _: rustls::pki_types::UnixTime,
        ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
            if end_entity.as_ref() == self.0.as_slice() {
                Ok(rustls::client::danger::ServerCertVerified::assertion())
            } else {
                Err(rustls::Error::General("not the pinned certificate".into()))
            }
        }
        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls12_signature(
                message,
                cert,
                dss,
                &self.1.signature_verification_algorithms,
            )
        }
        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &rustls::DigitallySignedStruct,
        ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(
                message,
                cert,
                dss,
                &self.1.signature_verification_algorithms,
            )
        }
        fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
            self.1.signature_verification_algorithms.supported_schemes()
        }
    }

    #[tokio::test]
    async fn phones_reach_the_gateway_over_tls_and_the_pin_names_its_key() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let dir = std::env::temp_dir().join(format!(
            "latch-wifi-tls-{}",
            crate::secret::hex(&crate::secret::random_bytes::<8>())
        ));
        let tls = load_or_create(&dir).expect("tls");
        let cert = std::fs::read(dir.join("wifi/cert.der")).expect("cert");
        // The pin is the SHA-256 of the SubjectPublicKeyInfo inside the certificate,
        // which is what Android's `certificate.publicKey.encoded` gives the app.
        let key = rcgen::KeyPair::try_from(
            std::fs::read(dir.join("wifi/key.der"))
                .expect("key")
                .as_slice(),
        )
        .expect("key");
        let spki = rcgen::PublicKeyData::subject_public_key_info(&key);
        assert!(cert.windows(spki.len()).any(|w| w == spki.as_slice()));
        assert_eq!(pin_of(&spki), tls.pin);

        let seen = Arc::new(std::sync::Mutex::new(None::<String>));
        let record = seen.clone();
        let app = Router::new().route(
            "/healthz",
            axum::routing::get(move |origin: Option<axum::Extension<WifiOrigin>>| {
                *record.lock().expect("lock") = origin.map(|o| o.0.0);
                async { "ok" }
            }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        let server = tokio::spawn(serve(listener, tls.config, app));

        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let client = rustls::ClientConfig::builder_with_provider(provider.clone())
            .with_safe_default_protocol_versions()
            .expect("versions")
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pinned(cert, provider)))
            .with_no_client_auth();
        let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        let name = rustls::pki_types::ServerName::try_from("127.0.0.1").expect("name");
        let mut stream = tokio_rustls::TlsConnector::from(Arc::new(client))
            .connect(name, tcp)
            .await
            .expect("handshake");
        stream
            .write_all(b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            .await
            .expect("write");
        let mut response = Vec::new();
        let _ = stream.read_to_end(&mut response).await;
        let response = String::from_utf8_lossy(&response);
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        assert_eq!(
            seen.lock().expect("lock").clone(),
            Some(format!("https://127.0.0.1:{port}")),
            "requests know the Wi-Fi address the phone used"
        );

        // A plain-HTTP request on the TLS port gets nothing.
        let mut plain = tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("connect");
        plain
            .write_all(b"GET /healthz HTTP/1.1\r\n\r\n")
            .await
            .expect("write");
        let mut answer = Vec::new();
        let _ = plain.read_to_end(&mut answer).await;
        assert!(!String::from_utf8_lossy(&answer).contains("200 OK"));
        server.abort();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_phone_address_carries_the_pin() {
        assert_eq!(
            phone_address(Ipv4Addr::new(192, 168, 1, 5), 8788, "abc"),
            "https://192.168.1.5:8788#pin=abc"
        );
    }
}
