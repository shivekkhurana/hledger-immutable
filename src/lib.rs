//! Reusable accounting and immutable event-log functionality.

pub mod importer;
pub mod ledger;
pub mod options;
pub mod store;

/// Error type used by the asynchronous library API.
pub type Error = Box<dyn std::error::Error + Send + Sync + 'static>;

pub use options::{FilterOptions, ListOptions, Period, ReportOptions};
pub use store::{HashConflictError, Store};
