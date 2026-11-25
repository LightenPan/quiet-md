//! Name order for the sidebar tree: numbers first, Latin case-insensitively,
//! and Han characters by their plain (toneless) pinyin.

use pinyin::ToPinyin;

/// The sort key for a page or folder. The last two fields make equal names
/// deterministic without changing the visible order.
pub(crate) fn key(title: &str, id: &str) -> (u8, String, String, String) {
    let title = title.trim();
    let group = if title.chars().next().is_some_and(char::is_numeric) { 0 } else { 1 };
    let mut name = String::with_capacity(title.len());
    for ch in title.chars() {
        match ch.to_pinyin() {
            Some(pinyin) => name.push_str(pinyin.plain()),
            None => name.extend(ch.to_lowercase()),
        }
    }
    (group, name, title.to_string(), id.to_string())
}

#[cfg(test)]
mod tests {
    use super::key;

    fn ordered(names: &[&'static str]) -> Vec<&'static str> {
        let mut names: Vec<_> = names.iter().enumerate().map(|(i, name)| (*name, i.to_string())).collect();
        names.sort_by_cached_key(|(name, id)| key(name, id));
        names.into_iter().map(|(name, _)| name).collect()
    }

    #[test]
    fn numbers_letters_and_pinyin() {
        assert_eq!(
            ordered(&["微信收藏", "My Notes", "05.自己积累", "服务信息", "魔改", "My Journals"]),
            ["05.自己积累", "服务信息", "魔改", "My Journals", "My Notes", "微信收藏"]
        );
    }

    #[test]
    fn latin_is_case_insensitive() {
        assert_eq!(ordered(&["beta", "Alpha", "alpha"]), ["Alpha", "alpha", "beta"]);
    }
}
