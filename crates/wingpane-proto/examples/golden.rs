use std::{fmt::Write, fs, path::PathBuf};
use wingpane_proto::{encode_frame, golden_packets};

fn main() {
    let mut output = String::new();
    for (name, packet) in golden_packets() {
        let mut bytes = Vec::new();
        encode_frame(&packet, &mut bytes).expect("golden packet encodes");
        write!(&mut output, "{name} ").unwrap();
        for byte in bytes {
            write!(&mut output, "{byte:02x}").unwrap();
        }
        output.push('\n');
    }
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("golden/packets.txt");
    fs::write(&path, output).expect("write golden vectors");
    println!("{}", path.display());
}
