// SPDX-License-Identifier: LicenseRef-VoiceKey-Author-Permission
// ZapIME-authored modifications: GPL-3.0-only. Upstream permission is separate.
// Adapted from J3n5en/voicekey @ 3ba017826eef7fac405198510b03859eb5b6c8c4
// Original file: crates/wtlocal/src/kernels.rs, original authors retain attribution.
// Change: stable AArch64 NEON fallback for unstable Rust 1.97 vdot intrinsic.
// See vendor/voicekey for pristine original and history.
//! 数值内核：int8 动态量化矩阵乘（aarch64 stable NEON / x86_64 AVX2 / 标量兜底）与 f32 点积
use rayon::prelude::*;

/// 按行动态量化到 int8（对称，每行一个 scale）
pub struct QAct {
    pub q: Vec<i8>,
    pub s: Vec<f32>,
    pub k: usize,
}

pub fn quant(x: &[f32], k: usize) -> QAct {
    let t = x.len() / k;
    let mut q = vec![0i8; t * k];
    let mut s = vec![0f32; t];
    q.par_chunks_mut(k)
        .zip(s.par_iter_mut())
        .zip(x.par_chunks(k))
        .for_each(|((q, s), x)| {
            let m = x.iter().fold(0f32, |a, v| a.max(v.abs()));
            let sc = if m > 0.0 { m / 127.0 } else { 1.0 };
            let inv = 1.0 / sc;
            for (q, v) in q.iter_mut().zip(x) {
                *q = (v * inv).round().clamp(-127.0, 127.0) as i8;
            }
            *s = sc;
        });
    QAct { q, s, k }
}

#[derive(Clone, Copy)]
struct Out(*mut f32);
unsafe impl Send for Out {}
unsafe impl Sync for Out {}
impl Out {
    unsafe fn write(&self, index: usize, value: f32) {
        unsafe { *self.0.add(index) = value };
    }
}

/// out[t×n] = (x · wᵀ) * scale + bias，w 为 [n, k] 行主序 int8
pub fn qgemm(x: &QAct, w: &[i8], n: usize, ws: f32, bias: Option<&[f32]>) -> Vec<f32> {
    let k = x.k;
    let t = x.s.len();
    debug_assert_eq!(w.len(), n * k);
    let mut out = vec![0f32; t * n];
    let o = Out(out.as_mut_ptr());
    const TILE: usize = 32;
    (0..n.div_ceil(TILE)).into_par_iter().for_each(|ti| {
        let n0 = ti * TILE;
        let n1 = (n0 + TILE).min(n);
        let mut j = n0;
        while j < n1 {
            let rows = (n1 - j).min(4);
            let mut i = 0;
            while i < t {
                let r2 = (t - i).min(2);
                let acc = dot(&x.q[i * k..], r2, &w[j * k..], rows, k);
                for (a, row) in acc.iter().enumerate().take(r2) {
                    for (b, value) in row.iter().enumerate().take(rows) {
                        let v = *value as f32 * x.s[i + a] * ws + bias.map_or(0.0, |bb| bb[j + b]);
                        unsafe { o.write((i + a) * n + j + b, v) };
                    }
                }
                i += r2;
            }
            j += rows;
        }
    });
    out
}

/// xr 行（≤2）× wr 行（≤4）的 int8 点积；不足的行用重复行指针补齐
#[inline]
fn dot(x: &[i8], xr: usize, w: &[i8], wr: usize, k: usize) -> [[i32; 4]; 2] {
    let xp = [x.as_ptr(), x[(xr - 1) * k..].as_ptr()];
    let wp: [*const i8; 4] = std::array::from_fn(|b| w[b.min(wr - 1) * k..].as_ptr());
    #[cfg(target_arch = "aarch64")]
    if k.is_multiple_of(16) {
        return unsafe { neon::dot2x4(xp, wp, k) };
    }
    #[cfg(target_arch = "x86_64")]
    if k.is_multiple_of(32) && avx2() {
        return unsafe { avx::dot2x4(xp, wp, k) };
    }
    let mut r = [[0i32; 4]; 2];
    for a in 0..xr {
        for b in 0..wr {
            r[a][b] = dot1(&x[a * k..(a + 1) * k], &w[b * k..(b + 1) * k]);
        }
    }
    r
}

fn dot1(a: &[i8], b: &[i8]) -> i32 {
    let mut acc = [0i32; 16];
    let (ca, cb) = (a.chunks_exact(16), b.chunks_exact(16));
    let (ra, rb) = (ca.remainder(), cb.remainder());
    for (x, y) in ca.zip(cb) {
        for i in 0..16 {
            acc[i] += x[i] as i32 * y[i] as i32;
        }
    }
    acc.iter().sum::<i32>()
        + ra.iter()
            .zip(rb)
            .map(|(&x, &y)| x as i32 * y as i32)
            .sum::<i32>()
}

#[cfg(target_arch = "x86_64")]
fn avx2() -> bool {
    static F: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *F.get_or_init(|| std::is_x86_feature_detected!("avx2"))
}

#[cfg(target_arch = "aarch64")]
mod neon {
    use std::arch::aarch64::*;

    pub unsafe fn dot2x4(x: [*const i8; 2], w: [*const i8; 4], k: usize) -> [[i32; 4]; 2] {
        let mut a = [vdupq_n_s32(0); 8];
        let mut i = 0;
        while i < k {
            let x0 = vld1q_s8(x[0].add(i));
            let x1 = vld1q_s8(x[1].add(i));
            for b in 0..4 {
                let wv = vld1q_s8(w[b].add(i));
                // Stable Rust 1.97 has no stabilized vdotq_s32 intrinsic.
                // Equivalent NEON signed 8x8->16 product / pairwise 16->32 sum
                // avoids unstable stdarch and runs without ARMv8.2 dotprod.
                let lo0 = vmull_s8(vget_low_s8(x0), vget_low_s8(wv));
                let hi0 = vmull_s8(vget_high_s8(x0), vget_high_s8(wv));
                let lo1 = vmull_s8(vget_low_s8(x1), vget_low_s8(wv));
                let hi1 = vmull_s8(vget_high_s8(x1), vget_high_s8(wv));
                a[b] = vaddq_s32(a[b], vaddq_s32(vpaddlq_s16(lo0), vpaddlq_s16(hi0)));
                a[4 + b] = vaddq_s32(a[4 + b], vaddq_s32(vpaddlq_s16(lo1), vpaddlq_s16(hi1)));
            }
            i += 16;
        }
        let mut r = [[0; 4]; 2];
        for b in 0..4 {
            r[0][b] = vaddvq_s32(a[b]);
            r[1][b] = vaddvq_s32(a[4 + b]);
        }
        r
    }
}

#[cfg(target_arch = "x86_64")]
mod avx {
    use std::arch::x86_64::*;

    #[target_feature(enable = "avx2")]
    unsafe fn hsum(v: __m256i) -> i32 {
        let s = _mm_add_epi32(_mm256_castsi256_si128(v), _mm256_extracti128_si256(v, 1));
        let s = _mm_add_epi32(s, _mm_shuffle_epi32(s, 0b01_00_11_10));
        let s = _mm_add_epi32(s, _mm_shuffle_epi32(s, 0b10_11_00_01));
        _mm_cvtsi128_si32(s)
    }

    /// Signed i8 widening and pairwise i16 products accumulated into i32.
    #[target_feature(enable = "avx2")]
    pub unsafe fn dot2x4(x: [*const i8; 2], w: [*const i8; 4], k: usize) -> [[i32; 4]; 2] {
        let mut a = [_mm256_setzero_si256(); 8];
        let mut i = 0;
        while i < k {
            let x0 = _mm256_loadu_si256(x[0].add(i) as *const __m256i);
            let x1 = _mm256_loadu_si256(x[1].add(i) as *const __m256i);
            // Signed widening avoids maddubs saturation/sign(-128) errors.
            let xl = [
                _mm256_cvtepi8_epi16(_mm256_castsi256_si128(x0)),
                _mm256_cvtepi8_epi16(_mm256_castsi256_si128(x1)),
            ];
            let xh = [
                _mm256_cvtepi8_epi16(_mm256_extracti128_si256(x0, 1)),
                _mm256_cvtepi8_epi16(_mm256_extracti128_si256(x1, 1)),
            ];
            for b in 0..4 {
                let wv = _mm256_loadu_si256(w[b].add(i) as *const __m256i);
                let lo = _mm256_cvtepi8_epi16(_mm256_castsi256_si128(wv));
                let hi = _mm256_cvtepi8_epi16(_mm256_extracti128_si256(wv, 1));
                let p0 =
                    _mm256_add_epi32(_mm256_madd_epi16(xl[0], lo), _mm256_madd_epi16(xh[0], hi));
                let p1 =
                    _mm256_add_epi32(_mm256_madd_epi16(xl[1], lo), _mm256_madd_epi16(xh[1], hi));
                a[b] = _mm256_add_epi32(a[b], p0);
                a[4 + b] = _mm256_add_epi32(a[4 + b], p1);
            }
            i += 32;
        }
        let mut r = [[0; 4]; 2];
        for b in 0..4 {
            r[0][b] = hsum(a[b]);
            r[1][b] = hsum(a[4 + b]);
        }
        r
    }
}

/// f32 点积（8 路累加，便于自动向量化）
#[inline]
pub fn dotf(a: &[f32], b: &[f32]) -> f32 {
    let mut acc = [0f32; 8];
    let (ca, cb) = (a.chunks_exact(8), b.chunks_exact(8));
    let (ra, rb) = (ca.remainder(), cb.remainder());
    for (x, y) in ca.zip(cb) {
        for i in 0..8 {
            acc[i] += x[i] * y[i];
        }
    }
    acc.iter().sum::<f32>() + ra.iter().zip(rb).map(|(x, y)| x * y).sum::<f32>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn architecture_dot_matches_i64_reference_for_extremes_and_tail_dimensions() {
        for k in [1, 15, 16, 17, 31, 32, 33, 64, 127, 512] {
            for xr in 1..=2 {
                for wr in 1..=4 {
                    let values = [i8::MIN, -127, -1, 0, 1, 126, i8::MAX];
                    let x: Vec<_> = (0..xr * k)
                        .map(|i| values[(i * 3 + i / 7) % values.len()])
                        .collect();
                    let w: Vec<_> = (0..wr * k)
                        .map(|i| values[(i * 5 + i / 3) % values.len()])
                        .collect();
                    let actual = dot(&x, xr, &w, wr, k);
                    for a in 0..xr {
                        for b in 0..wr {
                            let expected = (0..k)
                                .map(|n| i64::from(x[a * k + n]) * i64::from(w[b * k + n]))
                                .sum::<i64>();
                            assert_eq!(
                                i64::from(actual[a][b]),
                                expected,
                                "arch={} k={k} xr={xr} wr={wr} a={a} b={b}",
                                std::env::consts::ARCH
                            );
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn quantized_matrix_covers_tiles_and_odd_tails() {
        for k in [15, 16, 17, 32, 33, 64] {
            for n in [1, 3, 4, 31, 32, 33, 65] {
                let x: Vec<_> = (0..3 * k)
                    .map(|i| ((i * 17 % 255) as i32 - 127) as f32 / 4.0)
                    .collect();
                let w: Vec<_> = (0..n * k)
                    .map(|i| ((i * 31 % 256) as i32 - 128) as i8)
                    .collect();
                let q = quant(&x, k);
                let actual = qgemm(&q, &w, n, 0.125, None);
                for a in 0..3 {
                    for b in 0..n {
                        let sum = (0..k)
                            .map(|i| i64::from(q.q[a * k + i]) * i64::from(w[b * k + i]))
                            .sum::<i64>();
                        let expected = sum as f32 * q.s[a] * 0.125;
                        assert!(
                            (actual[a * n + b] - expected).abs() <= 1e-5 * expected.abs().max(1.0)
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn qgemm_matches_scalar() {
        let (t, k, n) = (5, 64, 11);
        let mut seed = 7u32;
        let mut r = || {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
            (seed >> 16) as i32 % 255 - 127
        };
        let x: Vec<f32> = (0..t * k).map(|_| r() as f32 * 0.01).collect();
        let w: Vec<i8> = (0..n * k).map(|_| r() as i8).collect();
        let b: Vec<f32> = (0..n).map(|i| i as f32).collect();
        let q = quant(&x, k);
        let out = qgemm(&q, &w, n, 0.5, Some(&b));
        for i in 0..t {
            for j in 0..n {
                let e =
                    dot1(&q.q[i * k..(i + 1) * k], &w[j * k..(j + 1) * k]) as f32 * q.s[i] * 0.5
                        + b[j];
                assert!(
                    (out[i * n + j] - e).abs() <= 1e-3 * e.abs().max(1.0),
                    "{i},{j}"
                );
            }
        }
    }
    #[test]
    fn architecture_dot_timing_diagnostic() {
        // Release-only CI diagnostic, not a device or end-to-end benchmark.
        // The reference is Rust code and may itself be auto-vectorized.
        let k = 512;
        let repeats = 100;
        let sample_count = 25;
        let x: Vec<i8> = (0..2 * k)
            .map(|i| ((i * 17 % 255) as i32 - 127) as i8)
            .collect();
        let w: Vec<i8> = (0..4 * k)
            .map(|i| ((i * 31 % 256) as i32 - 128) as i8)
            .collect();
        let mut optimized = Vec::new();
        let mut reference = Vec::new();
        for _ in 0..sample_count {
            let at = std::time::Instant::now();
            for _ in 0..repeats {
                std::hint::black_box(dot(
                    std::hint::black_box(&x),
                    2,
                    std::hint::black_box(&w),
                    4,
                    k,
                ));
            }
            optimized.push(at.elapsed().as_nanos() / repeats);
            let at = std::time::Instant::now();
            for _ in 0..repeats {
                for a in 0..2 {
                    for b in 0..4 {
                        std::hint::black_box(dot1(
                            std::hint::black_box(&x[a * k..(a + 1) * k]),
                            std::hint::black_box(&w[b * k..(b + 1) * k]),
                        ));
                    }
                }
            }
            reference.push(at.elapsed().as_nanos() / repeats);
        }
        optimized.sort_unstable();
        reference.sort_unstable();
        println!("{{\"scope\":\"synthetic 2x4 dot kernel only; Rust reference may auto-vectorize; no speed threshold or handset claim\",\"architecture\":\"{}\",\"debug_assertions\":{},\"k\":{},\"samples\":{},\"repeats_per_sample\":{},\"optimized_ns_p50\":{},\"optimized_ns_p95\":{},\"reference_ns_p50\":{},\"reference_ns_p95\":{}}}",std::env::consts::ARCH,cfg!(debug_assertions),k,sample_count,repeats,optimized[12],optimized[23],reference[12],reference[23]);
    }
}
