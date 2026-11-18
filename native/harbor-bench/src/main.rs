//! M0 benchmark: how many times faster than real time a firmware runs on this device, per clock,
//! with the JIT on or off. Pushed to a phone and run over `adb shell` (tools/m0-bench.sh), or on
//! the build machine.
//!
//!   harbor-bench FIRMWARE.fwsc... [--ms 8000] [--mhz 240,200,160,120] [--no-jit] [--json]
//!     [--profile] [--baseline-dual] [--pin none|prime|big]
//!
//! As fm1-live's --bench: three keys go down at 0.8-1 s so most firmwares make sound, the first
//! second is warm-up, and the figure is emulated time over wall time for the rest.

use std::time::Instant;

use fm1_soc::Action;
use harbor_engine::machine::Machine;

#[derive(Clone, Copy, PartialEq, Eq)]
enum PinMode {
    None,
    Prime,
    Big,
}

#[cfg(target_os = "android")]
fn apply_pin(mode: PinMode) -> String {
    if mode == PinMode::None {
        return "unpinned".into();
    }
    let mut freqs: Vec<(usize, u64)> = Vec::new();
    for cpu in 0..32usize {
        let path = format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq");
        if let Ok(s) = std::fs::read_to_string(&path) {
            if let Ok(f) = s.trim().parse::<u64>() {
                freqs.push((cpu, f));
            }
        }
    }
    if freqs.is_empty() {
        return "pin: no cpufreq sysfs".into();
    }
    let max_f = freqs.iter().map(|&(_, f)| f).max().unwrap_or(0);
    let thresh = match mode {
        PinMode::Prime => max_f,
        PinMode::Big => max_f * 75 / 100,
        PinMode::None => 0,
    };
    let chosen: Vec<usize> = freqs.iter().filter(|&&(_, f)| f >= thresh).map(|&(c, _)| c).collect();
    if chosen.is_empty() {
        return "pin: empty set".into();
    }
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for &c in &chosen {
            libc::CPU_SET(c, &mut set);
        }
        let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
        if rc != 0 {
            return format!("pin failed: errno {}", std::io::Error::last_os_error());
        }
    }
    format!("cpus {:?} (>= {} MHz)", chosen, thresh / 1000)
}

#[cfg(not(target_os = "android"))]
fn apply_pin(mode: PinMode) -> String {
    match mode {
        PinMode::None => "unpinned".into(),
        PinMode::Prime | PinMode::Big => "pin ignored on non-Android host".into(),
    }
}

fn main() {
    let mut files = Vec::new();
    let (mut ms, mut clocks, mut jit, mut json, mut profile, mut pin) =
        (8000u64, vec![240u32, 200, 160, 120], true, false, false, PinMode::None);
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--ms" => ms = args.next().and_then(|v| v.parse().ok()).expect("--ms N"),
            "--mhz" => clocks = args.next().expect("--mhz LIST").split(',').map(|c| c.parse().expect("MHz")).collect(),
            "--no-jit" => jit = false,
            "--json" => json = true,
            "--profile" => profile = true,
            "--pin" => {
                pin = match args.next().expect("--pin none|prime|big").as_str() {
                    "prime" => PinMode::Prime,
                    "big" => PinMode::Big,
                    _ => PinMode::None,
                };
            }
            _ => files.push(a),
        }
    }
    if files.is_empty() {
        eprintln!("usage: harbor-bench FIRMWARE.fwsc... [--ms 8000] [--mhz 240,200,160,120] [--no-jit] [--json] [--profile] [--pin none|prime|big]");
        std::process::exit(2);
    }
    let pin_info = apply_pin(pin);
    if !json {
        if profile {
            println!("# pin={pin_info}");
        }
        println!("{:<28} {:>5} {:>4} {:>9} {:>8}", "firmware", "MHz", "JIT", "x real", "frames");
    }
    for path in &files {
        let pkg = match std::fs::read(path) {
            Ok(p) => p,
            Err(e) => { eprintln!("{path}: {e}"); continue; }
        };
        let name = std::path::Path::new(path).file_stem().map_or(path.clone(), |s| s.to_string_lossy().into_owned());
        for &mhz in &clocks {
            match bench(&pkg, mhz, jit, ms, profile) {
                Ok(res) => {
                    if json {
                        println!("{{\"firmware\":\"{name}\",\"mhz\":{mhz},\"jit\":{},\"realtime\":{:.3},\"audioFrames\":{}}}", res.jit_active, res.x_real, res.frames);
                    } else {
                        println!("{name:<28} {mhz:>5} {:>4} {:>8.2}x {:>8}", if res.jit_active { "on" } else { "off" }, res.x_real, res.frames);
                        if profile {
                            println!("  cpu0: {} insns, {} decodes, pc={:#010x} (xip={}, ram={}, ovl={}, sram2={}, other={})",
                                res.cpu0_insns, res.cpu0_decodes, res.cpu0_pc,
                                res.pc_hist[0], res.pc_hist[1], res.pc_hist[2], res.pc_hist[3], res.pc_hist[4]);
                            println!("  cpu0 jit: {}", res.cpu0_jit);
                            if let Some(c1) = &res.cpu1_jit {
                                println!("  cpu1: {} insns, {} decodes, pc={:#010x}, jit: {}",
                                    res.cpu1_insns, res.cpu1_decodes, res.cpu1_pc, c1);
                            } else {
                                println!("  cpu1: not released");
                            }
                            if !res.top_slow.is_empty() {
                                println!("  top slow_hist: {}", res.top_slow);
                            }
                            println!("  skipped_cycles={}, loop_skip=(skipped={}, hits={}), lcd_frames={}, unknown_mmio={}, resets={}",
                                res.skipped, res.loop_skipped, res.loop_hits, res.lcd_frames, res.unknown_mmio, res.resets);
                            if !res.console.is_empty() {
                                println!("  console: {:?}", res.console);
                            }
                        }
                    }
                }
                Err(e) => eprintln!("{name} at {mhz} MHz: {e}"),
            }
        }
    }
}

struct BenchResult {
    x_real: f64,
    frames: usize,
    jit_active: bool,
    cpu0_insns: u64,
    cpu0_decodes: u64,
    cpu0_pc: u32,
    cpu0_jit: String,
    cpu1_insns: u64,
    cpu1_decodes: u64,
    cpu1_pc: u32,
    cpu1_jit: Option<String>,
    pc_hist: [u64; 5],
    top_slow: String,
    skipped: u64,
    loop_skipped: u64,
    loop_hits: u64,
    lcd_frames: u64,
    unknown_mmio: usize,
    resets: u32,
    console: String,
}

/// (steady times real time, audio frames made, whether the JIT was on, plus profile breakdown)
fn bench(pkg: &[u8], mhz: u32, jit: bool, ms: u64, profile: bool) -> Result<BenchResult, String> {
    let mut m = Machine::boot(pkg, &[], mhz, None, jit)?;
    if profile {
        m.set_profile(true);
    }
    for (at, id) in [(800, 21), (900, 25), (1000, 28)] {
        let c = m.soc.periph.cycle_of_ms(at);
        m.soc.periph.script.push_back((c, Action::Key { id, down: true }));
    }
    let warmup = 1000.min(ms.saturating_sub(1));
    let mut steady = None;
    let mut frames = 0usize;
    let mut pc_hist = [0u64; 5]; // xip, ram, ovl, sram2, other
    while m.soc.periph.ms() < ms {
        if steady.is_none() && m.soc.periph.ms() >= warmup {
            steady = Some((Instant::now(), m.soc.periph.ms()));
        }
        m.run(20_000);
        if profile {
            let pc = m.cpu.pc;
            let bucket = if (0x0200_0000..0x0400_0000).contains(&pc) { 0 }
            else if (0x01c0_0000..0x01d0_0000).contains(&pc) { 1 }
            else if (0x0400_0000..0x0410_0000).contains(&pc) { 2 }
            else if (0x01f0_0000..0x01f4_0000).contains(&pc) { 3 }
            else { 4 };
            pc_hist[bucket] += 1;
        }
        if m.halted {
            return Err(format!("the CPU halted: {}", m.fault));
        }
        frames += m.soc.periph.audio.len() / 2;
        m.soc.periph.audio.clear();
    }
    let (t, start) = steady.ok_or("too short to measure")?;
    let x_real = (m.soc.periph.ms() - start) as f64 / 1000.0 / t.elapsed().as_secs_f64();
    let mut slow: Vec<(&&'static str, &u64)> = m.cpu.slow_hist.iter().collect();
    slow.sort_by(|a, b| b.1.cmp(a.1));
    let top_slow = slow.into_iter().take(8).map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(", ");
    Ok(BenchResult {
        x_real,
        frames,
        jit_active: m.jit(),
        cpu0_insns: m.cpu0_insns,
        cpu0_decodes: m.cpu.decodes,
        cpu0_pc: m.cpu.pc,
        cpu0_jit: m.cpu.jit_profile_json(),
        cpu1_insns: m.cpu1_insns,
        cpu1_decodes: m.cpu1.as_ref().map_or(0, |c| c.decodes),
        cpu1_pc: m.cpu1.as_ref().map_or(0, |c| c.pc),
        cpu1_jit: m.cpu1.as_ref().map(|c| c.jit_profile_json()),
        pc_hist,
        top_slow,
        skipped: m.soc.periph.skipped,
        loop_skipped: m.soc.loop_skip.skipped,
        loop_hits: m.soc.loop_skip.hits,
        lcd_frames: m.soc.lcd.frames,
        unknown_mmio: m.soc.unknown_accesses().len(),
        resets: m.firmware_resets,
        console: m.soc.console_string(),
    })
}
