//! Host-side connection entry points.
use crate::transport::tcp::{self, TransportError};
use tokio::net::TcpListener;

/// Accept and serve one client connection.
pub async fn serve_one(listener: &TcpListener) -> Result<(), TransportError> {
    tcp::serve_one(listener).await
}
