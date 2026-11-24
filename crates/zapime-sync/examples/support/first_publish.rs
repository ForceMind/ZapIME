// SPDX-License-Identifier: GPL-3.0-only
//! The test host provides authenticated HTTPS response files. Proof stays in this process.
use super::{
    client, database, guarded, initialize_material, material, open, prepare_store, private, read,
    snapshot, status, workspace, Content, Material, Path, Retention, Store, Uuid,
};
use serde_json::json;
use zapime_data::quarantine::MAX_PAGE_BYTES;

const CODE: &str = "a2restore";
const RESTORED: &str = "synthetic restored A2 snippet";

fn insert(store: &mut Store, id: Uuid, content: Content, deleted: bool) {
    let device = store.device_id().unwrap();
    store
        .edit(Uuid::new_v4(), id, 0, device, content, deleted)
        .expect("fixture record");
}

fn snippet(code: &str, text: &str) -> Content {
    Content::Snippet {
        code: code.into(),
        text: text.into(),
        tags: vec!["synthetic".into()],
    }
}

fn record_id(dir: &Path) -> Uuid {
    serde_json::from_slice(&read(&dir.join("record.json"), 128)).expect("fixture record ID")
}

fn initialize(dir: &Path, args: &[String]) {
    let [account, mode] = args else {
        panic!("restore fixture arguments");
    };
    let profile = Uuid::parse_str(account).expect("fixture account");
    let material = initialize_material(dir, profile);
    let id = Uuid::new_v4();
    private(&dir.join("record.json"), &serde_json::to_vec(&id).unwrap());
    let mut source = Store::open(&dir.join("original.sqlite3")).unwrap();
    let restored = if mode == "duplicate" {
        Content::Dictionary {
            scheme: "pinyin".into(),
            code: CODE.into(),
            text: RESTORED.into(),
            weight: 1,
        }
    } else {
        snippet(CODE, RESTORED)
    };
    insert(&mut source, id, restored.clone(), false);
    let archive = source.export_plaintext().expect("fixture archive");
    let a_path = database(dir, "a");
    let mut a = Store::open(&a_path).unwrap();
    assert_eq!(a.restore_unbound_plaintext(&archive).unwrap(), 1);
    assert!(a.records(true).unwrap() == source.records(true).unwrap());
    drop(a);
    workspace::prepare(&a_path, profile, &material).expect("bind restored fixture");
    let mut a = open(dir, "a");
    assert_eq!(a.pending_count().unwrap(), 0);
    status(&mut a, &material, "init-restored");

    prepare_store(&database(dir, "b"), &material);
    let mut b = open(dir, "b");
    match mode.as_str() {
        "positive" => {}
        "nonterminal" => {
            for code in ["a2seedone", "a2seedtwo"] {
                insert(
                    &mut b,
                    Uuid::new_v4(),
                    snippet(code, "synthetic remote seed"),
                    false,
                );
            }
        }
        "same-id" => insert(
            &mut b,
            id,
            snippet(CODE, "synthetic concurrent edit"),
            false,
        ),
        "duplicate" => insert(&mut b, Uuid::new_v4(), restored, false),
        "tombstone" => insert(&mut b, id, restored, true),
        _ => panic!("restore fixture mode"),
    }
}

fn confirm(dir: &Path, material: &Material, expectation: &str, pages: &[String]) {
    assert!((1..=3).contains(&pages.len()), "bounded fixture page count");
    let mut store = open(dir, "a");
    let before = snapshot(&database(dir, "a"));
    let id = record_id(dir);
    let record = store.get(id).unwrap().expect("restored snippet");
    assert_eq!(store.pending_count().unwrap(), 0);
    let mut proof = None;
    for page in pages {
        assert!(proof.is_none(), "stop after the terminal page");
        let after = client::pull_cursor(&mut store, material.profile, material).unwrap();
        let wire = read(Path::new(page), MAX_PAGE_BYTES);
        let result = client::apply_page_for_first_publish(
            &mut store,
            material.profile,
            material,
            after,
            &wire,
        );
        if matches!(expectation, "duplicate" | "tombstone") {
            let Err(client::Error::Page(error)) = result else {
                panic!("fixture conflicting page must not produce proof");
            };
            let cause = if expectation == "duplicate" {
                zapime_data::Error::Duplicate
            } else {
                zapime_data::Error::SyncLocalConflict
            };
            // Compare the precise business error without formatting payloads.
            assert!(matches!(*error.cause, zapime_sync::Error::Data(actual)
                if std::mem::discriminant(&actual) == std::mem::discriminant(&cause)));
            assert!(matches!(error.retention, Retention::Saved(_)));
            assert!(
                snapshot(&database(dir, "a")) == before,
                "no canonical side effects"
            );
            status(&mut store, material, expectation);
            return;
        }
        let (_, terminal) = result.expect("actual HTTPS page must apply");
        proof = terminal;
    }

    if expectation == "nonterminal" {
        assert!(
            proof.is_none(),
            "no confirmation authority until terminal page"
        );
        assert_eq!(store.pending_count().unwrap(), 0);
        status(&mut store, material, expectation);
        return;
    }

    assert_eq!(
        expectation, "confirm",
        "synthetic operator's explicit selection"
    );
    let proof = proof.expect("actual terminal proof");
    let selected =
        client::inspect_first_publish(&mut store, material.profile, material, &proof, id)
            .expect("restored snippet is eligible");
    assert!(matches!(
        client::confirm_first_publish(
            &mut store,
            material.profile,
            material,
            &proof,
            std::slice::from_ref(&selected),
            false
        ),
        Err(client::Error::FirstPublishConsent)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(
        client::confirm_first_publish(
            &mut store,
            material.profile,
            material,
            &proof,
            std::slice::from_ref(&selected),
            true
        )
        .unwrap(),
        1
    );
    assert!(
        store.get(id).unwrap() == Some(record),
        "restored snapshot unchanged"
    );
    status(&mut store, material, "confirmed");
}

fn verify(dir: &Path, material: &Material) {
    let id = record_id(dir);
    let mut a = open(dir, "a");
    let mut b = open(dir, "b");
    let local = a.records(true).unwrap();
    assert!(local.len() == 1 && local == b.records(true).unwrap());
    assert!(!local[0].deleted && local[0].id == id);
    assert!(
        matches!(&local[0].content, Content::Snippet { code, text, .. } if code == CODE && text == RESTORED)
    );
    for (store, cursor) in [(&mut a, 0), (&mut b, 1)] {
        assert_eq!(store.pending_count().unwrap(), 0);
        assert_eq!(
            client::pull_cursor(store, material.profile, material).unwrap(),
            cursor
        );
        assert!(client::stage_next(store, material.profile, material)
            .unwrap()
            .is_none());
    }
    println!(
        "{}",
        json!({"status":"PASS","command":"verify-restored","stores":2,"records_each":1,"pending_each":0})
    );
}

pub fn run(dir: &Path, command: &str, args: &[String]) -> bool {
    match command {
        "init-restored" => initialize(dir, args),
        "first-publish" => {
            let [descriptor, expectation, pages @ ..] = args else {
                panic!("first-publish fixture arguments");
            };
            let material = material(dir);
            assert!(
                guarded(&material, Path::new(descriptor)),
                "registered descriptor required"
            );
            confirm(dir, &material, expectation, pages);
        }
        "verify-restored" => {
            assert!(args.is_empty());
            verify(dir, &material(dir));
        }
        _ => return false,
    }
    true
}
