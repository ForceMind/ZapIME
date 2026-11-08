// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_data::{Content, Store};

#[test]
fn restored_records_can_have_no_pending_operations() {
    let directory = tempfile::tempdir().unwrap();
    let mut original = Store::open(&directory.path().join("original.sqlite3")).unwrap();
    original
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            Uuid::new_v4(),
            Content::Favorite {
                title: "Synthetic restore".into(),
                text: "Public synthetic inventory fixture".into(),
            },
            false,
        )
        .unwrap();
    let archive = original.export_plaintext().unwrap();
    let mut restored = Store::open(&directory.path().join("restored.sqlite3")).unwrap();
    assert_eq!(restored.restore_unbound_plaintext(&archive).unwrap(), 1);
    assert_eq!(restored.records(true).unwrap().len(), 1);
    assert_eq!(restored.pending_count().unwrap(), 0);
    assert_eq!(restored.export_plaintext().unwrap(), archive);
}
