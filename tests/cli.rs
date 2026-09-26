use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::atomic::{AtomicU64, Ordering},
};

use serde_json::{Value, json};
use sqlx::{Connection, SqliteConnection, sqlite::SqliteConnectOptions};

static NEXT_WORKSPACE: AtomicU64 = AtomicU64::new(0);

struct Workspace(PathBuf);

impl Workspace {
    fn new() -> Self {
        let id = NEXT_WORKSPACE.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "hledger-immutable-test-{}-{id}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for Workspace {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn invoke(workspace: &Workspace, args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_hledger-immutable"))
        .arg("--workspace")
        .arg(workspace.path())
        .args(args)
        .output()
        .expect("start CLI")
}

fn json_output(output: Output) -> Value {
    let stdout = String::from_utf8(output.stdout).unwrap();
    serde_json::from_str(&stdout).unwrap_or_else(|error| panic!("not JSON ({error}): {stdout}"))
}

fn succeeds(workspace: &Workspace, args: &[&str]) -> Value {
    let output = invoke(workspace, args);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    json_output(output)
}

fn add_transaction(workspace: &Workspace, data: &Value) -> Value {
    let raw = serde_json::to_string(data).unwrap();
    succeeds(workspace, &["add-transaction", "--data", &raw])
}

fn snapshot_files(root: &Path) -> BTreeMap<String, Vec<u8>> {
    fn visit(root: &Path, directory: &Path, files: &mut BTreeMap<String, Vec<u8>>) {
        for entry in fs::read_dir(directory).unwrap() {
            let entry = entry.unwrap();
            let path = entry.path();
            if path.is_dir() {
                visit(root, &path, files);
            } else {
                let key = path
                    .strip_prefix(root)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned();
                files.insert(key, fs::read(path).unwrap());
            }
        }
    }
    let mut files = BTreeMap::new();
    visit(root, root, &mut files);
    files
}

#[test]
fn all_read_commands_and_makefile_report_options_emit_json() {
    let workspace = Workspace::new();
    succeeds(
        &workspace,
        &[
            "add-account",
            "--data",
            r#"{"file":"accounts.journal","name":"Assets:Bank"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-commodity",
            "--data",
            r#"{"file":"accounts.journal","name":"USD"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-price",
            "--data",
            r#"{"file":"prices.journal","date":"2025-01-01","commodity":"INR","value":"USD 0.01"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-price",
            "--data",
            r#"{"file":"prices.journal","date":"2025-01-01","commodity":"BTC","value":"USD 40000"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-include",
            "--data",
            r#"{"file":"main.journal","path":"accounts.journal"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-include",
            "--data",
            r#"{"file":"main.journal","path":"personal.journal"}"#,
        ],
    );
    succeeds(
        &workspace,
        &[
            "add-include",
            "--data",
            r#"{"file":"main.journal","path":"prices.journal"}"#,
        ],
    );

    let food = add_transaction(
        &workspace,
        &json!({
            "file":"personal.journal", "date":"2025-01-15", "description":"Food",
            "tags":[{"name":"project","value":"alpha"}],
            "postings":[
                {"account":"Expenses:Food", "amount":"INR 100"},
                {"account":"Assets:Cash", "amount":"INR -100"}
            ]
        }),
    );
    let salary = add_transaction(
        &workspace,
        &json!({
            "file":"personal.journal", "date":"2025-02-12", "description":"Salary",
            "postings":[
                {"account":"Income:Work", "amount":"USD -500"},
                {"account":"Assets:Bank", "amount":"USD 500"}
            ]
        }),
    );
    let crypto = add_transaction(
        &workspace,
        &json!({
            "file":"personal.journal", "date":"2025-03-02", "description":"Buy BTC",
            "postings":[
                {"account":"Assets:BTC", "amount":"BTC 0.5 @@ USD 20000"},
                {"account":"Assets:Cash", "amount":"USD -20000"}
            ]
        }),
    );

    let status = succeeds(&workspace, &["status"]);
    assert_eq!(status["source_of_truth"], "event_log");
    assert!(status["datom_count"].as_i64().unwrap() > 0);
    assert_eq!(
        status["latest_sequence"].as_i64().unwrap(),
        crypto["sequence"].as_i64().unwrap()
    );

    let accounts = succeeds(&workspace, &["accounts", "--file", "main.journal"]);
    assert!(
        accounts["accounts"]
            .as_array()
            .unwrap()
            .contains(&json!("Expenses:Food"))
    );
    assert!(
        accounts["accounts"]
            .as_array()
            .unwrap()
            .contains(&json!("Assets:Bank"))
    );
    let commodities = succeeds(&workspace, &["commodities", "--file", "main.journal"]);
    assert!(
        commodities["commodities"]
            .as_array()
            .unwrap()
            .contains(&json!("BTC"))
    );
    assert!(
        commodities["commodities"]
            .as_array()
            .unwrap()
            .contains(&json!("INR"))
    );

    let print = succeeds(
        &workspace,
        &["print", "--file", "main.journal", "--tag", "project=alpha"],
    );
    assert_eq!(print["count"], 1);
    assert_eq!(print["transactions"][0]["eid"], food["eid"]);
    let excluded = succeeds(
        &workspace,
        &["print", "--file", "main.journal", "not:Expenses:Food"],
    );
    assert_eq!(excluded["count"], 3);

    let balance = succeeds(
        &workspace,
        &[
            "balance",
            "--file",
            "main.journal",
            "--monthly",
            "-p",
            "2025",
            "-X",
            "USD",
            "--depth",
            "1",
            "-c",
            "USD 1",
        ],
    );
    assert_eq!(balance["command"], "balance");
    assert_eq!(balance["periods"].as_array().unwrap().len(), 3);
    assert_eq!(balance["commodity"], "USD");
    let food_month = balance["periods"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["period"] == "2025-01")
        .unwrap();
    assert!(
        food_month["rows"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["account"] == "Expenses" && row["amounts"][0]["quantity"] == "1")
    );

    let historical = succeeds(
        &workspace,
        &[
            "balance",
            "--file",
            "main.journal",
            "--monthly",
            "-p",
            "2025",
            "-X",
            "USD",
            "--historical",
            "--depth",
            "0",
            "^assets",
            "^liabilities",
            "--no-total",
            "--layout=bare",
            "--transpose",
        ],
    );
    assert_eq!(historical["orientation"], "accounts");
    assert!(
        historical["rows"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["account"] == "Assets")
    );

    let sheet = succeeds(
        &workspace,
        &[
            "bs",
            "--file",
            "main.journal",
            "-X",
            "USD",
            "--flat",
            "--layout=bare",
        ],
    );
    assert_eq!(sheet["command"], "balancesheet");
    assert!(
        sheet["periods"][0]["rows"]
            .as_array()
            .unwrap()
            .iter()
            .all(|row| ["Assets", "Liabilities", "Equity"]
                .iter()
                .any(|prefix| row["account"].as_str().unwrap().starts_with(prefix)))
    );
    let income = succeeds(
        &workspace,
        &[
            "is",
            "--file",
            "main.journal",
            "-X",
            "USD",
            "--yearly",
            "-p",
            "2025",
            "not:Income:Hidden",
        ],
    );
    assert_eq!(income["command"], "incomestatement");
    assert!(
        income["periods"][0]["rows"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["account"] == "Income:Work")
    );

    let register = succeeds(
        &workspace,
        &[
            "reg",
            "--file",
            "main.journal",
            "-b",
            "2025-02-01",
            "-e",
            "2025-03-01",
            "Income",
        ],
    );
    assert_eq!(register["command"], "register");
    assert_eq!(register["count"], 1);
    assert_eq!(register["entries"][0]["transaction_eid"], salary["eid"]);
}

#[test]
fn cost_market_and_native_valuations_are_distinct() {
    let workspace = Workspace::new();
    succeeds(
        &workspace,
        &[
            "add-price",
            "--data",
            r#"{"file":"prices.journal","date":"2025-01-01","commodity":"BTC","value":"USD 40000"}"#,
        ],
    );
    add_transaction(
        &workspace,
        &json!({
            "file":"main.journal", "date":"2025-01-02", "description":"BTC lot",
            "postings":[
                {"account":"Assets:BTC", "amount":"BTC 0.5 @@ USD 20000"},
                {"account":"Assets:Cash", "amount":"USD -20000"}
            ]
        }),
    );
    let native = succeeds(&workspace, &["balance"]);
    assert!(
        native["periods"][0]["rows"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["amounts"]
                .as_array()
                .unwrap()
                .iter()
                .any(|amount| amount["commodity"] == "BTC"))
    );
    let cost = succeeds(&workspace, &["balance", "-B"]);
    assert_eq!(cost["valuation"], "cost");
    assert_eq!(
        cost["periods"][0]["rows"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["account"] == "Assets:BTC")
            .unwrap()["amounts"][0]["quantity"],
        "20000"
    );
    let market = succeeds(&workspace, &["balance", "-V", "--today", "2025-01-02"]);
    assert_eq!(market["commodity"], "USD");
    assert_eq!(
        market["periods"][0]["rows"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["account"] == "Assets:BTC")
            .unwrap()["amounts"][0]["quantity"],
        "20000"
    );
}

#[test]
fn legacy_database_is_adopted_without_losing_event_log_history() {
    let workspace = Workspace::new();
    let database = workspace.path().join("immutable.sqlite");
    let options = SqliteConnectOptions::new()
        .filename(&database)
        .create_if_missing(true);
    let mut connection = futuresless_connect(options);
    futuresless_block_on(async {
        sqlx::query("CREATE TABLE entity_ids (eid INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER)))").execute(&mut connection).await.unwrap();
        sqlx::query("CREATE TABLE event_log (sequence INTEGER PRIMARY KEY AUTOINCREMENT, eid INTEGER NOT NULL, attr TEXT NOT NULL, value_json TEXT NOT NULL, retract INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s','now') AS INTEGER)), FOREIGN KEY (eid) REFERENCES entity_ids(eid))").execute(&mut connection).await.unwrap();
        sqlx::query("INSERT INTO entity_ids(eid) VALUES (1),(2),(3)")
            .execute(&mut connection)
            .await
            .unwrap();
        let rows = [
            (1_i64, 1_i64, "entity/type", "\"transaction\"", 0_i64),
            (2, 1, "entity/file", "\"main.journal\"", 0),
            (3, 1, "transaction/date", "\"2024-12-31\"", 0),
            (4, 1, "transaction/description", "\"legacy\"", 0),
            (5, 2, "entity/type", "\"posting\"", 0),
            (6, 2, "posting/parent-eid", "1", 0),
            (7, 2, "posting/account", "\"Assets:Cash\"", 0),
            (8, 2, "posting/amount", "\"USD 10\"", 0),
            (9, 3, "entity/type", "\"posting\"", 0),
            (10, 3, "posting/parent-eid", "1", 0),
            (11, 3, "posting/account", "\"Equity:Opening\"", 0),
            (12, 3, "posting/amount", "\"USD -10\"", 0),
        ];
        for (sequence, eid, attr, value_json, retract) in rows {
            sqlx::query("INSERT INTO event_log(sequence,eid,attr,value_json,retract) VALUES (?1,?2,?3,?4,?5)")
                .bind(sequence).bind(eid).bind(attr).bind(value_json).bind(retract).execute(&mut connection).await.unwrap();
        }
        connection.close().await.unwrap();
    });

    let printed = succeeds(&workspace, &["print"]);
    assert_eq!(printed["transactions"][0]["eid"], 1);
    assert_eq!(printed["transactions"][0]["description"], "legacy");
    let status = succeeds(&workspace, &["status"]);
    assert_eq!(status["latest_sequence"], 12);
    assert_eq!(status["datom_count"], 12);
}

#[test]
fn invalid_json_and_failed_batch_writes_return_json_without_partial_datoms() {
    let workspace = Workspace::new();
    let malformed = invoke(&workspace, &["add-transaction", "--data", "{"]);
    assert!(!malformed.status.success());
    assert!(
        json_output(malformed)["error"]["message"]
            .as_str()
            .is_some()
    );

    let before = succeeds(&workspace, &["status"])["latest_sequence"].clone();
    let invalid_transaction =
        r#"{"file":"main.journal","date":"2025-01-01","description":"missing postings"}"#;
    let failed = invoke(
        &workspace,
        &["add-transaction", "--data", invalid_transaction],
    );
    assert!(!failed.status.success());
    assert!(
        json_output(failed)["error"]["message"]
            .as_str()
            .unwrap()
            .contains("postings")
    );
    let after = succeeds(&workspace, &["status"])["latest_sequence"].clone();
    assert_eq!(before, after);
}

#[test]
fn bare_invocation_and_help_options_print_clap_tables() {
    let workspace = Workspace::new();
    let bare = invoke(&workspace, &[]);
    assert!(bare.status.success());
    let text = String::from_utf8(bare.stdout).unwrap();
    assert!(text.contains("accounts         List declared and used account names"));
    assert!(text.contains("add-transaction  Append a JSON transaction"));
    assert!(!workspace.path().join("immutable.sqlite").exists());

    let help = invoke(&workspace, &["balance", "--help"]);
    assert!(help.status.success());
    let command_help = String::from_utf8(help.stdout).unwrap();
    assert!(command_help.contains("Report account balance changes"));
    assert!(command_help.contains("--monthly"));

    let conflict = invoke(&workspace, &["balance", "--monthly", "--yearly"]);
    assert!(!conflict.status.success());
    assert!(json_output(conflict)["error"]["message"].as_str().is_some());
}

#[test]
fn every_command_has_individual_documentation_and_add_commands_show_json_examples() {
    let workspace = Workspace::new();
    for command in [
        "accounts",
        "commodities",
        "print",
        "balance",
        "bs",
        "is",
        "reg",
        "status",
        "add-transaction",
        "add-account",
        "add-commodity",
        "add-price",
        "add-include",
    ] {
        let output = invoke(&workspace, &[command, "--help"]);
        assert!(output.status.success(), "{command} --help failed");
        let help = String::from_utf8(output.stdout).unwrap();
        assert!(
            help.contains("Example:") || help.contains("Examples:"),
            "{command} lacks an example: {help}"
        );
    }

    for (command, json_field) in [
        ("add-transaction", "postings"),
        ("add-account", "name"),
        ("add-commodity", "name"),
        ("add-price", "commodity"),
        ("add-include", "path"),
    ] {
        let output = invoke(&workspace, &[command, "--help"]);
        let help = String::from_utf8(output.stdout).unwrap();
        assert!(
            help.contains("--data"),
            "{command} does not document JSON input"
        );
        assert!(
            help.contains(json_field),
            "{command} example lacks {json_field}"
        );
        assert!(help.contains("personal.journal") || command != "add-transaction");
    }
}

#[test]
fn interval_switches_and_balance_alias_return_the_expected_json_shape() {
    let workspace = Workspace::new();
    add_transaction(
        &workspace,
        &json!({
            "file":"main", "date":"2025-01-01", "description":"Opening",
            "postings":[
                {"account":"Assets:Cash", "amount":"USD 2"},
                {"account":"Equity:Opening", "amount":"USD -2"}
            ]
        }),
    );
    let alias = succeeds(&workspace, &["bal"]);
    assert_eq!(alias["command"], "balance");
    for (flag, expected_key) in [
        ("--daily", "2025-01-01"),
        ("--weekly", "2025-W01"),
        ("--monthly", "2025-01"),
        ("--quarterly", "2025-Q1"),
        ("--yearly", "2025"),
    ] {
        let report = succeeds(&workspace, &["balance", flag]);
        assert_eq!(report["periods"][0]["period"], expected_key, "{flag}");
    }
}

#[test]
fn unbalanced_historical_transaction_remains_appendable_and_reportable() {
    let workspace = Workspace::new();
    let appended = add_transaction(
        &workspace,
        &json!({
            "file":"main", "date":"2025-01-01", "description":"Incomplete source data",
            "postings":[{"account":"Assets:Cash", "amount":"USD 9"}]
        }),
    );
    assert!(appended["eid"].as_i64().is_some());
    let printed = succeeds(&workspace, &["print"]);
    assert_eq!(printed["count"], 1);
}

#[test]
fn imports_multiple_journals_recursively_without_modifying_source_files() {
    let workspace = Workspace::new();
    let source = workspace.path().join("source-journals");
    fs::create_dir_all(source.join("nested")).unwrap();
    fs::write(
        source.join("main.journal"),
        "include nested/transactions.journal\ncommodity USD\naccount Assets:Cash\nP 2025-01-01 BTC USD 50000\n",
    )
    .unwrap();
    fs::write(
        source.join("nested/transactions.journal"),
        "2025-01-02 * (PAY) Coffee ; project: home\n  Expenses:Coffee  USD 5 ; method: cash\n  Assets:Cash      USD -5\n",
    )
    .unwrap();
    fs::write(
        source.join("empty.journal"),
        "; intentionally empty source file\n",
    )
    .unwrap();
    let before = snapshot_files(&source);
    let source_arg = source.to_string_lossy().into_owned();
    let imported = succeeds(&workspace, &["import-journals", "--source", &source_arg]);
    assert_eq!(imported["imported"], true);
    assert_eq!(imported["source_files"], 3);
    assert_eq!(imported["transactions"], 1);
    assert_eq!(snapshot_files(&source), before);

    let print = succeeds(
        &workspace,
        &["print", "--file", "main.journal", "--tag", "project=home"],
    );
    assert_eq!(print["count"], 1);
    let txn = &print["transactions"][0];
    assert_eq!(txn["description"], "Coffee");
    assert_eq!(txn["code"], "PAY");
    assert_eq!(txn["postings"][0]["tags"][0]["name"], "method");

    let status_before = succeeds(&workspace, &["status"])["latest_sequence"].clone();
    let second_import = invoke(&workspace, &["import-journals", "--source", &source_arg]);
    assert!(!second_import.status.success());
    assert!(
        json_output(second_import)["error"]["message"]
            .as_str()
            .unwrap()
            .contains("empty event log")
    );
    assert_eq!(
        succeeds(&workspace, &["status"])["latest_sequence"],
        status_before
    );
    assert_eq!(snapshot_files(&source), before);
}

#[test]
fn importer_rejects_bad_sources_before_appending_datoms() {
    let workspace = Workspace::new();
    let source = workspace.path().join("bad-journals");
    fs::create_dir_all(&source).unwrap();
    fs::write(source.join("bad.journal"), "unsupported-directive value\n").unwrap();
    let source_arg = source.to_string_lossy().into_owned();
    let output = invoke(&workspace, &["import-journals", "--source", &source_arg]);
    assert!(!output.status.success());
    assert!(
        json_output(output)["error"]["message"]
            .as_str()
            .unwrap()
            .contains("Unsupported directive")
    );
    assert_eq!(
        succeeds(&workspace, &["status"])["latest_sequence"],
        Value::Null
    );
}

// These adapters let this integration suite use SQLx's async SQLite setup
// while keeping the individual tests synchronous like the CLI they exercise.
fn futuresless_connect(options: SqliteConnectOptions) -> SqliteConnection {
    futuresless_block_on(SqliteConnection::connect_with(&options)).unwrap()
}

fn futuresless_block_on<F: std::future::Future>(future: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(future)
}
