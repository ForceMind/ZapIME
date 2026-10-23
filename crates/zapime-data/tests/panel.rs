// SPDX-License-Identifier: GPL-3.0-only
use rusqlite::params;
use uuid::Uuid;
use zapime_data::{Content, Record, Store};

#[test]
fn large_store_queries_return_only_indexed_summary_pages_from_a_readonly_connection() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("profile.sqlite3");
    let _store = Store::open(&path).unwrap();
    let mut raw = rusqlite::Connection::open(&path).unwrap();
    let tx = raw.transaction().unwrap();
    let device = Uuid::new_v4();
    let body = "中文测试".repeat(200);
    for n in 0..6000 {
        let record = Record {
            id: Uuid::new_v4(),
            revision: 1,
            device_id: device,
            deleted: false,
            content: Content::Favorite {
                title: format!("资料{n:05}"),
                text: body.clone(),
            },
        };
        tx.execute(
            "INSERT INTO records VALUES(?1,1,?2)",
            params![
                record.id.to_string(),
                serde_json::to_string(&record).unwrap()
            ],
        )
        .unwrap();
    }
    tx.commit().unwrap();
    let mut reader = Store::open_panel_reader(&path).unwrap();
    let page = reader.query_panel("favorite", "", 5980, 40, 10).unwrap();
    assert_eq!(page.total, 6000);
    assert_eq!(page.items.len(), 20);
    assert_eq!(page.items[0].label, "资料05980");
    assert_eq!(page.items[0].preview.chars().count(), 96);
    assert_eq!(page.next_offset, None);
    let wire = serde_json::to_vec(&page).unwrap();
    assert!(wire.len() < 16000);
    assert!(reader
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            device,
            Content::Favorite {
                title: "read only".into(),
                text: "must fail".into()
            },
            false
        )
        .is_err());
    assert_eq!(
        reader
            .query_panel("all", "", u64::MAX, 20, 10)
            .unwrap()
            .offset,
        6000
    );
}

#[test]
fn sqlite_search_preserves_unicode_literals_tags_expiry_and_max_u64() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
    let device = store.device_id().unwrap();
    for content in [
        Content::Favorite {
            title: "ÉCOLE".into(),
            text: "literal 100%_ and 中文".into(),
        },
        Content::Snippet {
            code: "wx".into(),
            text: "test".into(),
            tags: vec!["Ω".into()],
        },
        Content::Clipboard {
            text: "future".into(),
            expires_at_ms: u64::MAX,
            pinned: true,
        },
        Content::Clipboard {
            text: "expired".into(),
            expires_at_ms: 10,
            pinned: true,
        },
    ] {
        store
            .edit(Uuid::new_v4(), Uuid::new_v4(), 0, device, content, false)
            .unwrap();
    }
    for query in ["école", "100%_", "中文", "ω", "WX"] {
        assert_eq!(store.query_panel("all", query, 0, 20, 10).unwrap().total, 1);
    }
    assert_eq!(
        store
            .query_panel("all", "' OR 1=1 --", 0, 20, 10)
            .unwrap()
            .total,
        0
    );
    let page = store.query_panel("clipboard", "", 0, 20, 10).unwrap();
    assert_eq!(page.total, 1);
    assert_eq!(page.items[0].expires_at_ms, u64::MAX);
    assert!(page.items[0].pinned);
    for (kind, query, limit) in [
        ("bad", "", 20),
        ("all", "", 0),
        ("all", "", 41),
        ("all", &"a".repeat(513), 20),
    ] {
        assert!(store.query_panel(kind, query, 0, limit, 10).is_err());
    }
}

#[test]
fn readonly_open_never_creates_or_migrates_a_profile() {
    let dir = tempfile::tempdir().unwrap();
    let absent = dir.path().join("absent.sqlite3");
    assert!(Store::open_panel_reader(&absent).is_err());
    assert!(!absent.exists());
    let path = dir.path().join("old.sqlite3");
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute_batch("PRAGMA user_version=1;").unwrap();
    assert!(Store::open_panel_reader(&path).is_err());
    assert_eq!(
        db.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))
            .unwrap(),
        1
    );
}
