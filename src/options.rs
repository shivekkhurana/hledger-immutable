//! Public options shared by the CLI and embedding applications.

#[derive(Debug, Clone, Default)]
pub struct FilterOptions {
    pub files: Vec<String>,
    pub accounts: Vec<String>,
    pub not_accounts: Vec<String>,
    pub query: Vec<String>,
    pub tags: Vec<String>,
    pub begin: Option<String>,
    pub end: Option<String>,
    pub date_period: Option<String>,
}

#[derive(Debug, Clone, Default)]
pub struct ListOptions {
    pub filters: FilterOptions,
    pub depth: Option<usize>,
}

#[derive(Debug, Clone, Copy)]
pub enum Period {
    Daily,
    Weekly,
    Monthly,
    Quarterly,
    Yearly,
}

#[derive(Debug, Clone, Default)]
pub struct ReportOptions {
    pub filters: FilterOptions,
    pub interval: Option<Period>,
    pub depth: Option<usize>,
    pub exchange: Option<String>,
    pub cost: bool,
    pub market: bool,
    pub empty: bool,
    pub historical: bool,
    pub today: Option<String>,
    pub flat: bool,
    pub layout: Option<String>,
    pub no_total: bool,
    pub transpose: bool,
    pub commodity_style: Option<String>,
}
