//! DeepSeekHashV1: SHA3-256 variant that skips Keccak-f[1600] round 0.
//! Used by chat.deepseek.com to gate `POST /api/v0/chat/completion`.
//!
//! Wire format: prefix = `{salt}_{expire_at}_`, find `nonce` in
//! `[0, difficulty)` such that `hash(prefix + nonce) == challenge`.

const ROUND_CONSTANTS: [u64; 24] = [
    0x0000000000000001,
    0x0000000000008082,
    0x800000000000808a,
    0x8000000080008000,
    0x000000000000808b,
    0x0000000080000001,
    0x8000000080008081,
    0x8000000000008009,
    0x000000000000008a,
    0x0000000000000088,
    0x0000000080008009,
    0x000000008000000a,
    0x000000008000808b,
    0x800000000000008b,
    0x8000000000008089,
    0x8000000000008003,
    0x8000000000008002,
    0x8000000000000080,
    0x000000000000800a,
    0x800000008000000a,
    0x8000000080008081,
    0x8000000000008080,
    0x0000000080000001,
    0x8000000080008008,
];

#[inline]
fn rotl(v: u64, k: u32) -> u64 {
    v.rotate_left(k)
}

/// Keccak-f[1600] over rounds 1..=23 (round 0 is skipped by design).
fn keccak_f23(a: &mut [u64; 25]) {
    for r in 1..24 {
        let c0 = a[0] ^ a[5] ^ a[10] ^ a[15] ^ a[20];
        let c1 = a[1] ^ a[6] ^ a[11] ^ a[16] ^ a[21];
        let c2 = a[2] ^ a[7] ^ a[12] ^ a[17] ^ a[22];
        let c3 = a[3] ^ a[8] ^ a[13] ^ a[18] ^ a[23];
        let c4 = a[4] ^ a[9] ^ a[14] ^ a[19] ^ a[24];

        let d0 = c4 ^ rotl(c1, 1);
        let d1 = c0 ^ rotl(c2, 1);
        let d2 = c1 ^ rotl(c3, 1);
        let d3 = c2 ^ rotl(c4, 1);
        let d4 = c3 ^ rotl(c0, 1);

        a[0] ^= d0;
        a[5] ^= d0;
        a[10] ^= d0;
        a[15] ^= d0;
        a[20] ^= d0;
        a[1] ^= d1;
        a[6] ^= d1;
        a[11] ^= d1;
        a[16] ^= d1;
        a[21] ^= d1;
        a[2] ^= d2;
        a[7] ^= d2;
        a[12] ^= d2;
        a[17] ^= d2;
        a[22] ^= d2;
        a[3] ^= d3;
        a[8] ^= d3;
        a[13] ^= d3;
        a[18] ^= d3;
        a[23] ^= d3;
        a[4] ^= d4;
        a[9] ^= d4;
        a[14] ^= d4;
        a[19] ^= d4;
        a[24] ^= d4;

        let b0 = a[0];
        let b10 = rotl(a[1], 1);
        let b20 = rotl(a[2], 62);
        let b5 = rotl(a[3], 28);
        let b15 = rotl(a[4], 27);
        let b16 = rotl(a[5], 36);
        let b1 = rotl(a[6], 44);
        let b11 = rotl(a[7], 6);
        let b21 = rotl(a[8], 55);
        let b6 = rotl(a[9], 20);
        let b7 = rotl(a[10], 3);
        let b17 = rotl(a[11], 10);
        let b2 = rotl(a[12], 43);
        let b12 = rotl(a[13], 25);
        let b22 = rotl(a[14], 39);
        let b23 = rotl(a[15], 41);
        let b8 = rotl(a[16], 45);
        let b18 = rotl(a[17], 15);
        let b3 = rotl(a[18], 21);
        let b13 = rotl(a[19], 8);
        let b14 = rotl(a[20], 18);
        let b24 = rotl(a[21], 2);
        let b9 = rotl(a[22], 61);
        let b19 = rotl(a[23], 56);
        let b4 = rotl(a[24], 14);

        a[0] = b0 ^ (!b1 & b2);
        a[1] = b1 ^ (!b2 & b3);
        a[2] = b2 ^ (!b3 & b4);
        a[3] = b3 ^ (!b4 & b0);
        a[4] = b4 ^ (!b0 & b1);
        a[5] = b5 ^ (!b6 & b7);
        a[6] = b6 ^ (!b7 & b8);
        a[7] = b7 ^ (!b8 & b9);
        a[8] = b8 ^ (!b9 & b5);
        a[9] = b9 ^ (!b5 & b6);
        a[10] = b10 ^ (!b11 & b12);
        a[11] = b11 ^ (!b12 & b13);
        a[12] = b12 ^ (!b13 & b14);
        a[13] = b13 ^ (!b14 & b10);
        a[14] = b14 ^ (!b10 & b11);
        a[15] = b15 ^ (!b16 & b17);
        a[16] = b16 ^ (!b17 & b18);
        a[17] = b17 ^ (!b18 & b19);
        a[18] = b18 ^ (!b19 & b15);
        a[19] = b19 ^ (!b15 & b16);
        a[20] = b20 ^ (!b21 & b22);
        a[21] = b21 ^ (!b22 & b23);
        a[22] = b22 ^ (!b23 & b24);
        a[23] = b23 ^ (!b24 & b20);
        a[24] = b24 ^ (!b20 & b21);

        a[0] ^= ROUND_CONSTANTS[r];
    }
}

pub fn deepseek_hash_v1(data: &[u8]) -> [u8; 32] {
    const RATE: usize = 136;
    let mut state = [0u64; 25];
    let mut offset = 0usize;

    while offset + RATE <= data.len() {
        for i in 0..RATE / 8 {
            let mut lane = [0u8; 8];
            lane.copy_from_slice(&data[offset + i * 8..offset + i * 8 + 8]);
            state[i] ^= u64::from_le_bytes(lane);
        }
        keccak_f23(&mut state);
        offset += RATE;
    }

    let mut last = [0u8; RATE];
    let remaining = data.len() - offset;
    last[..remaining].copy_from_slice(&data[offset..]);
    last[remaining] = 0x06;
    last[RATE - 1] |= 0x80;

    for i in 0..RATE / 8 {
        let mut lane = [0u8; 8];
        lane.copy_from_slice(&last[i * 8..i * 8 + 8]);
        state[i] ^= u64::from_le_bytes(lane);
    }
    keccak_f23(&mut state);

    let mut digest = [0u8; 32];
    for i in 0..4 {
        digest[i * 8..i * 8 + 8].copy_from_slice(&state[i].to_le_bytes());
    }
    digest
}

fn hex_nibble(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

fn hex_to_bytes(text: &str) -> Option<Vec<u8>> {
    let bytes = text.as_bytes();
    if bytes.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(bytes.len() / 2);
    for pair in bytes.chunks(2) {
        out.push((hex_nibble(pair[0])? << 4) | hex_nibble(pair[1])?);
    }
    Some(out)
}

fn write_decimal(buf: &mut Vec<u8>, mut value: u64) {
    let start = buf.len();
    if value == 0 {
        buf.push(b'0');
        return;
    }
    while value > 0 {
        buf.push(b'0' + (value % 10) as u8);
        value /= 10;
    }
    buf[start..].reverse();
}

/// Finds the nonce whose hash equals `challenge` (hex). Returns `None` if the
/// challenge is malformed or the nonce is outside `[0, difficulty)`.
pub fn solve(challenge: &str, salt: &str, expire_at: i64, difficulty: u64) -> Option<u64> {
    let target = hex_to_bytes(challenge)?;
    if target.len() != 32 {
        return None;
    }
    let mut target_arr = [0u8; 32];
    target_arr.copy_from_slice(&target);

    let mut buffer = Vec::with_capacity(96);
    buffer.extend_from_slice(salt.as_bytes());
    buffer.push(b'_');
    buffer.extend_from_slice(expire_at.to_string().as_bytes());
    buffer.push(b'_');
    let prefix_len = buffer.len();

    // A hostile/broken challenge must not spin forever on a phone.
    for nonce in 0..difficulty.min(10_000_000) {
        buffer.truncate(prefix_len);
        write_decimal(&mut buffer, nonce);
        if deepseek_hash_v1(&buffer) == target_arr {
            return Some(nonce);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    #[test]
    fn hash_matches_official_wasm_vectors() {
        for (input, expected) in [
            ("", "e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3"),
            ("testsalt_1700000000_42", "d4a2ea58c89e40887c933484868380c6f803eaa8dc53a3b9df8e431b921a4f09"),
            ("testsalt_1700000000_100000", "abea2f35796b65486e9be1b36f7878c66cab021e96faa473fdf4decd31f9ba30"),
            ("abc123salt_1700000000_12345", "74b3b7452745b70e85eb32ee7f0a9ec0381d42dd5137b695da915e104fc390e1"),
        ] {
            assert_eq!(hex(&deepseek_hash_v1(input.as_bytes())), expected, "input {input:?}");
        }
    }

    #[test]
    fn solves_known_challenges() {
        for (salt, expire, answer, difficulty) in [
            ("testsalt", 1_700_000_000i64, 42u64, 1000u64),
            ("testsalt", 1_700_000_000, 500, 2000),
            ("abc123salt", 1_700_000_000, 12345, 20000),
        ] {
            let prefix = format!("{salt}_{expire}_");
            let target = hex(&deepseek_hash_v1(format!("{prefix}{answer}").as_bytes()));
            assert_eq!(solve(&target, salt, expire, difficulty), Some(answer));
        }
    }

    #[test]
    fn rejects_malformed_challenge() {
        assert_eq!(solve("zzzz", "salt", 1, 10), None);
        assert_eq!(solve("00", "salt", 1, 10), None);
    }
}
