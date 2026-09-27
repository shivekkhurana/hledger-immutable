use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug)]
pub enum ImportedEntity {
    Account {
        file: String,
        name: String,
    },
    Commodity {
        file: String,
        name: String,
    },
    Price {
        file: String,
        date: String,
        commodity: String,
        value: String,
    },
    Include {
        file: String,
        path: String,
    },
    Alias {
        file: String,
        old: String,
        new: String,
    },
    DecimalMark {
        file: String,
        value: String,
    },
    DefaultCommodity {
        file: String,
        value: String,
    },
    Payee {
        file: String,
        name: String,
    },
    TagDeclaration {
        file: String,
        name: String,
    },
    Transaction(ImportedTransaction),
}

#[derive(Clone, Debug)]
pub struct ImportedTransaction {
    pub file: String,
    pub date: String,
    pub description: String,
    pub status: Option<String>,
    pub code: Option<String>,
    pub tags: Vec<ImportedTag>,
    pub postings: Vec<ImportedPosting>,
}

#[derive(Clone, Debug)]
pub struct ImportedPosting {
    pub account: String,
    pub amount: Option<String>,
    pub status: Option<String>,
    pub tags: Vec<ImportedTag>,
}

#[derive(Clone, Debug)]
pub struct ImportedTag {
    pub name: String,
    pub value: Option<String>,
}

#[derive(Debug)]
pub struct JournalImport {
    pub entities: Vec<ImportedEntity>,
    pub source_files: usize,
}

#[derive(Debug)]
struct PendingTransaction(ImportedTransaction);

pub fn read_folder(source: &Path) -> Result<JournalImport, crate::Error> {
    let root = source.canonicalize()?;
    if !root.is_dir() {
        return Err(format!("Source is not a directory: {}", source.display()).into());
    }

    let mut files = Vec::new();
    collect_journals(&root, &mut files)?;
    files.sort();
    if files.is_empty() {
        return Err(format!("No .journal files found under {}", source.display()).into());
    }

    let mut entities = Vec::new();
    let mut known_files = BTreeSet::new();
    for path in &files {
        known_files.insert(relative_key(&root, path)?);
    }

    for path in &files {
        let file = relative_key(&root, path)?;
        let contents =
            fs::read_to_string(path).map_err(|error| format!("{}: {error}", path.display()))?;
        parse_journal(path, &file, &root, &contents, &known_files, &mut entities)?;
    }
    Ok(JournalImport {
        entities,
        source_files: files.len(),
    })
}

fn collect_journals(directory: &Path, files: &mut Vec<PathBuf>) -> Result<(), crate::Error> {
    let mut entries = fs::read_dir(directory)?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.path());
    for entry in entries {
        let kind = entry.file_type()?;
        let path = entry.path();
        if kind.is_symlink() {
            return Err(format!("Refusing to follow source symlink: {}", path.display()).into());
        }
        if kind.is_dir() {
            collect_journals(&path, files)?;
        } else if kind.is_file()
            && path
                .extension()
                .is_some_and(|extension| extension == "journal")
        {
            files.push(path);
        }
    }
    Ok(())
}

fn relative_key(root: &Path, path: &Path) -> Result<String, crate::Error> {
    let relative = path.strip_prefix(root)?;
    let parts = relative
        .components()
        .map(|part| {
            part.as_os_str()
                .to_str()
                .ok_or("Journal path is not valid UTF-8")
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok(parts.join("/"))
}

fn parse_journal(
    path: &Path,
    file: &str,
    root: &Path,
    contents: &str,
    known_files: &BTreeSet<String>,
    entities: &mut Vec<ImportedEntity>,
) -> Result<(), crate::Error> {
    let mut transaction: Option<PendingTransaction> = None;
    for (index, line) in contents.lines().enumerate() {
        let line_number = index + 1;
        let trimmed = line.trim();
        if trimmed.is_empty() {
            flush_transaction(&mut transaction, entities);
            continue;
        }
        if trimmed.starts_with(';') || trimmed.starts_with('#') {
            if let Some(current) = transaction.as_mut() {
                let tags = parse_tags(trimmed.trim_start_matches([';', '#']).trim());
                current.0.tags.extend(tags);
            }
            continue;
        }

        let (body, comment) = split_comment(line);
        if line.starts_with([' ', '\t']) {
            let Some(current) = transaction.as_mut() else {
                // Indented directive continuations (for example commodity format)
                // do not affect the imported event-log facts.
                continue;
            };
            let (posting, amount, status) =
                parse_posting(body).map_err(|message| source_error(path, line_number, message))?;
            current.0.postings.push(ImportedPosting {
                account: posting,
                amount,
                status,
                tags: parse_tags(comment),
            });
            continue;
        }

        flush_transaction(&mut transaction, entities);
        if let Some((date, remainder)) = parse_transaction_header(body) {
            let mut parsed = parse_transaction_tail(remainder)
                .map_err(|message| source_error(path, line_number, message))?;
            parsed.file = file.to_owned();
            parsed.date = date;
            parsed.tags.extend(parse_tags(comment));
            transaction = Some(PendingTransaction(parsed));
            continue;
        }

        let (directive, value) = split_directive(body).ok_or_else(|| {
            source_error(
                path,
                line_number,
                format!("Unrecognized journal entry: {trimmed}"),
            )
        })?;
        let value = value.trim();
        if value.is_empty() && directive != "Y" {
            return Err(source_error(
                path,
                line_number,
                format!("Missing value for {directive} directive"),
            )
            .into());
        }
        match directive {
            "include" => {
                let include_path = unquote(value);
                let target_path = path
                    .parent()
                    .unwrap_or(root)
                    .join(&include_path)
                    .canonicalize()
                    .map_err(|error| {
                        source_error(
                            path,
                            line_number,
                            format!("Cannot resolve include {include_path:?}: {error}"),
                        )
                    })?;
                if !target_path.starts_with(root) {
                    return Err(source_error(
                        path,
                        line_number,
                        format!("Include escapes source folder: {include_path:?}"),
                    )
                    .into());
                }
                let key = relative_key(root, &target_path)?;
                if !known_files.contains(&key) {
                    return Err(source_error(
                        path,
                        line_number,
                        format!("Included file is not a discovered .journal: {key}"),
                    )
                    .into());
                }
                entities.push(ImportedEntity::Include {
                    file: file.into(),
                    path: key,
                });
            }
            "commodity" => entities.push(ImportedEntity::Commodity {
                file: file.into(),
                name: first_value(value),
            }),
            "account" => entities.push(ImportedEntity::Account {
                file: file.into(),
                name: unquote(value),
            }),
            "P" => {
                let (price_date, commodity, amount) = parse_price(value)
                    .map_err(|message| source_error(path, line_number, message))?;
                entities.push(ImportedEntity::Price {
                    file: file.into(),
                    date: price_date,
                    commodity,
                    value: amount,
                });
            }
            "alias" => {
                let (old, new) = value
                    .split_once('=')
                    .ok_or_else(|| source_error(path, line_number, "Alias must use OLD=NEW"))?;
                entities.push(ImportedEntity::Alias {
                    file: file.into(),
                    old: unquote(old.trim()),
                    new: unquote(new.trim()),
                });
            }
            "decimal-mark" => entities.push(ImportedEntity::DecimalMark {
                file: file.into(),
                value: first_value(value),
            }),
            "D" => entities.push(ImportedEntity::DefaultCommodity {
                file: file.into(),
                value: value.to_owned(),
            }),
            "payee" => entities.push(ImportedEntity::Payee {
                file: file.into(),
                name: unquote(value),
            }),
            "tag" => entities.push(ImportedEntity::TagDeclaration {
                file: file.into(),
                name: first_value(value),
            }),
            // These directives affect hledger validation or formatting but do not
            // add accounting facts needed by this JSON report API.
            "Y" | "comment" | "check" | "assert" => {}
            _ => {
                return Err(source_error(
                    path,
                    line_number,
                    format!("Unsupported directive: {directive}"),
                )
                .into());
            }
        }
    }
    flush_transaction(&mut transaction, entities);
    Ok(())
}

fn split_directive(line: &str) -> Option<(&str, &str)> {
    let end = line.find(char::is_whitespace).unwrap_or(line.len());
    (end > 0).then_some((&line[..end], line[end..].trim_start()))
}

fn split_comment(line: &str) -> (&str, &str) {
    line.split_once(';')
        .map_or((line, ""), |(body, comment)| (body, comment.trim()))
}

fn parse_transaction_header(line: &str) -> Option<(String, &str)> {
    let (date_text, remainder) = line.split_once(char::is_whitespace)?;
    let date = normalize_date(date_text)?;
    Some((date, remainder.trim()))
}

fn normalize_date(value: &str) -> Option<String> {
    for format in ["%Y-%m-%d", "%Y/%m/%d"] {
        if let Ok(date) = chrono::NaiveDate::parse_from_str(value, format) {
            return Some(date.format("%Y-%m-%d").to_string());
        }
    }
    None
}

fn parse_transaction_tail(remainder: &str) -> Result<ImportedTransaction, String> {
    let tokens = remainder.split_whitespace().collect::<Vec<_>>();
    let mut index = 0;
    let mut status = None;
    let mut code = None;
    let mut description = Vec::new();
    while index < tokens.len() {
        let token = tokens[index];
        if token == "*" || token == "!" {
            status = Some(token.to_owned());
        } else if token == "=" && index + 1 < tokens.len() {
            index += 1;
        } else if code.is_none() && token.starts_with('(') && token.ends_with(')') {
            code = Some(token[1..token.len() - 1].to_owned());
        } else {
            description.push(token);
        }
        index += 1;
    }
    Ok(ImportedTransaction {
        file: String::new(),
        date: String::new(),
        description: description.join(" "),
        status,
        code,
        tags: Vec::new(),
        postings: Vec::new(),
    })
}

fn parse_posting(line: &str) -> Result<(String, Option<String>, Option<String>), String> {
    let content = line.trim();
    if content.is_empty() {
        return Err("Empty posting".into());
    }
    let (status, content) = if let Some(rest) = content
        .strip_prefix('*')
        .or_else(|| content.strip_prefix('!'))
    {
        (Some(content[..1].to_owned()), rest.trim_start())
    } else {
        (None, content)
    };
    let split = content.char_indices().find_map(|(index, character)| {
        if character == '\t' {
            Some(index)
        } else if character == ' ' && content[index..].starts_with("  ") {
            Some(index)
        } else {
            None
        }
    });
    let (account, amount) = split.map_or((content, None), |index| {
        let account = content[..index].trim();
        let amount = content[index..].trim();
        (account, (!amount.is_empty()).then_some(amount))
    });
    if account.is_empty() {
        return Err("Posting is missing its account".into());
    }
    let amount = amount.map(|amount| {
        amount
            .split_once(" = ")
            .map_or(amount, |(value, _)| value)
            .trim()
            .to_owned()
    });
    Ok((unquote(account), amount, status))
}

fn parse_price(value: &str) -> Result<(String, String, String), String> {
    let (date_text, rest) = take_field(value).ok_or("Price directive is missing a date")?;
    let price_date =
        normalize_date(date_text).ok_or_else(|| format!("Invalid price date: {date_text}"))?;
    let (commodity, amount) = take_field(rest).ok_or("Price directive is missing a commodity")?;
    if amount.trim().is_empty() {
        return Err("Price directive is missing a value".into());
    }
    Ok((price_date, unquote(commodity), amount.trim().to_owned()))
}

fn take_field(value: &str) -> Option<(&str, &str)> {
    let value = value.trim_start();
    if value.is_empty() {
        return None;
    }
    if let Some(quoted) = value.strip_prefix('"') {
        let end = quoted.find('"')? + 2;
        Some((&value[..end], &value[end..]))
    } else {
        let end = value.find(char::is_whitespace).unwrap_or(value.len());
        Some((&value[..end], &value[end..]))
    }
}

fn parse_tags(comment: &str) -> Vec<ImportedTag> {
    comment
        .split(';')
        .flat_map(|segment| segment.split(','))
        .filter_map(|segment| {
            let (name, value) = segment.trim().split_once(':')?;
            let name = name.trim();
            if name.is_empty() || name.chars().any(char::is_whitespace) {
                return None;
            }
            let value = value.trim();
            Some(ImportedTag {
                name: name.to_owned(),
                value: (!value.is_empty()).then(|| value.to_owned()),
            })
        })
        .collect()
}

fn first_value(value: &str) -> String {
    let value = value.trim();
    if value.starts_with('"') {
        unquote(value)
    } else {
        value
            .split_whitespace()
            .next()
            .unwrap_or_default()
            .to_owned()
    }
}

fn unquote(value: &str) -> String {
    let value = value.trim();
    if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
        value[1..value.len() - 1].to_owned()
    } else {
        value.to_owned()
    }
}

fn flush_transaction(
    transaction: &mut Option<PendingTransaction>,
    entities: &mut Vec<ImportedEntity>,
) {
    if let Some(PendingTransaction(transaction)) = transaction.take() {
        entities.push(ImportedEntity::Transaction(transaction));
    }
}

fn source_error(path: &Path, line: usize, message: impl std::fmt::Display) -> String {
    format!("{}:{line}: {message}", path.display())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_journal_transactions_directives_tags_and_nested_includes() {
        let root =
            std::env::temp_dir().join(format!("journal-import-parser-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("nested")).unwrap();
        fs::write(root.join("main.journal"), "include nested/transactions.journal\ncommodity USD\naccount Assets:Cash\nP 2025-01-01   \"BTC Token\"   USD 50000\n2025-01-02 * (GROCERY) Groceries ; project: home\n  Expenses:Food  USD 10 ; reimbursable: yes\n  Assets:Cash  USD -10\n").unwrap();
        fs::write(
            root.join("nested/transactions.journal"),
            "2025/02/03 Payment\n  Assets:Bank\n  Income:Work  USD -1\n",
        )
        .unwrap();
        let entities = read_folder(&root).unwrap().entities;
        assert!(entities.iter().any(|entity| matches!(entity, ImportedEntity::Include { path, .. } if path == "nested/transactions.journal")));
        assert!(entities.iter().any(|entity| matches!(entity, ImportedEntity::Price { commodity, value, .. } if commodity == "BTC Token" && value == "USD 50000")));
        let ImportedEntity::Transaction(transaction) = entities.iter().find(|entity| matches!(entity, ImportedEntity::Transaction(txn) if txn.description == "Groceries")).unwrap() else { panic!("expected parsed transaction") };
        assert_eq!(transaction.status.as_deref(), Some("*"));
        assert_eq!(transaction.code.as_deref(), Some("GROCERY"));
        assert_eq!(transaction.tags[0].name, "project");
        assert_eq!(transaction.postings.len(), 2);
        assert_eq!(
            transaction.postings[0].tags[0].value.as_deref(),
            Some("yes")
        );
    }

    #[test]
    fn parser_supports_bare_postings_and_reports_unsupported_directives() {
        let parsed = parse_posting("Assets:Cash").unwrap();
        assert_eq!(parsed.0, "Assets:Cash");
        assert!(parsed.1.is_none());
        assert_eq!(parse_tags("comment without a tag").len(), 0);
        assert_eq!(parse_tags("flag:")[0].value, None);

        let root =
            std::env::temp_dir().join(format!("journal-import-error-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("bad.journal"), "unsupported-directive here\n").unwrap();
        let error = read_folder(&root).unwrap_err().to_string();
        assert!(error.contains("Unsupported directive"));
        let _ = fs::remove_dir_all(root);
    }
}
