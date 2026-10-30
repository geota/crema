//! Regenerates `tests/vectors/v4.json` from the fixed seeds (§3.7), and the
//! fuzz seed corpus.
//!
//! ```text
//! cargo run -p de1-relay --features test-seeds --example gen_vectors            # rewrite
//! cargo run -p de1-relay --features test-seeds --example gen_vectors -- --check # CI: fail on any difference
//! cargo run -p de1-relay --features test-seeds --example gen_vectors -- --corpus # rewrite fuzz/corpus
//! ```

use std::{fs, path::Path, process::ExitCode};

fn main() -> ExitCode {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let path = dir.join("tests/vectors/v4.json");
    let fresh = de1_relay::test_vectors::generate();
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "--corpus") {
        for (target, name, bytes) in de1_relay::fuzzing::seed_corpus() {
            let target_dir = dir.join("fuzz/corpus").join(target);
            if let Err(e) = fs::create_dir_all(&target_dir)
                .and_then(|()| fs::write(target_dir.join(name), bytes))
            {
                eprintln!("writing corpus: {e}");
                return ExitCode::FAILURE;
            }
        }
        println!("wrote fuzz/corpus");
        return ExitCode::SUCCESS;
    }
    if args.iter().any(|a| a == "--check") {
        return match fs::read_to_string(&path) {
            Ok(current) if current == fresh => {
                println!("tests/vectors/v4.json is up to date");
                ExitCode::SUCCESS
            }
            Ok(_) => {
                eprintln!("tests/vectors/v4.json differs from the regenerated vectors");
                ExitCode::FAILURE
            }
            Err(e) => {
                eprintln!("reading {}: {e}", path.display());
                ExitCode::FAILURE
            }
        };
    }
    match fs::write(&path, fresh) {
        Ok(()) => {
            println!("wrote {}", path.display());
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("writing {}: {e}", path.display());
            ExitCode::FAILURE
        }
    }
}
