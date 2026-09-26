use std::{collections::BTreeMap, path::Path, time::Duration};

use serde_json::{Value, json};
use sqlx::{
    Row, SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
};

use crate::{
    importer::{self, ImportedEntity, ImportedTag},
    ledger::{Entity, fold},
};

pub struct Store {
    pool: SqlitePool,
    workspace: String,
}

impl Store {
    pub async fn open(workspace: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        std::fs::create_dir_all(workspace)?;
        let db_path = workspace.join("immutable.sqlite");
        let options = SqliteConnectOptions::new()
            .filename(&db_path)
            .create_if_missing(true)
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(5))
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal);
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(options)
            .await?;
        sqlx::migrate!("./migrations").run(&pool).await?;
        Ok(Self {
            pool,
            workspace: workspace.display().to_string(),
        })
    }

    pub async fn status(&self) -> Result<Value, sqlx::Error> {
        let row =
            sqlx::query("SELECT max(sequence) AS sequence, count(*) AS datom_count FROM event_log")
                .fetch_one(&self.pool)
                .await?;
        Ok(json!({
            "workspace": &self.workspace,
            "latest_sequence": row.try_get::<Option<i64>, _>("sequence")?,
            "datom_count": row.try_get::<i64, _>("datom_count")?,
            "source_of_truth": "event_log"
        }))
    }

    pub async fn entities(&self) -> Result<BTreeMap<i64, Entity>, Box<dyn std::error::Error>> {
        let rows = sqlx::query(
            "SELECT sequence, eid, attr, value_json, retract FROM event_log ORDER BY sequence",
        )
        .fetch_all(&self.pool)
        .await?;
        let mut datoms = Vec::with_capacity(rows.len());
        for row in rows {
            let raw: String = row.try_get("value_json")?;
            datoms.push((
                row.try_get::<i64, _>("sequence")?,
                row.try_get::<i64, _>("eid")?,
                row.try_get::<String, _>("attr")?,
                serde_json::from_str::<Value>(&raw)?,
                row.try_get::<i64, _>("retract")? != 0,
            ));
        }
        Ok(fold(datoms))
    }

    pub async fn add_simple(
        &mut self,
        entity_type: &str,
        data: Value,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let eid = allocate_eid(&mut tx).await?;
        let group = required_string(&data, "file")?;
        let position = eid * 1000;
        let mut datoms = vec![
            (eid, "entity/type", json!(entity_type)),
            (eid, "entity/file", json!(group)),
            (eid, "entity/position", json!(position)),
        ];
        let attrs: &[(&str, &str)] = match entity_type {
            "account" => &[("name", "account/name")],
            "commodity" => &[("name", "commodity/name")],
            "price" => &[
                ("date", "price/date"),
                ("commodity", "price/commodity"),
                ("value", "price/value"),
            ],
            "include" => &[("path", "include/file")],
            _ => return Err(format!("Unsupported simple entity type: {entity_type}").into()),
        };
        for (input_key, attr) in attrs {
            let value = data
                .get(*input_key)
                .ok_or_else(|| format!("Missing required field: {input_key}"))?;
            datoms.push((eid, attr, value.clone()));
        }
        append_datoms(&mut tx, &datoms).await?;
        tx.commit().await?;
        Ok(json!({"eid": eid, "type": entity_type, "sequence": latest_sequence(&self.pool).await?}))
    }

    pub async fn add_transaction(
        &mut self,
        data: Value,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let root = allocate_eid(&mut tx).await?;
        let group = required_string(&data, "file")?;
        let date = required_value(&data, "date")?;
        let description = required_value(&data, "description")?;
        let mut datoms = vec![
            (root, "entity/type", json!("transaction")),
            (root, "entity/file", json!(group)),
            (root, "entity/position", json!(root * 1000)),
            (root, "transaction/date", date),
            (root, "transaction/description", description),
        ];
        for (key, attr) in [
            ("status", "transaction/status"),
            ("code", "transaction/code"),
        ] {
            if let Some(value) = data.get(key) {
                datoms.push((root, attr, value.clone()));
            }
        }
        add_tags(&mut tx, &mut datoms, root, data.get("tags")).await?;
        let postings = data
            .get("postings")
            .and_then(Value::as_array)
            .ok_or("Transaction requires a postings array")?;
        for posting in postings {
            let eid = allocate_eid(&mut tx).await?;
            datoms.extend([
                (eid, "entity/type", json!("posting")),
                (eid, "posting/parent-eid", json!(root)),
                (eid, "posting/position", json!(eid * 1000)),
                (eid, "posting/account", required_value(posting, "account")?),
            ]);
            for (key, attr) in [("amount", "posting/amount"), ("status", "posting/status")] {
                if let Some(value) = posting.get(key) {
                    datoms.push((eid, attr, value.clone()));
                }
            }
            add_tags(&mut tx, &mut datoms, eid, posting.get("tags")).await?;
        }
        append_datoms(&mut tx, &datoms).await?;
        tx.commit().await?;
        Ok(
            json!({"eid": root, "type": "transaction", "sequence": latest_sequence(&self.pool).await?}),
        )
    }

    pub async fn import_journals(
        &mut self,
        source: &Path,
    ) -> Result<Value, Box<dyn std::error::Error>> {
        let journal_import = importer::read_folder(source)?;
        let source_files = journal_import.source_files;
        let imported = journal_import.entities;
        let transaction_count = imported
            .iter()
            .filter(|entity| matches!(entity, ImportedEntity::Transaction(_)))
            .count();

        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let existing_datoms: i64 = sqlx::query_scalar("SELECT count(*) FROM event_log")
            .fetch_one(&mut *tx)
            .await?;
        if existing_datoms != 0 {
            return Err("Journal import requires an empty event log; refusing to append duplicate source history".into());
        }

        let mut datoms = Vec::new();
        for entity in &imported {
            match entity {
                ImportedEntity::Account { file, name } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "account", file);
                    datoms.push((eid, "account/name", json!(name)));
                }
                ImportedEntity::Commodity { file, name } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "commodity", file);
                    datoms.push((eid, "commodity/name", json!(name)));
                }
                ImportedEntity::Price {
                    file,
                    date,
                    commodity,
                    value,
                } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "price", file);
                    datoms.extend([
                        (eid, "price/date", json!(date)),
                        (eid, "price/commodity", json!(commodity)),
                        (eid, "price/value", json!(value)),
                    ]);
                }
                ImportedEntity::Include { file, path } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "include", file);
                    datoms.push((eid, "include/file", json!(path)));
                }
                ImportedEntity::Alias { file, old, new } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "alias", file);
                    datoms.extend([
                        (eid, "alias/old", json!(old)),
                        (eid, "alias/new", json!(new)),
                    ]);
                }
                ImportedEntity::DecimalMark { file, value } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "decimal-mark", file);
                    datoms.push((eid, "decimal-mark/value", json!(value)));
                }
                ImportedEntity::DefaultCommodity { file, value } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "default-commodity", file);
                    datoms.push((eid, "default-commodity/value", json!(value)));
                }
                ImportedEntity::Payee { file, name } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "payee", file);
                    datoms.push((eid, "payee/name", json!(name)));
                }
                ImportedEntity::TagDeclaration { file, name } => {
                    let eid = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, eid, "tag-declaration", file);
                    datoms.push((eid, "tag-declaration/name", json!(name)));
                }
                ImportedEntity::Transaction(transaction) => {
                    let root = allocate_eid(&mut tx).await?;
                    push_import_root(&mut datoms, root, "transaction", &transaction.file);
                    datoms.extend([
                        (root, "transaction/date", json!(transaction.date)),
                        (
                            root,
                            "transaction/description",
                            json!(transaction.description),
                        ),
                    ]);
                    if let Some(status) = &transaction.status {
                        datoms.push((root, "transaction/status", json!(status)));
                    }
                    if let Some(code) = &transaction.code {
                        datoms.push((root, "transaction/code", json!(code)));
                    }
                    append_import_tags(&mut tx, &mut datoms, root, &transaction.tags).await?;
                    for posting in &transaction.postings {
                        let eid = allocate_eid(&mut tx).await?;
                        datoms.extend([
                            (eid, "entity/type", json!("posting")),
                            (eid, "posting/parent-eid", json!(root)),
                            (eid, "posting/position", json!(eid * 1000)),
                            (eid, "posting/account", json!(posting.account)),
                        ]);
                        if let Some(amount) = &posting.amount {
                            datoms.push((eid, "posting/amount", json!(amount)));
                        }
                        if let Some(status) = &posting.status {
                            datoms.push((eid, "posting/status", json!(status)));
                        }
                        append_import_tags(&mut tx, &mut datoms, eid, &posting.tags).await?;
                    }
                }
            }
        }

        let datom_count = datoms.len();
        append_datoms(&mut tx, &datoms).await?;
        tx.commit().await?;
        Ok(json!({
            "imported": true,
            "source_files": source_files,
            "entities": imported.len(),
            "transactions": transaction_count,
            "datoms": datom_count,
            "latest_sequence": latest_sequence(&self.pool).await?
        }))
    }
}

fn push_import_root(datoms: &mut Vec<(i64, &str, Value)>, eid: i64, entity_type: &str, file: &str) {
    datoms.extend([
        (eid, "entity/type", json!(entity_type)),
        (eid, "entity/file", json!(file)),
        (eid, "entity/position", json!(eid * 1000)),
    ]);
}

async fn append_import_tags(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    datoms: &mut Vec<(i64, &str, Value)>,
    parent: i64,
    tags: &[ImportedTag],
) -> Result<(), sqlx::Error> {
    for tag in tags {
        let eid = allocate_eid(tx).await?;
        datoms.extend([
            (eid, "entity/type", json!("tag")),
            (eid, "tag/parent-eid", json!(parent)),
            (eid, "tag/position", json!(eid * 1000)),
            (eid, "tag/name", json!(tag.name)),
        ]);
        if let Some(value) = &tag.value {
            datoms.push((eid, "tag/value", json!(value)));
        }
    }
    Ok(())
}

async fn allocate_eid(tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>) -> Result<i64, sqlx::Error> {
    sqlx::query("INSERT INTO entity_ids DEFAULT VALUES RETURNING eid")
        .fetch_one(&mut **tx)
        .await?
        .try_get("eid")
}

async fn append_datoms(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    datoms: &[(i64, &str, Value)],
) -> Result<(), sqlx::Error> {
    for (eid, attr, value) in datoms {
        let value_json = serde_json::to_string(value).expect("JSON values serialize");
        sqlx::query(
            "INSERT INTO event_log (eid, attr, value_json, retract) VALUES (?1, ?2, ?3, 0)",
        )
        .bind(eid)
        .bind(attr)
        .bind(value_json)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

async fn add_tags(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    datoms: &mut Vec<(i64, &'static str, Value)>,
    parent: i64,
    tags: Option<&Value>,
) -> Result<(), Box<dyn std::error::Error>> {
    let Some(tags) = tags.and_then(Value::as_array) else {
        return Ok(());
    };
    for tag in tags {
        let eid = allocate_eid(tx).await?;
        datoms.extend([
            (eid, "entity/type", json!("tag")),
            (eid, "tag/parent-eid", json!(parent)),
            (eid, "tag/position", json!(eid * 1000)),
            (eid, "tag/name", required_value(tag, "name")?),
        ]);
        if let Some(value) = tag.get("value") {
            datoms.push((eid, "tag/value", value.clone()));
        }
    }
    Ok(())
}

fn required_string(data: &Value, key: &str) -> Result<String, Box<dyn std::error::Error>> {
    Ok(required_value(data, key)?
        .as_str()
        .ok_or_else(|| format!("{key} must be a string"))?
        .to_owned())
}

fn required_value(data: &Value, key: &str) -> Result<Value, Box<dyn std::error::Error>> {
    data.get(key)
        .cloned()
        .ok_or_else(|| format!("Missing required field: {key}").into())
}

async fn latest_sequence(pool: &SqlitePool) -> Result<Option<i64>, sqlx::Error> {
    sqlx::query("SELECT max(sequence) AS sequence FROM event_log")
        .fetch_one(pool)
        .await?
        .try_get("sequence")
}
