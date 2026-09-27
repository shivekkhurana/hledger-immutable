use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
    path::Path,
    time::Duration,
};

use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use sqlx::{
    QueryBuilder, Row, Sqlite, SqlitePool,
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

#[derive(Debug)]
pub struct HashConflictError {
    pub expected: String,
    pub current: String,
}

impl fmt::Display for HashConflictError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "workspace changed since lastHash was read")
    }
}

impl std::error::Error for HashConflictError {}

impl Store {
    pub async fn open(workspace: &Path) -> Result<Self, crate::Error> {
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
        let store = Self {
            pool,
            workspace: workspace.display().to_string(),
        };
        store.ensure_datom_hashes().await?;
        store.ensure_current_projection().await?;
        Ok(store)
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
            "lastHash": current_state_hash(&self.pool).await?,
            "source_of_truth": "event_log"
        }))
    }

    async fn ensure_datom_hashes(&self) -> Result<(), crate::Error> {
        let unhashed: i64 =
            sqlx::query_scalar("SELECT count(*) FROM event_log WHERE datom_hash IS NULL")
                .fetch_one(&self.pool)
                .await?;
        if unhashed == 0 {
            return Ok(());
        }
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let rows = sqlx::query(
            "SELECT sequence, eid, attr, value_json, retract, writer_external_id \
             FROM event_log ORDER BY sequence",
        )
        .fetch_all(&mut *tx)
        .await?;
        let mut previous_hash = "0".to_owned();
        for row in rows {
            let sequence: i64 = row.try_get("sequence")?;
            previous_hash = next_datom_hash(
                &previous_hash,
                sequence,
                row.try_get("eid")?,
                &row.try_get::<String, _>("attr")?,
                &row.try_get::<String, _>("value_json")?,
                row.try_get("retract")?,
                row.try_get::<Option<String>, _>("writer_external_id")?
                    .as_deref(),
            );
            sqlx::query("UPDATE event_log SET datom_hash = ?1 WHERE sequence = ?2")
                .bind(&previous_hash)
                .bind(sequence)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok(())
    }

    pub async fn entities(&self) -> Result<BTreeMap<i64, Entity>, crate::Error> {
        let rows = sqlx::query(
            "SELECT eid, first_sequence, attributes_json \
             FROM current_entities ORDER BY eid",
        )
        .fetch_all(&self.pool)
        .await?;
        let mut entities = BTreeMap::<i64, Entity>::new();
        for row in rows {
            let eid: i64 = row.try_get("eid")?;
            entities.insert(
                eid,
                Entity {
                    eid,
                    first_sequence: row.try_get("first_sequence")?,
                    attributes: serde_json::from_str::<BTreeMap<String, Value>>(
                        &row.try_get::<String, _>("attributes_json")?,
                    )?,
                },
            );
        }
        Ok(entities)
    }

    async fn ensure_current_projection(&self) -> Result<(), crate::Error> {
        let latest_sequence: i64 =
            sqlx::query_scalar("SELECT coalesce(max(sequence), 0) FROM event_log")
                .fetch_one(&self.pool)
                .await?;
        let projected_sequence: Option<i64> =
            sqlx::query_scalar("SELECT last_sequence FROM current_projection_state WHERE id = 1")
                .fetch_optional(&self.pool)
                .await?;

        if projected_sequence == Some(latest_sequence) {
            return Ok(());
        }

        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let latest_sequence: i64 =
            sqlx::query_scalar("SELECT coalesce(max(sequence), 0) FROM event_log")
                .fetch_one(&mut *tx)
                .await?;
        let projected_sequence: Option<i64> =
            sqlx::query_scalar("SELECT last_sequence FROM current_projection_state WHERE id = 1")
                .fetch_optional(&mut *tx)
                .await?;
        if projected_sequence == Some(latest_sequence) {
            tx.commit().await?;
            return Ok(());
        }

        let rows = sqlx::query(
            "SELECT sequence, eid, attr, value_json, retract FROM event_log ORDER BY sequence",
        )
        .fetch_all(&mut *tx)
        .await?;
        let mut datoms = Vec::with_capacity(rows.len());
        for row in rows {
            datoms.push((
                row.try_get::<i64, _>("sequence")?,
                row.try_get::<i64, _>("eid")?,
                row.try_get::<String, _>("attr")?,
                serde_json::from_str::<Value>(&row.try_get::<String, _>("value_json")?)?,
                row.try_get::<i64, _>("retract")? != 0,
            ));
        }
        let entities = fold(datoms);

        sqlx::query("DELETE FROM current_entities")
            .execute(&mut *tx)
            .await?;
        for chunk in entities.values().collect::<Vec<_>>().chunks(5_000) {
            let serialized = chunk
                .iter()
                .map(|entity| {
                    Ok((
                        entity.eid,
                        entity.first_sequence,
                        serde_json::to_string(&entity.attributes)?,
                    ))
                })
                .collect::<Result<Vec<_>, serde_json::Error>>()?;
            let mut query = QueryBuilder::<Sqlite>::new(
                "INSERT INTO current_entities (eid, first_sequence, attributes_json) ",
            );
            query.push_values(&serialized, |mut row, (eid, sequence, attributes)| {
                row.push_bind(*eid)
                    .push_bind(*sequence)
                    .push_bind(attributes);
            });
            query.build().execute(&mut *tx).await?;
        }
        sqlx::query(
            "INSERT INTO current_projection_state (id, last_sequence) VALUES (1, ?1) \
             ON CONFLICT(id) DO UPDATE SET last_sequence = excluded.last_sequence",
        )
        .bind(latest_sequence)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn add_simple(
        &self,
        entity_type: &str,
        data: Value,
        writer_external_id: Option<&str>,
    ) -> Result<Value, crate::Error> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let expected_hash = required_string(&data, "lastHash")?.to_owned();
        check_state_hash(&mut tx, &expected_hash).await?;
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
        append_datoms(&mut tx, &datoms, writer_external_id).await?;
        let last_hash = current_state_hash_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(
            json!({"eid": eid, "type": entity_type, "sequence": latest_sequence(&self.pool).await?, "lastHash": last_hash}),
        )
    }

    pub async fn add_transaction(
        &self,
        data: Value,
        writer_external_id: Option<&str>,
    ) -> Result<Value, crate::Error> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let expected_hash = required_string(&data, "lastHash")?.to_owned();
        check_state_hash(&mut tx, &expected_hash).await?;
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
        append_datoms(&mut tx, &datoms, writer_external_id).await?;
        let last_hash = current_state_hash_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(
            json!({"eid": root, "type": "transaction", "sequence": latest_sequence(&self.pool).await?, "lastHash": last_hash}),
        )
    }

    pub async fn update_entity(
        &self,
        data: Value,
        writer_external_id: Option<&str>,
    ) -> Result<Value, crate::Error> {
        let eid = data
            .get("eid")
            .and_then(Value::as_i64)
            .ok_or("eid must be an integer")?;
        let attr = required_string(&data, "attr")?;
        let expected_hash = required_string(&data, "lastHash")?.to_owned();
        let value = required_value(&data, "value")?;
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        check_state_hash(&mut tx, &expected_hash).await?;
        let entities = load_current_entities(&mut tx, &BTreeSet::from([eid])).await?;
        let entity = entities
            .get(&eid)
            .ok_or_else(|| format!("Entity {eid} does not exist"))?;
        let entity_type = entity
            .attributes
            .get("entity/type")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("Entity {eid} has no valid entity/type"))?;
        if !mutable_attribute(entity_type, &attr) {
            return Err(format!(
                "Attribute {attr:?} cannot be updated on entity type {entity_type:?}"
            )
            .into());
        }
        if !value.is_string() {
            return Err(format!("Value for attribute {attr:?} must be a string").into());
        }
        let current = entity
            .attributes
            .get(&attr)
            .ok_or_else(|| format!("Attribute {attr:?} is not set on entity {eid}"))?;
        if current == &value {
            let sequence: Option<i64> = sqlx::query_scalar("SELECT max(sequence) FROM event_log")
                .fetch_one(&mut *tx)
                .await?;
            return Ok(
                json!({"eid": eid, "attr": attr, "value": value, "sequence": sequence, "unchanged": true, "lastHash": expected_hash}),
            );
        }
        let datoms = [
            (eid, attr.as_str(), current.clone(), true),
            (eid, attr.as_str(), value.clone(), false),
        ];
        append_datom_events(&mut tx, &datoms, writer_external_id).await?;
        let sequence: Option<i64> = sqlx::query_scalar("SELECT max(sequence) FROM event_log")
            .fetch_one(&mut *tx)
            .await?;
        let last_hash = current_state_hash_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(
            json!({"eid": eid, "attr": attr, "value": value, "sequence": sequence, "lastHash": last_hash}),
        )
    }

    pub async fn delete_entity(
        &self,
        eid: i64,
        expected_hash: &str,
        writer_external_id: Option<&str>,
    ) -> Result<Value, crate::Error> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        check_state_hash(&mut tx, expected_hash).await?;
        let all_entities = load_all_current_entities(&mut tx).await?;
        if !all_entities.contains_key(&eid) {
            return Err(format!("Entity {eid} does not exist").into());
        }

        let mut deleted_eids = BTreeSet::from([eid]);
        loop {
            let current_len = deleted_eids.len();
            for entity in all_entities.values() {
                let parent = entity
                    .attributes
                    .get("posting/parent-eid")
                    .or_else(|| entity.attributes.get("tag/parent-eid"))
                    .and_then(Value::as_i64);
                if parent.is_some_and(|parent| deleted_eids.contains(&parent)) {
                    deleted_eids.insert(entity.eid);
                }
            }
            if deleted_eids.len() == current_len {
                break;
            }
        }

        let datoms = deleted_eids
            .iter()
            .flat_map(|deleted_eid| {
                all_entities[deleted_eid]
                    .attributes
                    .iter()
                    .map(move |(attr, value)| (*deleted_eid, attr.as_str(), value.clone(), true))
            })
            .collect::<Vec<_>>();
        append_datom_events(&mut tx, &datoms, writer_external_id).await?;
        let sequence: Option<i64> = sqlx::query_scalar("SELECT max(sequence) FROM event_log")
            .fetch_one(&mut *tx)
            .await?;
        let last_hash = current_state_hash_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(
            json!({"eid": eid, "deleted_eids": deleted_eids, "sequence": sequence, "lastHash": last_hash}),
        )
    }

    pub async fn import_journals(
        &self,
        source: &Path,
        expected_hash: &str,
        writer_external_id: Option<&str>,
    ) -> Result<Value, crate::Error> {
        let journal_import = importer::read_folder(source)?;
        let source_files = journal_import.source_files;
        let imported = journal_import.entities;
        let transaction_count = imported
            .iter()
            .filter(|entity| matches!(entity, ImportedEntity::Transaction(_)))
            .count();

        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        check_state_hash(&mut tx, expected_hash).await?;
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
        append_datoms(&mut tx, &datoms, writer_external_id).await?;
        let last_hash = current_state_hash_tx(&mut tx).await?;
        tx.commit().await?;
        Ok(json!({
            "imported": true,
            "source_files": source_files,
            "entities": imported.len(),
            "transactions": transaction_count,
            "datoms": datom_count,
            "latest_sequence": latest_sequence(&self.pool).await?,
            "lastHash": last_hash
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
    writer_external_id: Option<&str>,
) -> Result<(), sqlx::Error> {
    let events = datoms
        .iter()
        .map(|(eid, attr, value)| (*eid, *attr, value.clone(), false))
        .collect::<Vec<_>>();
    append_datom_events(tx, &events, writer_external_id).await
}

async fn append_datom_events(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    datoms: &[(i64, &str, Value, bool)],
    writer_external_id: Option<&str>,
) -> Result<(), sqlx::Error> {
    let mut previous_hash = current_state_hash_tx(tx).await?;
    let mut sequence: i64 = sqlx::query_scalar(
        "SELECT max(\
             coalesce((SELECT seq FROM sqlite_sequence WHERE name = 'event_log'), 0), \
             coalesce((SELECT max(sequence) FROM event_log), 0)\
         ) + 1",
    )
    .fetch_one(&mut **tx)
    .await?;
    let touched_eids = datoms
        .iter()
        .map(|(eid, _, _, _)| *eid)
        .collect::<BTreeSet<_>>();
    let mut current_entities = load_current_entities(tx, &touched_eids).await?;
    let mut latest_sequence = None;
    for (eid, attr, value, retract) in datoms {
        let value_json = serde_json::to_string(value).expect("JSON values serialize");
        let datom_hash = next_datom_hash(
            &previous_hash,
            sequence,
            *eid,
            attr,
            &value_json,
            i64::from(*retract),
            writer_external_id,
        );
        sqlx::query(
            "INSERT INTO event_log (sequence, eid, attr, value_json, retract, datom_hash, writer_external_id) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        )
        .bind(sequence)
        .bind(eid)
        .bind(attr)
        .bind(&value_json)
        .bind(i64::from(*retract))
        .bind(&datom_hash)
        .bind(writer_external_id)
        .execute(&mut **tx)
        .await?;
        previous_hash = datom_hash;
        apply_current_datom(&mut current_entities, *eid, attr, value, *retract, sequence);
        latest_sequence = Some(sequence);
        sequence += 1;
    }
    replace_current_entities(tx, &touched_eids, &current_entities).await?;
    if let Some(latest_sequence) = latest_sequence {
        sqlx::query(
            "INSERT INTO current_projection_state (id, last_sequence) VALUES (1, ?1) \
             ON CONFLICT(id) DO UPDATE SET last_sequence = excluded.last_sequence",
        )
        .bind(latest_sequence)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}

async fn check_state_hash(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    expected: &str,
) -> Result<(), crate::Error> {
    let current = current_state_hash_tx(tx).await?;
    if current != expected {
        return Err(Box::new(HashConflictError {
            expected: expected.to_owned(),
            current,
        }));
    }
    Ok(())
}

async fn current_state_hash_tx(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
) -> Result<String, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT coalesce((SELECT datom_hash FROM event_log ORDER BY sequence DESC LIMIT 1), '0')",
    )
    .fetch_one(&mut **tx)
    .await
}

async fn current_state_hash(pool: &SqlitePool) -> Result<String, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT coalesce((SELECT datom_hash FROM event_log ORDER BY sequence DESC LIMIT 1), '0')",
    )
    .fetch_one(pool)
    .await
}

fn next_datom_hash(
    previous: &str,
    sequence: i64,
    eid: i64,
    attr: &str,
    value_json: &str,
    retract: i64,
    writer_external_id: Option<&str>,
) -> String {
    let mut content_digest = Sha256::new();
    content_digest.update(sequence.to_string().as_bytes());
    content_digest.update(eid.to_string().as_bytes());
    content_digest.update(attr.as_bytes());
    content_digest.update(value_json.as_bytes());
    content_digest.update(retract.to_string().as_bytes());
    if let Some(writer_external_id) = writer_external_id {
        content_digest.update([1]);
        content_digest.update(writer_external_id.as_bytes());
    }
    let content_hash = format!("{:x}", content_digest.finalize());

    let mut chain_digest = Sha256::new();
    chain_digest.update(previous.as_bytes());
    chain_digest.update(content_hash.as_bytes());
    format!("{:x}", chain_digest.finalize())
}

fn mutable_attribute(entity_type: &str, attr: &str) -> bool {
    match entity_type {
        "account" => attr == "account/name",
        "commodity" => attr == "commodity/name",
        "price" => matches!(attr, "price/date" | "price/commodity" | "price/value"),
        "include" => attr == "include/file",
        "transaction" => matches!(
            attr,
            "transaction/date"
                | "transaction/description"
                | "transaction/status"
                | "transaction/code"
        ),
        "posting" => matches!(
            attr,
            "posting/account" | "posting/amount" | "posting/status"
        ),
        "tag" => matches!(attr, "tag/name" | "tag/value"),
        _ => false,
    }
}

async fn load_current_entities(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    eids: &BTreeSet<i64>,
) -> Result<BTreeMap<i64, Entity>, sqlx::Error> {
    let mut entities = BTreeMap::new();
    for chunk in eids.iter().collect::<Vec<_>>().chunks(500) {
        let mut query = QueryBuilder::<Sqlite>::new(
            "SELECT eid, first_sequence, attributes_json FROM current_entities WHERE eid IN (",
        );
        let mut separated = query.separated(", ");
        for eid in chunk {
            separated.push_bind(**eid);
        }
        query.push(")");
        for row in query.build().fetch_all(&mut **tx).await? {
            let eid: i64 = row.try_get("eid")?;
            entities.insert(
                eid,
                Entity {
                    eid,
                    first_sequence: row.try_get("first_sequence")?,
                    attributes: serde_json::from_str::<BTreeMap<String, Value>>(
                        &row.try_get::<String, _>("attributes_json")?,
                    )
                    .map_err(|error| sqlx::Error::Decode(Box::new(error)))?,
                },
            );
        }
    }
    Ok(entities)
}

async fn load_all_current_entities(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
) -> Result<BTreeMap<i64, Entity>, sqlx::Error> {
    let rows = sqlx::query(
        "SELECT eid, first_sequence, attributes_json FROM current_entities ORDER BY eid",
    )
    .fetch_all(&mut **tx)
    .await?;
    rows.into_iter()
        .map(|row| {
            let eid: i64 = row.try_get("eid")?;
            let attributes = serde_json::from_str::<BTreeMap<String, Value>>(
                &row.try_get::<String, _>("attributes_json")?,
            )
            .map_err(|error| sqlx::Error::Decode(Box::new(error)))?;
            Ok((
                eid,
                Entity {
                    eid,
                    first_sequence: row.try_get("first_sequence")?,
                    attributes,
                },
            ))
        })
        .collect()
}

fn apply_current_datom(
    entities: &mut BTreeMap<i64, Entity>,
    eid: i64,
    attr: &str,
    value: &Value,
    retract: bool,
    sequence: i64,
) {
    if retract {
        let should_remove = entities
            .get(&eid)
            .and_then(|entity| entity.attributes.get(attr))
            == Some(value);
        if should_remove {
            let entity = entities.get_mut(&eid).expect("entity exists");
            entity.attributes.remove(attr);
            if entity.attributes.is_empty() {
                entities.remove(&eid);
            }
        }
    } else {
        let entity = entities.entry(eid).or_insert_with(|| Entity {
            eid,
            first_sequence: sequence,
            attributes: BTreeMap::new(),
        });
        entity.attributes.insert(attr.to_owned(), value.clone());
    }
}

async fn replace_current_entities(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    touched_eids: &BTreeSet<i64>,
    entities: &BTreeMap<i64, Entity>,
) -> Result<(), sqlx::Error> {
    let removed_eids = touched_eids
        .iter()
        .filter(|eid| !entities.contains_key(eid))
        .collect::<Vec<_>>();
    for chunk in removed_eids.chunks(500) {
        let mut query = QueryBuilder::<Sqlite>::new("DELETE FROM current_entities WHERE eid IN (");
        let mut separated = query.separated(", ");
        for eid in chunk {
            separated.push_bind(**eid);
        }
        query.push(")");
        query.build().execute(&mut **tx).await?;
    }

    let serialized = entities
        .values()
        .filter(|entity| touched_eids.contains(&entity.eid))
        .map(|entity| {
            Ok((
                entity.eid,
                entity.first_sequence,
                serde_json::to_string(&entity.attributes)?,
            ))
        })
        .collect::<Result<Vec<_>, serde_json::Error>>()
        .map_err(|error| sqlx::Error::Encode(Box::new(error)))?;
    for chunk in serialized.chunks(5_000) {
        let mut query = QueryBuilder::<Sqlite>::new(
            "INSERT INTO current_entities (eid, first_sequence, attributes_json) ",
        );
        query.push_values(chunk, |mut row, (eid, sequence, attributes)| {
            row.push_bind(*eid)
                .push_bind(*sequence)
                .push_bind(attributes);
        });
        query.push(
            " ON CONFLICT(eid) DO UPDATE SET \
             first_sequence = excluded.first_sequence, \
             attributes_json = excluded.attributes_json",
        );
        query.build().execute(&mut **tx).await?;
    }
    Ok(())
}

async fn add_tags(
    tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    datoms: &mut Vec<(i64, &'static str, Value)>,
    parent: i64,
    tags: Option<&Value>,
) -> Result<(), crate::Error> {
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

fn required_string(data: &Value, key: &str) -> Result<String, crate::Error> {
    Ok(required_value(data, key)?
        .as_str()
        .ok_or_else(|| format!("{key} must be a string"))?
        .to_owned())
}

fn required_value(data: &Value, key: &str) -> Result<Value, crate::Error> {
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
