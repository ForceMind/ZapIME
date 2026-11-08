use std::{env, fs};
use zapime_data::Store;

fn main() {
    let alias = env::args().nth(1).unwrap();
    let path = std::path::Path::new(&alias);
    drop(Store::open(path).unwrap());
    println!("leaf_is_regular={}", fs::symlink_metadata(path).unwrap().is_file());
    match Store::open_sync_writer(path) {
        Ok(_) => println!("aliased_parent=accepted"),
        Err(error) => println!("aliased_parent_error={error:?}"),
    }
    let canonical = fs::canonicalize(path).unwrap();
    println!("canonical_parent_accepted={}", Store::open_sync_writer(&canonical).is_ok());
}
