mod importer;
mod ledger;
mod store;

use std::{path::PathBuf, process::ExitCode};

use clap::{Args, CommandFactory, Parser, Subcommand, ValueEnum};
use serde_json::{Value, json};
use store::Store;

#[derive(Debug, Parser)]
#[command(
    name = "hledger-immutable",
    version,
    about = "JSON accounting reports over an immutable SQLite event log",
    disable_help_flag = false
)]
struct Cli {
    /// Workspace directory containing immutable.sqlite.
    #[arg(short, long, global = true, default_value = ".")]
    workspace: PathBuf,

    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// List declared and used account names.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books accounts --file main.journal"#)]
    Accounts(ListArgs),
    /// List declared and used commodities.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books commodities --file main.journal"#)]
    Commodities(FilterArgs),
    /// Return transactions as structured JSON.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books print --file main.journal --tag project=alpha"#)]
    Print(FilterArgs),
    /// Report account balance changes (alias: bal).
    #[command(
        name = "balance",
        alias = "bal",
        after_help = r#"Examples:
  ./target/release/hledger-immutable --workspace ./books balance --file main.journal
  ./target/release/hledger-immutable --workspace ./books balance --file main.journal -X USD --monthly -p 2025"#
    )]
    Balance(ReportArgs),
    /// Report assets and liabilities.
    #[command(
        name = "balancesheet",
        alias = "bs",
        after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books bs --file main.journal -X USD"#
    )]
    BalanceSheet(ReportArgs),
    /// Report income and expenses.
    #[command(
        name = "incomestatement",
        alias = "is",
        after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books is --file main.journal --yearly -p 2025 -X USD"#
    )]
    IncomeStatement(ReportArgs),
    /// Report postings and running balances (alias: reg).
    #[command(
        alias = "reg",
        after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books reg --file main.journal -b 2025-01-01 -e 2025-02-01"#
    )]
    Register(FilterArgs),
    /// Show event-log and Rust API status.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books status"#)]
    Status,
    /// Append a JSON transaction to the immutable event log.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books add-transaction --writer-external-id ui-user-42 --data '{"lastHash":"HASH_FROM_STATUS","file":"personal.journal","date":"2025-04-03","description":"Groceries","postings":[{"account":"Expenses:Food","amount":"INR 500"},{"account":"Assets:Cash","amount":"INR -500"}]}'"#)]
    AddTransaction(JsonData),
    /// Append a JSON account declaration.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books add-account --data '{"lastHash":"HASH_FROM_STATUS","file":"accounts.journal","name":"Assets:Cash"}'"#)]
    AddAccount(JsonData),
    /// Append a JSON commodity declaration.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books add-commodity --data '{"lastHash":"HASH_FROM_STATUS","file":"accounts.journal","name":"INR"}'"#)]
    AddCommodity(JsonData),
    /// Append a JSON market-price record.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books add-price --data '{"lastHash":"HASH_FROM_STATUS","file":"prices.journal","date":"2025-04-03","commodity":"BTC","value":"USD 84000"}'"#)]
    AddPrice(JsonData),
    /// Append a JSON logical source-group include relationship.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books add-include --data '{"lastHash":"HASH_FROM_STATUS","file":"main.journal","path":"prices.journal"}'"#)]
    AddInclude(JsonData),
    /// Safely replace one entity attribute when the workspace hash matches.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books update --data '{"eid":42,"attr":"posting/amount","lastHash":"HASH_FROM_STATUS","value":"USD -90"}'"#)]
    Update(JsonData),
    /// Logically delete an entity and its owned child entities.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books delete --eid 42 --last-hash HASH_FROM_STATUS --writer-external-id ui-user-42"#)]
    Delete(DeleteArgs),
    /// Read .journal files from a source folder and append them to an empty event log.
    #[command(after_help = r#"Example:
  ./target/release/hledger-immutable --workspace ./books import-journals --source ./journals --last-hash HASH_FROM_STATUS --writer-external-id ui-user-42

The source folder is read-only. The importer scans its .journal files recursively,
preserves relative source-group names and include relationships, and refuses to
append if the workspace event log already contains datoms."#)]
    ImportJournals(ImportArgs),
}

#[derive(Debug, Args)]
struct JsonData {
    /// JSON object describing the entity.
    #[arg(short, long)]
    data: String,
    /// External identity of the writer; copied to every datom produced by this command.
    #[arg(long = "writer-external-id")]
    writer_external_id: Option<String>,
}

#[derive(Debug, Args)]
struct ImportArgs {
    /// Folder containing source .journal files. Files are never modified.
    #[arg(short, long)]
    source: PathBuf,
    /// Workspace hash returned by status, required to guard this write.
    #[arg(long = "last-hash")]
    last_hash: String,
    /// External identity of the writer; copied to every imported datom.
    #[arg(long = "writer-external-id")]
    writer_external_id: Option<String>,
}

#[derive(Debug, Args)]
struct DeleteArgs {
    /// Entity id to delete.
    #[arg(long)]
    eid: i64,
    /// Workspace hash returned by status, required to guard this write.
    #[arg(long = "last-hash")]
    last_hash: String,
    /// External identity of the writer; copied to every retraction datom.
    #[arg(long = "writer-external-id")]
    writer_external_id: Option<String>,
}

#[derive(Debug, Args)]
struct ListArgs {
    #[command(flatten)]
    filters: FilterArgs,
    /// Keep only account names up to this many components.
    #[arg(long)]
    depth: Option<usize>,
}

#[derive(Debug, Args, Clone, Default)]
struct FilterArgs {
    /// Logical source-group key already stored in entity/file datoms. May repeat.
    #[arg(short = 'F', long = "file")]
    files: Vec<String>,
    /// Account prefix or regular expression. May repeat.
    #[arg(short = 'A', long = "account")]
    accounts: Vec<String>,
    /// Exclude account prefix or regular expression. May repeat.
    #[arg(long = "not-account")]
    not_accounts: Vec<String>,
    /// Hledger-style account query terms, including `not:ACCOUNT` exclusions.
    #[arg(value_name = "QUERY")]
    query: Vec<String>,
    /// Require a transaction or entity tag, written as NAME or NAME=VALUE.
    #[arg(long = "tag")]
    tags: Vec<String>,
    /// Include entries on or after this ISO date.
    #[arg(short, long)]
    begin: Option<String>,
    /// Exclude entries on or after this ISO date.
    #[arg(short, long)]
    end: Option<String>,
    /// Restrict to a calendar year (`2025`), month (`2025-03`), or half-open date range (`START..END`).
    #[arg(short = 'p', long = "period", value_name = "PERIOD")]
    date_period: Option<String>,
}

#[derive(Debug, Args)]
struct ReportArgs {
    #[command(flatten)]
    filters: FilterArgs,
    /// Calendar grouping for a multi-period report.
    #[arg(long = "interval", value_enum, conflicts_with_all = ["daily", "weekly", "monthly", "quarterly", "yearly"])]
    interval_period: Option<Period>,
    #[arg(short = 'D', long, conflicts_with_all = ["interval_period", "weekly", "monthly", "quarterly", "yearly"])]
    daily: bool,
    #[arg(short = 'W', long, conflicts_with_all = ["interval_period", "daily", "monthly", "quarterly", "yearly"])]
    weekly: bool,
    #[arg(short = 'M', long, conflicts_with_all = ["interval_period", "daily", "weekly", "quarterly", "yearly"])]
    monthly: bool,
    #[arg(short = 'Q', long, conflicts_with_all = ["interval_period", "daily", "weekly", "monthly", "yearly"])]
    quarterly: bool,
    #[arg(short = 'Y', long, conflicts_with_all = ["interval_period", "daily", "weekly", "monthly", "quarterly"])]
    yearly: bool,
    /// Limit account hierarchy depth.
    #[arg(long)]
    depth: Option<usize>,
    /// Convert values through dated market prices into this commodity.
    #[arg(short = 'X', long)]
    exchange: Option<String>,
    /// Use per-posting cost annotations.
    #[arg(short = 'B', long)]
    cost: bool,
    /// Convert using market prices at each report end date.
    #[arg(short = 'V', long)]
    market: bool,
    /// Include zero-balance accounts.
    #[arg(short = 'E', long)]
    empty: bool,
    /// Build historical period balances instead of period changes.
    #[arg(long)]
    historical: bool,
    /// Date used for current-value reports; defaults to today's local date.
    #[arg(long)]
    today: Option<String>,
    /// Use flattened account names (JSON reports are flat by default).
    #[arg(long)]
    flat: bool,
    /// JSON-compatible output layout. `bare` is retained for Makefile compatibility.
    #[arg(long, value_parser = ["bare"])]
    layout: Option<String>,
    /// Omit total rows (reports currently return period/account rows only).
    #[arg(long)]
    no_total: bool,
    /// Return account rows with period values nested under each account.
    #[arg(long)]
    transpose: bool,
    /// Commodity display-style hint, such as `USD 1`; values remain exact JSON decimals.
    #[arg(short = 'c', long = "commodity-style")]
    commodity_style: Option<String>,
}

impl ReportArgs {
    fn interval(&self) -> Option<Period> {
        self.interval_period.or_else(|| {
            if self.daily {
                Some(Period::Daily)
            } else if self.weekly {
                Some(Period::Weekly)
            } else if self.monthly {
                Some(Period::Monthly)
            } else if self.quarterly {
                Some(Period::Quarterly)
            } else if self.yearly {
                Some(Period::Yearly)
            } else {
                None
            }
        })
    }
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum Period {
    Daily,
    Weekly,
    Monthly,
    Quarterly,
    Yearly,
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = match Cli::try_parse() {
        Ok(cli) => cli,
        Err(error) => {
            let code = error.exit_code();
            if matches!(
                error.kind(),
                clap::error::ErrorKind::DisplayHelp | clap::error::ErrorKind::DisplayVersion
            ) {
                print!("{error}");
                return ExitCode::SUCCESS;
            }
            let output = match error.kind() {
                _ => json!({
                    "error": {
                        "kind": format!("{:?}", error.kind()),
                        "message": error.to_string()
                    }
                }),
            };
            println!("{}", output);
            return ExitCode::from(code as u8);
        }
    };
    match run(cli).await {
        Ok(Some(value)) => {
            println!(
                "{}",
                serde_json::to_string(&value).unwrap_or_else(|_| "{}".into())
            );
            ExitCode::SUCCESS
        }
        Ok(None) => {
            print!("{}", Cli::command().render_long_help());
            ExitCode::SUCCESS
        }
        Err(error) => {
            let output = if let Some(conflict) = error.downcast_ref::<store::HashConflictError>() {
                json!({
                    "error": {
                        "kind": "hash_conflict",
                        "message": conflict.to_string(),
                        "expected": conflict.expected,
                        "current": conflict.current
                    }
                })
            } else {
                json!({"error": {"message": error.to_string()}})
            };
            println!("{output}");
            ExitCode::FAILURE
        }
    }
}

async fn run(cli: Cli) -> Result<Option<Value>, Box<dyn std::error::Error>> {
    let Some(command) = cli.command else {
        return Ok(None);
    };

    let mut store = Store::open(&cli.workspace).await?;
    let value = match command {
        Command::Status => store.status().await?,
        Command::Accounts(args) => ledger::accounts(&mut store, &args).await?,
        Command::Commodities(filters) => ledger::commodities(&mut store, &filters).await?,
        Command::Print(filters) => ledger::print(&mut store, &filters).await?,
        Command::Register(filters) => ledger::register(&mut store, &filters).await?,
        Command::Balance(args) => {
            ledger::balance(&mut store, &args, ledger::ReportKind::Balance).await?
        }
        Command::BalanceSheet(args) => {
            ledger::balance(&mut store, &args, ledger::ReportKind::BalanceSheet).await?
        }
        Command::IncomeStatement(args) => {
            ledger::balance(&mut store, &args, ledger::ReportKind::IncomeStatement).await?
        }
        Command::AddTransaction(data) => {
            store
                .add_transaction(parse_data(&data.data)?, data.writer_external_id.as_deref())
                .await?
        }
        Command::AddAccount(data) => {
            store
                .add_simple(
                    "account",
                    parse_data(&data.data)?,
                    data.writer_external_id.as_deref(),
                )
                .await?
        }
        Command::AddCommodity(data) => {
            store
                .add_simple(
                    "commodity",
                    parse_data(&data.data)?,
                    data.writer_external_id.as_deref(),
                )
                .await?
        }
        Command::AddPrice(data) => {
            store
                .add_simple(
                    "price",
                    parse_data(&data.data)?,
                    data.writer_external_id.as_deref(),
                )
                .await?
        }
        Command::AddInclude(data) => {
            store
                .add_simple(
                    "include",
                    parse_data(&data.data)?,
                    data.writer_external_id.as_deref(),
                )
                .await?
        }
        Command::Update(data) => {
            store
                .update_entity(parse_data(&data.data)?, data.writer_external_id.as_deref())
                .await?
        }
        Command::Delete(args) => {
            store
                .delete_entity(
                    args.eid,
                    &args.last_hash,
                    args.writer_external_id.as_deref(),
                )
                .await?
        }
        Command::ImportJournals(args) => {
            store
                .import_journals(
                    &args.source,
                    &args.last_hash,
                    args.writer_external_id.as_deref(),
                )
                .await?
        }
    };
    Ok(Some(value))
}

fn parse_data(raw: &str) -> Result<Value, Box<dyn std::error::Error>> {
    Ok(serde_json::from_str(raw)?)
}
