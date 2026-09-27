use std::collections::{BTreeMap, BTreeSet, VecDeque};

use chrono::{Datelike, Local, NaiveDate};
use regex::Regex;
use rust_decimal::Decimal;
use serde::Serialize;
use serde_json::{Value, json};

use crate::options::{FilterOptions, ListOptions, Period, ReportOptions};
use crate::store::Store;

#[derive(Clone, Debug)]
pub struct Entity {
    pub eid: i64,
    pub first_sequence: i64,
    pub attributes: BTreeMap<String, Value>,
}

pub fn fold(datoms: Vec<(i64, i64, String, Value, bool)>) -> BTreeMap<i64, Entity> {
    let mut entities = BTreeMap::<i64, Entity>::new();
    for (sequence, eid, attr, value, retract) in datoms {
        if retract {
            if entities
                .get(&eid)
                .and_then(|entity| entity.attributes.get(&attr))
                == Some(&value)
            {
                let entity = entities.get_mut(&eid).expect("entity exists");
                entity.attributes.remove(&attr);
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
            entity.attributes.insert(attr, value);
        }
    }
    entities
}

#[derive(Clone, Debug, Serialize)]
struct Amount {
    commodity: String,
    #[serde(with = "rust_decimal::serde::str")]
    quantity: Decimal,
    #[serde(skip_serializing_if = "Option::is_none")]
    cost: Option<Cost>,
}

#[derive(Clone, Debug, Serialize)]
struct Cost {
    total: bool,
    #[serde(with = "rust_decimal::serde::str")]
    amount: Decimal,
    commodity: String,
}

#[derive(Clone, Debug)]
struct Posting {
    eid: i64,
    position: i64,
    account: String,
    amount: Option<Amount>,
    status: Option<String>,
    tags: Vec<Tag>,
}

#[derive(Clone, Debug, Serialize)]
struct Tag {
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    value: Option<String>,
}

#[derive(Clone, Debug)]
struct Transaction {
    eid: i64,
    sequence: i64,
    date: NaiveDate,
    description: String,
    status: Option<String>,
    code: Option<String>,
    file: Option<String>,
    tags: Vec<Tag>,
    postings: Vec<Posting>,
}

#[derive(Clone, Debug)]
struct Price {
    date: NaiveDate,
    from: String,
    to: String,
    quantity: Decimal,
}

#[derive(Debug, Clone, Copy)]
pub enum ReportKind {
    Balance,
    BalanceSheet,
    IncomeStatement,
}

pub async fn accounts(store: &Store, args: &ListOptions) -> Result<Value, crate::Error> {
    let entities = store.entities().await?;
    let selected_files = file_closure(&entities, &args.filters.files);
    let transactions = transactions(&entities, &selected_files)?;
    let mut names = BTreeSet::new();
    for entity in entities.values().filter(|e| type_is(e, "account")) {
        if in_selected_files(entity, &selected_files) {
            if let Some(name) = string_attr(entity, "account/name") {
                add_account_tree(&mut names, name);
            }
        }
    }
    for transaction in &transactions {
        for posting in &transaction.postings {
            add_account_tree(&mut names, &posting.account);
        }
    }
    let (include, exclude) = account_queries(&args.filters);
    let names: Vec<String> = names
        .into_iter()
        .filter(|name| {
            args.depth
                .is_none_or(|depth| account_depth(name) <= depth.max(1))
        })
        .filter(|name| account_matches(name, &include, &exclude))
        .collect();
    Ok(json!({"command":"accounts", "accounts": names, "count": names.len()}))
}

pub async fn commodities(store: &Store, filters: &FilterOptions) -> Result<Value, crate::Error> {
    let entities = store.entities().await?;
    let selected_files = file_closure(&entities, &filters.files);
    let mut names = BTreeSet::new();
    for entity in entities
        .values()
        .filter(|entity| in_selected_files(entity, &selected_files))
    {
        match string_attr(entity, "entity/type") {
            Some("commodity") => {
                if let Some(name) = string_attr(entity, "commodity/name") {
                    names.insert(name.to_owned());
                }
            }
            Some("price") => {
                if let Some(name) = string_attr(entity, "price/commodity") {
                    names.insert(name.to_owned());
                }
                if let Some(value) = string_attr(entity, "price/value") {
                    if let Ok(amount) = parse_amount(value) {
                        names.insert(amount.commodity);
                    }
                }
            }
            _ => {}
        }
    }
    for transaction in transactions(&entities, &selected_files)? {
        for posting in transaction.postings {
            if let Some(amount) = posting.amount {
                names.insert(amount.commodity);
                if let Some(cost) = amount.cost {
                    names.insert(cost.commodity);
                }
            }
        }
    }
    let commodities: Vec<_> = names.into_iter().collect();
    Ok(json!({"command":"commodities", "commodities":commodities, "count":commodities.len()}))
}

pub async fn print(store: &Store, filters: &FilterOptions) -> Result<Value, crate::Error> {
    validate_filters(filters)?;
    let entities = store.entities().await?;
    let selected_files = file_closure(&entities, &filters.files);
    let rows = transactions(&entities, &selected_files)?
        .into_iter()
        .filter(|transaction| transaction_matches(transaction, filters))
        .map(transaction_json)
        .collect::<Vec<_>>();
    Ok(json!({"command":"print", "transactions":rows, "count":rows.len()}))
}

pub async fn register(store: &Store, filters: &FilterOptions) -> Result<Value, crate::Error> {
    validate_filters(filters)?;
    let entities = store.entities().await?;
    let selected_files = file_closure(&entities, &filters.files);
    let (include, exclude) = account_queries(filters);
    let mut all = transactions(&entities, &selected_files)?;
    all.retain(|transaction| transaction_matches(transaction, filters));
    all.sort_by_key(|transaction| (transaction.date, transaction.sequence));
    let mut balances = BTreeMap::<String, BTreeMap<String, Decimal>>::new();
    let mut rows = Vec::new();
    for transaction in all {
        for posting in transaction.postings {
            if !account_matches(&posting.account, &include, &exclude) {
                continue;
            }
            let amounts = posting.amount.into_iter().collect::<Vec<_>>();
            let account_balance = balances.entry(posting.account.clone()).or_default();
            for amount in &amounts {
                *account_balance.entry(amount.commodity.clone()).or_default() += amount.quantity;
            }
            rows.push(json!({
                "date":transaction.date,
                "transaction_eid":transaction.eid,
                "posting_eid":posting.eid,
                "description":transaction.description,
                "account":posting.account,
                "status":posting.status.or(transaction.status.clone()),
                "amounts":amounts,
                "running_balance":amount_json(account_balance),
                "tags":posting.tags
            }));
        }
    }
    Ok(json!({"command":"register", "entries":rows, "count":rows.len()}))
}

pub async fn balance(
    store: &Store,
    args: &ReportOptions,
    kind: ReportKind,
) -> Result<Value, crate::Error> {
    validate_filters(&args.filters)?;
    let entities = store.entities().await?;
    let selected_files = file_closure(&entities, &args.filters.files);
    let (include, exclude) = account_queries(&args.filters);
    let mut transactions = transactions(&entities, &selected_files)?;
    transactions.retain(|transaction| transaction_matches(transaction, &args.filters));
    let prices = prices(&entities, &selected_files)?;
    let today = match &args.today {
        Some(value) => date(value)?,
        None => Local::now().date_naive(),
    };
    let interval = args.interval;
    let target = args.exchange.clone().or_else(|| {
        args.market
            .then(|| default_valuation_commodity(&entities, &prices))
            .flatten()
    });

    let mut periods = BTreeMap::<String, BTreeMap<String, BTreeMap<String, Decimal>>>::new();
    for transaction in &transactions {
        for posting in &transaction.postings {
            if !account_matches(&posting.account, &include, &exclude) {
                continue;
            }
            if !report_account_matches(&posting.account, kind) {
                continue;
            }
            let Some(amount) = &posting.amount else {
                continue;
            };
            let key = interval
                .map(|period| period_key(transaction.date, period))
                .unwrap_or_else(|| "total".to_string());
            let value = valued_amount(
                amount,
                &prices,
                target.as_deref(),
                args.cost,
                args.market || target.is_some(),
                period_end(&key, interval).unwrap_or(today),
            )?;
            let by_account = periods.entry(key).or_default();
            let report_account = truncate_account(&posting.account, args.depth);
            for (commodity, quantity) in value {
                *by_account
                    .entry(report_account.clone())
                    .or_default()
                    .entry(commodity)
                    .or_default() += quantity;
            }
        }
    }

    if args.historical {
        let mut running = BTreeMap::<String, BTreeMap<String, Decimal>>::new();
        for by_account in periods.values_mut() {
            for (account, amounts) in by_account.iter_mut() {
                let total = running.entry(account.clone()).or_default();
                for (commodity, quantity) in amounts.iter_mut() {
                    *total.entry(commodity.clone()).or_default() += *quantity;
                    *quantity = total[commodity];
                }
            }
        }
    }

    let mut report_periods = Vec::new();
    for (period, accounts) in periods {
        let rows: Vec<_> = accounts
            .into_iter()
            .filter(|(_, amounts)| args.empty || amounts.values().any(|amount| !amount.is_zero()))
            .map(|(account, amounts)| json!({"account":account,"amounts":amount_json(&amounts)}))
            .collect();
        report_periods.push(json!({"period":period,"rows":rows}));
    }
    let command = match kind {
        ReportKind::Balance => "balance",
        ReportKind::BalanceSheet => "balancesheet",
        ReportKind::IncomeStatement => "incomestatement",
    };
    if args.transpose {
        let mut accounts = BTreeMap::<String, Vec<Value>>::new();
        for period in &report_periods {
            let period_name = period["period"].as_str().unwrap_or_default();
            if let Some(rows) = period["rows"].as_array() {
                for row in rows {
                    if let Some(account) = row["account"].as_str() {
                        accounts.entry(account.to_owned()).or_default().push(json!({
                            "period":period_name,
                            "amounts":row["amounts"]
                        }));
                    }
                }
            }
        }
        let rows = accounts
            .into_iter()
            .map(|(account, periods)| json!({"account":account,"periods":periods}))
            .collect::<Vec<_>>();
        Ok(json!({
            "command":command,
            "historical":args.historical,
            "valuation":if args.cost {"cost"} else if args.market || target.is_some() {"market"} else {"native"},
            "commodity":target,
            "orientation":"accounts",
            "commodity_style":args.commodity_style,
            "rows":rows
        }))
    } else {
        Ok(json!({
            "command":command,
            "historical":args.historical,
            "valuation":if args.cost {"cost"} else if args.market || target.is_some() {"market"} else {"native"},
            "commodity":target,
            "commodity_style":args.commodity_style,
            "periods":report_periods
        }))
    }
}

fn transactions(
    entities: &BTreeMap<i64, Entity>,
    selected_files: &BTreeSet<String>,
) -> Result<Vec<Transaction>, crate::Error> {
    let mut postings_by_parent = BTreeMap::<i64, Vec<&Entity>>::new();
    let mut tags_by_parent = BTreeMap::<i64, Vec<Tag>>::new();
    for entity in entities.values() {
        if type_is(entity, "posting") {
            if let Some(parent) = int_attr(entity, "posting/parent-eid") {
                postings_by_parent.entry(parent).or_default().push(entity);
            }
        } else if type_is(entity, "tag")
            && let Some(parent) = int_attr(entity, "tag/parent-eid")
        {
            tags_by_parent.entry(parent).or_default().push(Tag {
                name: string_attr(entity, "tag/name").unwrap_or("").to_owned(),
                value: string_attr(entity, "tag/value").map(str::to_owned),
            });
        }
    }
    for tags in tags_by_parent.values_mut() {
        tags.sort_by(|a, b| (&a.name, &a.value).cmp(&(&b.name, &b.value)));
    }

    let mut result = Vec::new();
    for root in entities
        .values()
        .filter(|entity| type_is(entity, "transaction"))
    {
        if !in_selected_files(root, selected_files) {
            continue;
        }
        let date = date(
            string_attr(root, "transaction/date").ok_or("transaction missing transaction/date")?,
        )?;
        let mut postings: Vec<Posting> = postings_by_parent
            .get(&root.eid)
            .into_iter()
            .flatten()
            .map(|entity| {
                Ok(Posting {
                    eid: entity.eid,
                    position: int_attr(entity, "posting/position").unwrap_or(entity.eid * 1000),
                    account: string_attr(entity, "posting/account")
                        .ok_or("posting missing posting/account")?
                        .to_owned(),
                    amount: string_attr(entity, "posting/amount")
                        .map(parse_amount)
                        .transpose()?,
                    status: string_attr(entity, "posting/status").map(str::to_owned),
                    tags: tags_by_parent.get(&entity.eid).cloned().unwrap_or_default(),
                })
            })
            .collect::<Result<_, crate::Error>>()?;
        postings.sort_by_key(|posting| (posting.position, posting.eid));
        infer_missing_amount(&mut postings);
        result.push(Transaction {
            eid: root.eid,
            sequence: root.first_sequence,
            date,
            description: string_attr(root, "transaction/description")
                .unwrap_or("")
                .to_owned(),
            status: string_attr(root, "transaction/status").map(str::to_owned),
            code: string_attr(root, "transaction/code").map(str::to_owned),
            file: string_attr(root, "entity/file").map(str::to_owned),
            tags: tags_by_parent.get(&root.eid).cloned().unwrap_or_default(),
            postings,
        });
    }
    Ok(result)
}

fn file_closure(entities: &BTreeMap<i64, Entity>, requested: &[String]) -> BTreeSet<String> {
    let mut selected = requested.iter().cloned().collect::<BTreeSet<_>>();
    let mut queue = requested.iter().cloned().collect::<VecDeque<_>>();
    while let Some(file) = queue.pop_front() {
        for include in entities.values().filter(|entity| {
            type_is(entity, "include") && string_attr(entity, "entity/file") == Some(file.as_str())
        }) {
            if let Some(path) = string_attr(include, "include/file") {
                if selected.insert(path.to_owned()) {
                    queue.push_back(path.to_owned());
                }
            }
        }
    }
    selected
}

fn in_selected_files(entity: &Entity, files: &BTreeSet<String>) -> bool {
    files.is_empty() || string_attr(entity, "entity/file").is_some_and(|file| files.contains(file))
}

fn transaction_matches(transaction: &Transaction, filters: &FilterOptions) -> bool {
    let (begin, end) = date_bounds(filters).unwrap_or((None, None));
    if let Some(begin) = begin {
        if transaction.date < begin {
            return false;
        }
    }
    if let Some(end) = end {
        if transaction.date >= end {
            return false;
        }
    }
    if !filters.tags.is_empty()
        && !filters
            .tags
            .iter()
            .all(|tag| transaction_has_tag(transaction, tag))
    {
        return false;
    }
    let (include, exclude) = account_queries(filters);
    include.is_empty() && exclude.is_empty()
        || transaction
            .postings
            .iter()
            .any(|posting| account_matches(&posting.account, &include, &exclude))
}

fn validate_filters(filters: &FilterOptions) -> Result<(), crate::Error> {
    date_bounds(filters)?;
    for term in &filters.query {
        let term = term.strip_prefix("not:").unwrap_or(term);
        if term.is_empty() {
            return Err("account query cannot be empty".into());
        }
    }
    Ok(())
}

fn date_bounds(
    filters: &FilterOptions,
) -> Result<(Option<NaiveDate>, Option<NaiveDate>), crate::Error> {
    let mut begin = filters.begin.as_deref().map(date).transpose()?;
    let mut end = filters.end.as_deref().map(date).transpose()?;
    if let Some(period) = &filters.date_period {
        let (period_begin, period_end) = parse_date_period(period)?;
        begin = Some(begin.map_or(period_begin, |value| value.max(period_begin)));
        end = Some(end.map_or(period_end, |value| value.min(period_end)));
    }
    if begin
        .zip(end)
        .is_some_and(|(start, finish)| start >= finish)
    {
        return Err("begin date must be before end date".into());
    }
    Ok((begin, end))
}

fn parse_date_period(raw: &str) -> Result<(NaiveDate, NaiveDate), crate::Error> {
    if let Some((start, end)) = raw.split_once("..") {
        let start = date(start)?;
        let end = date(end)?;
        if start >= end {
            return Err("period start must be before period end".into());
        }
        return Ok((start, end));
    }
    if let Ok(year) = raw.parse::<i32>() {
        return Ok((
            NaiveDate::from_ymd_opt(year, 1, 1).ok_or("invalid year")?,
            NaiveDate::from_ymd_opt(year + 1, 1, 1).ok_or("invalid year")?,
        ));
    }
    if let Ok(start) = NaiveDate::parse_from_str(&format!("{raw}-01"), "%Y-%m-%d") {
        let end = if start.month() == 12 {
            NaiveDate::from_ymd_opt(start.year() + 1, 1, 1).ok_or("invalid month")?
        } else {
            NaiveDate::from_ymd_opt(start.year(), start.month() + 1, 1).ok_or("invalid month")?
        };
        return Ok((start, end));
    }
    Err(format!("invalid period {raw:?}; expected YYYY, YYYY-MM, or START..END").into())
}

fn account_queries(filters: &FilterOptions) -> (Vec<String>, Vec<String>) {
    let mut include = filters.accounts.clone();
    let mut exclude = filters.not_accounts.clone();
    for term in &filters.query {
        if let Some(term) = term.strip_prefix("not:") {
            exclude.push(term.to_owned());
        } else {
            include.push(term.to_owned());
        }
    }
    (include, exclude)
}

fn transaction_has_tag(transaction: &Transaction, query: &str) -> bool {
    let (name, value) = query.split_once('=').unwrap_or((query, ""));
    transaction
        .tags
        .iter()
        .chain(
            transaction
                .postings
                .iter()
                .flat_map(|posting| posting.tags.iter()),
        )
        .any(|tag| tag.name == name && (value.is_empty() || tag.value.as_deref() == Some(value)))
}

fn account_matches(account: &str, include: &[String], exclude: &[String]) -> bool {
    let has_include = include.is_empty()
        || include
            .iter()
            .any(|pattern| account_pattern(account, pattern));
    let excluded = exclude
        .iter()
        .any(|pattern| account_pattern(account, pattern));
    has_include && !excluded
}

fn account_pattern(account: &str, pattern: &str) -> bool {
    if pattern
        .chars()
        .any(|character| "^$.*+?()[]{}|\\".contains(character))
    {
        Regex::new(&format!("(?i){pattern}")).is_ok_and(|regex| regex.is_match(account))
    } else {
        account.to_lowercase().starts_with(&pattern.to_lowercase())
    }
}

fn report_account_matches(account: &str, kind: ReportKind) -> bool {
    let root = account.split(':').next().unwrap_or("").to_lowercase();
    match kind {
        ReportKind::Balance => true,
        ReportKind::BalanceSheet => matches!(
            root.as_str(),
            "assets" | "asset" | "liabilities" | "liability" | "equity"
        ),
        ReportKind::IncomeStatement => {
            matches!(root.as_str(), "income" | "revenue" | "expenses" | "expense")
        }
    }
}

fn add_account_tree(names: &mut BTreeSet<String>, account: &str) {
    let mut path = String::new();
    for part in account.split(':') {
        if !path.is_empty() {
            path.push(':');
        }
        path.push_str(part);
        names.insert(path.clone());
    }
}

fn account_depth(account: &str) -> usize {
    account.split(':').count()
}

fn truncate_account(account: &str, depth: Option<usize>) -> String {
    let Some(depth) = depth else {
        return account.to_owned();
    };
    account
        .split(':')
        .take(depth.max(1))
        .collect::<Vec<_>>()
        .join(":")
}

fn transaction_json(transaction: Transaction) -> Value {
    json!({
        "eid":transaction.eid,
        "sequence":transaction.sequence,
        "date":transaction.date,
        "status":transaction.status,
        "code":transaction.code,
        "description":transaction.description,
        "file":transaction.file,
        "tags":transaction.tags,
        "postings":transaction.postings.into_iter().map(|posting| json!({
            "eid":posting.eid,"account":posting.account,"status":posting.status,
            "amount":posting.amount,"tags":posting.tags
        })).collect::<Vec<_>>()
    })
}

fn infer_missing_amount(postings: &mut [Posting]) {
    let missing = postings
        .iter()
        .enumerate()
        .filter_map(|(index, posting)| posting.amount.is_none().then_some(index))
        .collect::<Vec<_>>();
    if missing.len() == 1 {
        let mut sums = BTreeMap::<String, Decimal>::new();
        for posting in postings
            .iter()
            .filter_map(|posting| posting.amount.as_ref())
        {
            *sums.entry(posting.commodity.clone()).or_default() += posting.quantity;
        }
        let mut nonzero = sums
            .into_iter()
            .filter(|(_, quantity)| !quantity.is_zero())
            .collect::<Vec<_>>();
        if nonzero.len() == 1 {
            let (commodity, quantity) = nonzero.pop().expect("one amount");
            postings[missing[0]].amount = Some(Amount {
                commodity,
                quantity: -quantity,
                cost: None,
            });
        }
    }
}

fn parse_amount(raw: &str) -> Result<Amount, crate::Error> {
    let trimmed = raw.trim();
    let cost_split = trimmed
        .split_once("@@")
        .map(|(a, b)| (a.trim(), b.trim(), true))
        .or_else(|| {
            trimmed
                .split_once('@')
                .map(|(a, b)| (a.trim(), b.trim(), false))
        });
    let (base, cost) = if let Some((base, cost_text, total)) = cost_split {
        let (amount, commodity) = parse_quantity_commodity(cost_text)?;
        (
            base,
            Some(Cost {
                total,
                amount,
                commodity,
            }),
        )
    } else {
        (trimmed, None)
    };
    let (quantity, commodity) = parse_quantity_commodity(base)?;
    Ok(Amount {
        commodity,
        quantity,
        cost,
    })
}

fn parse_quantity_commodity(raw: &str) -> Result<(Decimal, String), crate::Error> {
    let raw = raw.trim();
    if raw.is_empty() {
        return Err("empty amount".into());
    }
    let tokens = raw.split_whitespace().collect::<Vec<_>>();
    if tokens.len() == 1 {
        let token = tokens[0];
        let (quantity, suffix) = split_numeric_prefix(token)?;
        return Ok((quantity, suffix.to_owned()));
    }
    if let Ok(quantity) = parse_decimal(tokens[0]) {
        let commodity = tokens[1..].join(" ");
        return Ok((quantity, commodity));
    }
    if let Ok(quantity) = parse_decimal(tokens[1]) {
        let commodity = tokens[0].to_owned();
        return Ok((quantity, commodity));
    }
    Err(format!("Cannot parse amount: {raw}").into())
}

fn split_numeric_prefix(token: &str) -> Result<(Decimal, &str), crate::Error> {
    let start = token
        .find(|ch: char| ch.is_ascii_digit() || ch == '-' || ch == '+' || ch == '.')
        .ok_or_else(|| format!("amount has no number: {token}"))?;
    let end = token[start..]
        .find(|ch: char| !(ch.is_ascii_digit() || ch == ',' || ch == '.' || ch == '-' || ch == '+'))
        .map(|offset| start + offset)
        .unwrap_or(token.len());
    let before = &token[..start];
    let number = &token[start..end];
    let after = &token[end..];
    let commodity = if before.is_empty() { after } else { before };
    Ok((parse_decimal(number)?, commodity))
}

fn parse_decimal(raw: &str) -> Result<Decimal, crate::Error> {
    let has_comma = raw.contains(',');
    let has_dot = raw.contains('.');
    let normalized = match (has_comma, has_dot) {
        (true, true) => {
            let comma = raw.rfind(',').unwrap_or(0);
            let dot = raw.rfind('.').unwrap_or(0);
            if comma > dot {
                raw.replace('.', "").replace(',', ".")
            } else {
                raw.replace(',', "")
            }
        }
        (true, false) => {
            let last_group = raw.rsplit(',').next().unwrap_or("");
            if last_group.len() == 3 {
                raw.replace(',', "")
            } else {
                raw.replace(',', ".")
            }
        }
        _ => raw.to_owned(),
    };
    Ok(normalized.parse::<Decimal>()?)
}

fn prices(
    entities: &BTreeMap<i64, Entity>,
    selected_files: &BTreeSet<String>,
) -> Result<Vec<Price>, crate::Error> {
    entities
        .values()
        .filter(|entity| type_is(entity, "price") && in_selected_files(entity, selected_files))
        .map(|entity| {
            let from = string_attr(entity, "price/commodity")
                .ok_or("price missing commodity")?
                .to_owned();
            let price_date = date(string_attr(entity, "price/date").ok_or("price missing date")?)?;
            let value =
                parse_amount(string_attr(entity, "price/value").ok_or("price missing value")?)?;
            Ok(Price {
                date: price_date,
                from,
                to: value.commodity,
                quantity: value.quantity,
            })
        })
        .collect()
}

fn default_valuation_commodity(
    entities: &BTreeMap<i64, Entity>,
    prices: &[Price],
) -> Option<String> {
    let declared = entities
        .values()
        .find(|entity| type_is(entity, "default-commodity"))
        .and_then(|entity| string_attr(entity, "default-commodity/value"))
        .and_then(|value| parse_amount(value).ok())
        .map(|amount| amount.commodity);
    if declared.is_some() {
        return declared;
    }

    let mut counts = BTreeMap::<String, usize>::new();
    for price in prices {
        *counts.entry(price.to.clone()).or_default() += 1;
    }
    counts
        .into_iter()
        .max_by(|(left_name, left_count), (right_name, right_count)| {
            left_count
                .cmp(right_count)
                .then_with(|| right_name.cmp(left_name))
        })
        .map(|(commodity, _)| commodity)
}

fn valued_amount(
    amount: &Amount,
    prices: &[Price],
    target: Option<&str>,
    cost_mode: bool,
    market_mode: bool,
    at: NaiveDate,
) -> Result<BTreeMap<String, Decimal>, crate::Error> {
    let (quantity, commodity) = if cost_mode {
        match &amount.cost {
            Some(cost) => (
                if cost.total {
                    if amount.quantity.is_sign_negative() {
                        -cost.amount.abs()
                    } else {
                        cost.amount.abs()
                    }
                } else {
                    amount.quantity * cost.amount
                },
                cost.commodity.as_str(),
            ),
            None => (amount.quantity, amount.commodity.as_str()),
        }
    } else {
        (amount.quantity, amount.commodity.as_str())
    };
    if let Some(target) = target {
        let quantity = if commodity == target {
            quantity
        } else if cost_mode && !market_mode {
            quantity
        } else {
            quantity
                * exchange_rate(prices, commodity, target, at)
                    .ok_or_else(|| format!("No price path from {commodity} to {target} on {at}"))?
        };
        return Ok(BTreeMap::from([(target.to_owned(), quantity)]));
    }
    if market_mode {
        let first = prices
            .iter()
            .filter(|price| price.from == commodity && price.date <= at)
            .max_by_key(|price| price.date);
        if let Some(price) = first {
            return Ok(BTreeMap::from([(
                price.to.clone(),
                quantity * price.quantity,
            )]));
        }
    }
    Ok(BTreeMap::from([(commodity.to_owned(), quantity)]))
}

fn exchange_rate(prices: &[Price], from: &str, to: &str, at: NaiveDate) -> Option<Decimal> {
    let mut latest = BTreeMap::<(String, String), (NaiveDate, Decimal)>::new();
    for price in prices.iter().filter(|price| price.date <= at) {
        let key = (price.from.clone(), price.to.clone());
        if latest.get(&key).is_none_or(|(date, _)| *date < price.date) {
            latest.insert(key, (price.date, price.quantity));
        }
    }
    let mut queue = VecDeque::from([(from.to_owned(), Decimal::ONE)]);
    let mut seen = BTreeSet::from([from.to_owned()]);
    while let Some((commodity, rate)) = queue.pop_front() {
        let mut edges = Vec::<(String, Decimal)>::new();
        for ((edge_from, edge_to), (_, price)) in &latest {
            if edge_from == &commodity {
                edges.push((edge_to.clone(), *price));
            }
            if edge_to == &commodity && !price.is_zero() {
                edges.push((edge_from.clone(), Decimal::ONE / *price));
            }
        }
        edges.sort_by(|a, b| a.0.cmp(&b.0));
        for (next, factor) in edges {
            let new_rate = rate * factor;
            if next == to {
                return Some(new_rate);
            }
            if seen.insert(next.clone()) {
                queue.push_back((next, new_rate));
            }
        }
    }
    None
}

fn period_key(date: NaiveDate, period: Period) -> String {
    match period {
        Period::Daily => date.format("%Y-%m-%d").to_string(),
        Period::Weekly => {
            let week = date.iso_week();
            format!("{}-W{:02}", week.year(), week.week())
        }
        Period::Monthly => date.format("%Y-%m").to_string(),
        Period::Quarterly => format!("{}-Q{}", date.year(), (date.month0() / 3) + 1),
        Period::Yearly => date.format("%Y").to_string(),
    }
}

fn period_end(key: &str, period: Option<Period>) -> Option<NaiveDate> {
    match period? {
        Period::Daily => date(key).ok(),
        Period::Weekly => {
            let mut parts = key.split("-W");
            let year = parts.next()?.parse::<i32>().ok()?;
            let week = parts.next()?.parse::<u32>().ok()?;
            NaiveDate::from_isoywd_opt(year, week, chrono::Weekday::Sun)
        }
        Period::Monthly => {
            let start = NaiveDate::parse_from_str(&format!("{key}-01"), "%Y-%m-%d").ok()?;
            next_month(start)
        }
        Period::Quarterly => {
            let (year, quarter) = key.split_once("-Q")?;
            let year = year.parse::<i32>().ok()?;
            let next_quarter = quarter.parse::<u32>().ok()?.checked_add(1)?;
            if next_quarter > 4 {
                NaiveDate::from_ymd_opt(year + 1, 1, 1)?.pred_opt()
            } else {
                NaiveDate::from_ymd_opt(year, (next_quarter - 1) * 3 + 1, 1)?.pred_opt()
            }
        }
        Period::Yearly => {
            let year = key.parse::<i32>().ok()?;
            NaiveDate::from_ymd_opt(year + 1, 1, 1)?.pred_opt()
        }
    }
}

fn next_month(date: NaiveDate) -> Option<NaiveDate> {
    if date.month() == 12 {
        NaiveDate::from_ymd_opt(date.year() + 1, 1, 1)?.pred_opt()
    } else {
        NaiveDate::from_ymd_opt(date.year(), date.month() + 1, 1)?.pred_opt()
    }
}

fn date(raw: &str) -> Result<NaiveDate, crate::Error> {
    Ok(NaiveDate::parse_from_str(raw, "%Y-%m-%d")?)
}
fn type_is(entity: &Entity, expected: &str) -> bool {
    string_attr(entity, "entity/type") == Some(expected)
}
fn string_attr<'a>(entity: &'a Entity, key: &str) -> Option<&'a str> {
    entity.attributes.get(key)?.as_str()
}
fn int_attr(entity: &Entity, key: &str) -> Option<i64> {
    entity.attributes.get(key)?.as_i64()
}

fn amount_json(amounts: &BTreeMap<String, Decimal>) -> Vec<Value> {
    amounts.iter().map(|(commodity,quantity)|json!({"commodity":commodity,"quantity":quantity.normalize().to_string()})).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn d(value: &str) -> Decimal {
        value.parse().unwrap()
    }

    fn empty_transaction() -> Transaction {
        Transaction {
            eid: 1,
            sequence: 1,
            date: date("2025-01-01").unwrap(),
            description: String::new(),
            status: None,
            code: None,
            file: None,
            tags: Vec::new(),
            postings: Vec::new(),
        }
    }

    fn posting(account: &str, amount: Option<&str>) -> Posting {
        Posting {
            eid: 10,
            position: 1000,
            account: account.into(),
            amount: amount.map(|value| parse_amount(value).unwrap()),
            status: None,
            tags: Vec::new(),
        }
    }

    fn price(date_value: &str, from: &str, to: &str, quantity: &str) -> Price {
        Price {
            date: date(date_value).unwrap(),
            from: from.into(),
            to: to.into(),
            quantity: d(quantity),
        }
    }

    #[test]
    fn fold_keeps_latest_attribute_value_and_original_sequence() {
        let entities = fold(vec![
            (1, 7, "entity/type".into(), json!("transaction"), false),
            (2, 7, "transaction/description".into(), json!("old"), false),
            (3, 7, "transaction/description".into(), json!("new"), false),
        ]);
        assert_eq!(entities[&7].first_sequence, 1);
        assert_eq!(
            string_attr(&entities[&7], "transaction/description"),
            Some("new")
        );
    }

    #[test]
    fn fold_retracts_only_the_matching_value_and_removes_empty_entities() {
        let entities = fold(vec![
            (1, 7, "x".into(), json!("old"), false),
            (2, 7, "x".into(), json!("new"), false),
            (3, 7, "x".into(), json!("old"), true),
        ]);
        assert_eq!(entities[&7].attributes["x"], json!("new"));
        assert!(
            fold(vec![
                (1, 8, "x".into(), json!(1), false),
                (2, 8, "x".into(), json!(1), true)
            ])
            .is_empty()
        );
    }

    #[test]
    fn amount_parser_handles_prefix_suffix_grouping_and_costs() {
        assert_eq!(parse_amount("USD 12.50").unwrap().commodity, "USD");
        assert_eq!(parse_amount("12.50 EUR").unwrap().commodity, "EUR");
        assert_eq!(parse_amount("€1.234,50").unwrap().quantity, d("1234.50"));
        assert_eq!(parse_amount("1,234.50 USD").unwrap().quantity, d("1234.50"));
        let unit = parse_amount("BTC 0.25 @ USD 40000").unwrap();
        assert_eq!(unit.cost.as_ref().unwrap().amount, d("40000"));
        assert!(!unit.cost.unwrap().total);
        let total = parse_amount("BTC -0.25 @@ USD 10000").unwrap();
        assert_eq!(total.cost.as_ref().unwrap().amount, d("10000"));
        assert!(total.cost.unwrap().total);
        assert!(parse_amount(" ").is_err());
        assert!(parse_amount("amount only").is_err());
    }

    #[test]
    fn amount_inference_handles_single_missing_and_ambiguous_cases() {
        let mut one = vec![
            posting("Assets:Cash", Some("USD 12")),
            posting("Expenses:Food", None),
        ];
        infer_missing_amount(&mut one);
        assert_eq!(one[1].amount.as_ref().unwrap().quantity, d("-12"));

        let mut multiple = vec![posting("A", None), posting("B", None)];
        infer_missing_amount(&mut multiple);
        assert!(multiple.iter().all(|item| item.amount.is_none()));

        let mut mixed = vec![
            posting("A", Some("USD 2")),
            posting("B", Some("EUR 3")),
            posting("C", None),
        ];
        infer_missing_amount(&mut mixed);
        assert!(mixed[2].amount.is_none());
    }

    #[test]
    fn account_queries_support_prefix_regex_and_exclusion() {
        assert!(account_matches("Expenses:Food", &["Expenses".into()], &[]));
        assert!(account_matches("Assets:Bank", &["^Assets:.*".into()], &[]));
        assert!(!account_matches("Expenses:Food", &[], &["Expenses".into()]));
        assert!(!account_matches("Income:Sales", &["Expenses".into()], &[]));
        let filters = FilterOptions {
            query: vec!["^Assets".into(), "not:Assets:Hidden".into()],
            ..FilterOptions::default()
        };
        let (include, exclude) = account_queries(&filters);
        assert_eq!(include, ["^Assets"]);
        assert_eq!(exclude, ["Assets:Hidden"]);
    }

    #[test]
    fn tag_filter_matches_bare_and_valued_tags() {
        let mut transaction = empty_transaction();
        transaction.tags.push(Tag {
            name: "project".into(),
            value: Some("alpha".into()),
        });
        assert!(transaction_has_tag(&transaction, "project"));
        assert!(transaction_has_tag(&transaction, "project=alpha"));
        assert!(!transaction_has_tag(&transaction, "project=beta"));
        assert!(!transaction_has_tag(&transaction, "other"));
        transaction.tags.push(Tag {
            name: "reviewed".into(),
            value: None,
        });
        assert!(transaction_has_tag(&transaction, "reviewed"));
    }

    #[test]
    fn file_closure_expands_includes_and_terminates_on_cycles() {
        let entities = fold(vec![
            (1, 1, "entity/type".into(), json!("include"), false),
            (2, 1, "entity/file".into(), json!("main"), false),
            (3, 1, "include/file".into(), json!("prices"), false),
            (4, 2, "entity/type".into(), json!("include"), false),
            (5, 2, "entity/file".into(), json!("prices"), false),
            (6, 2, "include/file".into(), json!("main"), false),
        ]);
        assert_eq!(
            file_closure(&entities, &["main".into()]),
            BTreeSet::from(["main".into(), "prices".into()])
        );
        assert!(in_selected_files(&entities[&1], &BTreeSet::new()));
    }

    #[test]
    fn date_ranges_and_filter_intersections_are_half_open() {
        assert_eq!(
            parse_date_period("2025").unwrap(),
            (date("2025-01-01").unwrap(), date("2026-01-01").unwrap())
        );
        assert_eq!(
            parse_date_period("2025-02").unwrap(),
            (date("2025-02-01").unwrap(), date("2025-03-01").unwrap())
        );
        assert_eq!(
            parse_date_period("2025-03-01..2025-04-01").unwrap(),
            (date("2025-03-01").unwrap(), date("2025-04-01").unwrap())
        );
        assert!(parse_date_period("2025-04-01..2025-03-01").is_err());
        assert!(parse_date_period("yesterday").is_err());
        let filters = FilterOptions {
            begin: Some("2025-02-01".into()),
            date_period: Some("2025".into()),
            ..FilterOptions::default()
        };
        assert_eq!(
            date_bounds(&filters).unwrap(),
            (
                Some(date("2025-02-01").unwrap()),
                Some(date("2026-01-01").unwrap())
            )
        );
    }

    #[test]
    fn transaction_filter_combines_dates_accounts_and_tags() {
        let mut transaction = empty_transaction();
        transaction.date = date("2025-02-10").unwrap();
        transaction
            .postings
            .push(posting("Expenses:Food", Some("USD 3")));
        transaction.tags.push(Tag {
            name: "trip".into(),
            value: Some("work".into()),
        });
        let filters = FilterOptions {
            begin: Some("2025-02-01".into()),
            end: Some("2025-03-01".into()),
            accounts: vec!["Expenses".into()],
            tags: vec!["trip=work".into()],
            ..FilterOptions::default()
        };
        assert!(transaction_matches(&transaction, &filters));
        assert!(!transaction_matches(
            &transaction,
            &FilterOptions {
                begin: Some("2025-02-11".into()),
                ..filters.clone()
            }
        ));
        assert!(!transaction_matches(
            &transaction,
            &FilterOptions {
                tags: vec!["trip=personal".into()],
                ..filters
            }
        ));
    }

    #[test]
    fn account_depth_and_report_roots_are_consistent() {
        assert_eq!(account_depth("Assets:Cash:Wallet"), 3);
        assert_eq!(
            truncate_account("Assets:Cash:Wallet", Some(2)),
            "Assets:Cash"
        );
        assert_eq!(truncate_account("Assets:Cash", Some(0)), "Assets");
        assert!(report_account_matches(
            "Equity:Opening",
            ReportKind::BalanceSheet
        ));
        assert!(report_account_matches(
            "Expenses:Food",
            ReportKind::IncomeStatement
        ));
        assert!(!report_account_matches(
            "Assets:Cash",
            ReportKind::IncomeStatement
        ));
    }

    #[test]
    fn interval_keys_and_end_dates_cover_calendar_periods() {
        let date_value = date("2025-02-05").unwrap();
        assert_eq!(period_key(date_value, Period::Daily), "2025-02-05");
        assert_eq!(period_key(date_value, Period::Weekly), "2025-W06");
        assert_eq!(period_key(date_value, Period::Monthly), "2025-02");
        assert_eq!(period_key(date_value, Period::Quarterly), "2025-Q1");
        assert_eq!(period_key(date_value, Period::Yearly), "2025");
        assert_eq!(
            period_end("2025-02", Some(Period::Monthly)),
            Some(date("2025-02-28").unwrap())
        );
    }

    #[test]
    fn exchange_rates_use_latest_dated_direct_inverse_and_cross_edges() {
        let prices = vec![
            price("2025-01-01", "EUR", "USD", "2"),
            price("2025-02-01", "EUR", "USD", "3"),
            price("2025-01-01", "USD", "INR", "80"),
        ];
        let at_february = date("2025-02-10").unwrap();
        assert_eq!(
            exchange_rate(&prices, "EUR", "USD", at_february),
            Some(d("3"))
        );
        assert_eq!(
            exchange_rate(&prices, "EUR", "INR", at_february),
            Some(d("240"))
        );
        assert_eq!(
            exchange_rate(&prices, "EUR", "INR", date("2025-01-15").unwrap()),
            Some(d("160"))
        );
        assert_eq!(
            exchange_rate(&prices, "EUR", "INR", date("2024-01-01").unwrap()),
            None
        );
        assert!(
            exchange_rate(&prices, "USD", "EUR", at_february)
                .unwrap()
                .round_dp(2)
                == d("0.33")
        );
    }

    #[test]
    fn valuation_handles_native_cost_market_and_missing_paths() {
        let at = date("2025-02-01").unwrap();
        let prices = vec![price("2025-01-01", "BTC", "USD", "40000")];
        let amount = parse_amount("BTC 0.5 @@ USD 20000").unwrap();
        assert_eq!(
            valued_amount(&amount, &prices, None, false, false, at).unwrap()["BTC"],
            d("0.5")
        );
        assert_eq!(
            valued_amount(&amount, &prices, None, true, false, at).unwrap()["USD"],
            d("20000")
        );
        assert_eq!(
            valued_amount(&amount, &prices, Some("USD"), false, true, at).unwrap()["USD"],
            d("20000")
        );
        assert!(valued_amount(&amount, &prices, Some("EUR"), false, true, at).is_err());

        let unit_cost = parse_amount("BTC 2 @ USD 500").unwrap();
        assert_eq!(
            valued_amount(&unit_cost, &prices, None, true, false, at).unwrap()["USD"],
            d("1000")
        );
        let negative_total = parse_amount("BTC -0.5 @@ USD 20000").unwrap();
        assert_eq!(
            valued_amount(&negative_total, &prices, None, true, false, at).unwrap()["USD"],
            d("-20000")
        );
    }
}
