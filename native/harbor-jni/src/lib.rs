//! libharbor.so: harbor-engine for the app, through `com.markwhite.harbor.engine.NativeEngine`.
//!
//! One engine per process (the app's PlaybackService owns its lifetime), an AAudio output stream
//! feeding from the engine's ring, MIDI parsers per source, and the `.fm1save` codec. Calls are
//! coarse; nothing here is called per sample from Kotlin.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use jni::objects::{JByteArray, JByteBuffer, JClass, JDoubleArray, JFloatArray, JIntArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jlongArray, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use harbor_engine::engine::{AudioOut, Boot, Cmd, Engine, RunState, WorkReport, LCD_PIXELS};
use harbor_engine::midi::{
    panic_messages, ControlTarget, KnobMode, MappingRule, MidiParser, PanelAction, Sensitivity,
    Trigger, TriggerKind,
};
use harbor_engine::recorder::Recorder;
use harbor_engine::savefile;
use harbor_engine::Board;

mod audio;
mod hint;

struct Host {
    engine: Engine,
    out: Arc<Mutex<AudioOut>>,
    audio: audio::Output,
    parsers: HashMap<i32, MidiParser>,
    recorder: Option<Recorder>,
    pot: u16,
    enc_clicks: [i32; 7],
    learn_source: i32,
    learned: Option<[i32; 4]>,
    requested_lead: usize,
}

/// Ensure the engine's ring lead is at least 2x the AAudio burst in 44.1 kHz frames so a single
/// output callback never drains more frames than the ring holds.
fn update_lead_for_audio(h: &Host) {
    let info = h.audio.info();
    let min_lead = if info.sample_rate > 0 && info.frames_per_burst > 0 {
        let burst_44k = (info.frames_per_burst as usize * 44_100) / (info.sample_rate as usize);
        h.requested_lead.max(burst_44k * 2)
    } else {
        h.requested_lead
    };
    h.engine.set_lead(min_lead);
}

static HOST: Mutex<Option<Host>> = Mutex::new(None);

fn host() -> MutexGuard<'static, Option<Host>> {
    HOST.lock().unwrap_or_else(|e| e.into_inner())
}

/// CLOCK_MONOTONIC in nanoseconds, the clock of `System.nanoTime` and of MIDI timestamps.
pub(crate) fn now_ns() -> u64 {
    #[cfg(unix)]
    {
        let mut ts = libc_timespec::new();
        // SAFETY: a valid out pointer.
        unsafe { libc_timespec::clock_gettime(&mut ts) };
        ts.0.tv_sec as u64 * 1_000_000_000 + ts.0.tv_nsec as u64
    }
    #[cfg(not(unix))]
    {
        use std::sync::OnceLock;
        static T0: OnceLock<std::time::Instant> = OnceLock::new();
        T0.get_or_init(std::time::Instant::now).elapsed().as_nanos() as u64 + 1
    }
}

#[cfg(unix)]
mod libc_timespec {
    pub struct Ts(pub libc::timespec);
    pub fn new() -> Ts { Ts(libc::timespec { tv_sec: 0, tv_nsec: 0 }) }
    pub unsafe fn clock_gettime(ts: &mut Ts) { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts.0); }
}

/// On the engine thread as it starts: audio priority, big-core affinity, and a performance hint
/// session if the system has them.
fn engine_thread_setup() -> Option<WorkReport> {
    audio_priority();
    pin_fast_cores();
    let mut hint = hint::Hint::for_this_thread()?;
    Some(Box::new(move |busy, audio, bg| hint.report(busy, audio, bg)))
}

/// Prefer the SoC's fastest CPU cluster (e.g. Cortex-X1 on Tensor G2) so Android doesn't leave
/// or migrate the emulator onto little cores between sleeps.
fn pin_fast_cores() {
    #[cfg(target_os = "android")]
    {
        let mut freqs: Vec<(usize, u64)> = Vec::new();
        for cpu in 0..32usize {
            let path = format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq");
            if let Ok(s) = std::fs::read_to_string(&path) {
                if let Ok(f) = s.trim().parse::<u64>() {
                    freqs.push((cpu, f));
                }
            }
        }
        let Some(max_f) = freqs.iter().map(|&(_, f)| f).max() else { return };
        // Exclude little cores (< 75% of peak clock, e.g. A55 on Tensor G2) while keeping both
        // prime (X1) and big (A78) cores available so sustained DSP avoids per-core X1 thermal
        // throttling (measured on Pixel 7 Pro: big >= prime).
        let big: Vec<usize> = freqs.iter().filter(|&&(_, f)| f >= max_f * 75 / 100).map(|&(c, _)| c).collect();
        if big.is_empty() || big.len() == freqs.len() {
            return;
        }
        // SAFETY: zeroed cpu_set_t populated with valid cpu indices (< 32).
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_ZERO(&mut set);
            for &c in &big {
                libc::CPU_SET(c, &mut set);
            }
            if libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set) == 0 {
                let min_f = freqs.iter().filter(|(c, _)| big.contains(c)).map(|&(_, f)| f).min().unwrap_or(0);
                log::info!("engine thread: pinned to big cores {:?} (>= {} MHz)", big, min_f / 1000);
            } else {
                log::warn!("engine thread: sched_setaffinity failed");
            }
        }
    }
}

/// Raise the engine thread to audio priority (what Process.setThreadPriority(URGENT_AUDIO) does).
fn audio_priority() {
    #[cfg(target_os = "android")]
    // SAFETY: plain system calls on the current thread.
    unsafe {
        let tid = libc::gettid();
        if libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, -19) != 0 {
            log::warn!("engine thread: could not raise priority");
        }
    }
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    if a.is_null() { Vec::new() } else { env.convert_byte_array(a).unwrap_or_default() }
}

fn new_bytes(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: jni::JavaVM, _: *mut std::ffi::c_void) -> jint {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("harbor").with_max_level(log::LevelFilter::Info));
    jni::sys::JNI_VERSION_1_6
}

/// Start the engine and the audio output (idempotent). `lead`: frames the engine runs ahead.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_start(
    _env: JNIEnv, _: JClass, lead: jint, low_latency: jboolean,
) -> jboolean {
    let requested = lead.max(64) as usize;
    let mut h = host();
    if h.is_none() {
        let (engine, out) = Engine::start(requested, engine_thread_setup);
        let out = Arc::new(Mutex::new(out));
        let audio = audio::Output::new(out.clone(), low_latency != 0);
        *h = Some(Host {
            engine,
            out,
            audio,
            parsers: HashMap::new(),
            recorder: None,
            pot: 512,
            enc_clicks: [0; 7],
            learn_source: -1,
            learned: None,
            requested_lead: requested,
        });
    }
    let host = h.as_mut().unwrap();
    host.requested_lead = requested;
    host.audio.ensure_running();
    update_lead_for_audio(host);
    (host.audio.is_running() as u8) as jboolean
}

/// Stop the audio output and the engine.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_shutdown(_env: JNIEnv, _: JClass) {
    let h = host().take();
    drop(h);
}

/// Pause or resume the output (headphones unplugged, audio focus lost). The emulation pauses
/// with it: the engine only runs to keep its ring `lead` frames ahead of the output.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_setPaused(_env: JNIEnv, _: JClass, paused: jboolean) {
    if let Some(h) = host().as_mut() {
        if paused != 0 {
            h.audio.pause()
        } else {
            h.audio.ensure_running();
            update_lead_for_audio(h);
            h.engine.send(Cmd::Settle);
        }
    }
}

/// Tell the engine whether Harbor is running in the background so ADPF can tighten its target.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_setBackground(_env: JNIEnv, _: JClass, bg: jboolean) {
    if let Some(h) = host().as_ref() {
        h.engine.set_background(bg != 0);
    }
}

/// Reopen the output with new settings (buffer mode) or after the device changed.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_restartAudio(
    _env: JNIEnv, _: JClass, lead: jint, low_latency: jboolean,
) {
    if let Some(h) = host().as_mut() {
        h.requested_lead = lead.max(64) as usize;
        h.audio = audio::Output::new(h.out.clone(), low_latency != 0);
        h.audio.ensure_running();
        update_lead_for_audio(h);
        h.engine.send(Cmd::Settle);
    }
}

/// Boot a package. `sectors`: Harbor's sector store (savefile::sectors_to_bytes), or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_boot(
    mut env: JNIEnv, _: JClass, pkg: JByteArray, sectors: JByteArray, mhz: jint, auto_clock: jboolean,
    syx: JByteArray, jit: jboolean, id: jlong,
) {
    let pkg = bytes(&env, &pkg);
    let sectors = match savefile::sectors_from_bytes(&bytes(&env, &sectors)) {
        Ok(s) => s,
        Err(e) => return throw(&mut env, &e),
    };
    let syx = (!syx.is_null()).then(|| bytes(&env, &syx));
    if let Some(h) = host().as_mut() {
        h.engine.send(Cmd::Boot(Boot {
            pkg: Arc::new(pkg), sectors, mhz: mhz as u32, auto_clock: auto_clock != 0, syx, jit: jit != 0, id: id as u64,
        }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_unload(_env: JNIEnv, _: JClass) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Unload) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_key(_env: JNIEnv, _: JClass, id: jint, down: jboolean) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Key { id: id as u8, down: down != 0 }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_enc(_env: JNIEnv, _: JClass, enc: jint, clicks: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Enc { enc: enc as u8, clicks }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_pot(_env: JNIEnv, _: JClass, value: jint) {
    if let Some(h) = host().as_mut() {
        let v = value.clamp(0, 1023) as u16;
        h.pot = v;
        h.engine.send(Cmd::Pot(v));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_analog(_env: JNIEnv, _: JClass, idx: jint, value: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Analog { idx: idx as u8, value: value.clamp(0, 1023) as u16 }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_velocity(_env: JNIEnv, _: JClass, us: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Vel(us.max(0) as u32)) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_setClock(_env: JNIEnv, _: JClass, mhz: jint, auto: jboolean) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Clock { mhz: mhz as u32, auto: auto != 0 }) }
}

// ------------------------------------------------------------------------------------- MIDI

/// A MIDI source was opened. `remap`: channel 0..15 to move channel messages to, or -1.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiOpen(_env: JNIEnv, _: JClass, source: jint, remap: jint) {
    if let Some(h) = host().as_mut() {
        h.parsers.insert(source, MidiParser::new((0..16).contains(&remap).then_some(remap as u8)));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiRemap(_env: JNIEnv, _: JClass, source: jint, remap: jint) {
    if let Some(h) = host().as_mut() {
        let p = h.parsers.entry(source).or_default();
        p.remap = (0..16).contains(&remap).then_some(remap as u8);
    }
}

/// Set the control mapping rules and knob behaviour for `source`.
/// `rules` is packed in 5-int tuples: `[trigger_kind, number, channel, target_kind, target_arg]`.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiMap(
    env: JNIEnv, _: JClass, source: jint, rules: JIntArray, knob_mode: jint, sensitivity: jint,
) {
    let len = if rules.is_null() { 0 } else { env.get_array_length(&rules).unwrap_or(0).max(0) as usize };
    let mut raw = vec![0i32; len];
    if len > 0 {
        let _ = env.get_int_array_region(&rules, 0, &mut raw);
    }
    let parsed: Vec<MappingRule> = raw
        .chunks_exact(5)
        .filter_map(|c| {
            let kind = match c[0] {
                0 => TriggerKind::Cc,
                1 => TriggerKind::Note,
                _ => return None,
            };
            let number = (c[1].clamp(0, 127)) as u8;
            let channel = (0..16).contains(&c[2]).then_some(c[2] as u8);
            let target = match c[3] {
                0 => ControlTarget::Enc((c[4] & 0x7f) as u8),
                1 => ControlTarget::EncStep {
                    enc: (c[4] & 0xff) as u8,
                    clicks: c[4] >> 8,
                },
                2 => ControlTarget::Pot,
                3 => ControlTarget::PotStep(c[4].clamp(-1023, 1023) as i16),
                4 => ControlTarget::Button((c[4] & 0x7f) as u8),
                _ => return None,
            };
            Some(MappingRule {
                trigger: Trigger { kind, number, channel },
                target,
            })
        })
        .collect();
    if let Some(h) = host().as_mut() {
        let p = h.parsers.entry(source).or_default();
        p.set_mapping(parsed, KnobMode::from_i32(knob_mode), Sensitivity::from_i32(sensitivity));
    }
}

/// Start listening for the next CC or Note-On on `source` (`-1` cancels).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiLearnStart(_env: JNIEnv, _: JClass, source: jint) {
    if let Some(h) = host().as_mut() {
        h.learn_source = source;
        h.learned = None;
    }
}

/// Take the message captured since `midiLearnStart`: `[source, kind, channel, number]`, or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiLearnPoll(env: JNIEnv, _: JClass) -> jintArray {
    let got = host().as_mut().and_then(|h| h.learned.take());
    let Some(vals) = got else { return std::ptr::null_mut() };
    let Ok(arr) = env.new_int_array(4) else { return std::ptr::null_mut() };
    let _ = env.set_int_array_region(&arr, 0, &vals);
    arr.into_raw()
}

/// Bytes from a source (`MidiReceiver.onSend`), stamped with its CLOCK_MONOTONIC timestamp.
/// Returns true when a message other than real-time went out (the activity dot).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiData(
    env: JNIEnv, _: JClass, source: jint, data: JByteArray, offset: jint, count: jint, timestamp: jlong,
) -> jboolean {
    if data.is_null() { return JNI_FALSE; }
    let Ok(arr_len) = env.get_array_length(&data) else { return JNI_FALSE };
    let arr_len = arr_len.max(0) as usize;
    let o = offset.max(0) as usize;
    let n = count.max(0) as usize;
    let Some(end) = o.checked_add(n).map(|e| e.min(arr_len)) else { return JNI_FALSE };
    let len = end.saturating_sub(o);
    if len == 0 { return JNI_FALSE; }

    let mut stack_buf = [0i8; 64];
    let heap_buf: Vec<u8>;
    let chunk: &[u8] = if len <= stack_buf.len() {
        if env.get_byte_array_region(&data, o as jni::sys::jsize, &mut stack_buf[..len]).is_err() {
            return JNI_FALSE;
        }
        // SAFETY: `i8` and `u8` have identical size and alignment, initialized by `get_byte_array_region`.
        unsafe { std::slice::from_raw_parts(stack_buf.as_ptr() as *const u8, len) }
    } else {
        heap_buf = bytes(&env, &data);
        let Some(slice) = heap_buf.get(o..end.min(heap_buf.len())) else { return JNI_FALSE };
        slice
    };
    let now = now_ns();
    let mut h = host();
    let Some(h) = h.as_mut() else { return JNI_FALSE };
    let Host { engine, parsers, pot, enc_clicks, learn_source, learned, .. } = h;
    let parser = parsers.entry(source).or_default();
    let mut got_learn = None;
    let active = parser.feed_mapped(
        chunk,
        &mut |m| engine.midi(m, timestamp.max(0) as u64, now),
        &mut |action| match action {
            PanelAction::Enc { enc, clicks } => {
                engine.send(Cmd::Enc { enc, clicks });
                if (enc as usize) < enc_clicks.len() {
                    enc_clicks[enc as usize] = enc_clicks[enc as usize].wrapping_add(clicks);
                }
            }
            PanelAction::Pot(v) => {
                *pot = v.min(1023);
                engine.send(Cmd::Pot(*pot));
            }
            PanelAction::PotDelta(d) => {
                *pot = (*pot as i32 + d as i32).clamp(0, 1023) as u16;
                engine.send(Cmd::Pot(*pot));
            }
            PanelAction::Key { id, down } => {
                engine.send(Cmd::Key { id, down });
            }
        },
        &mut got_learn,
    );
    if *learn_source == source {
        if let Some((kind, ch, num)) = got_learn {
            *learned = Some([source, kind as i32, ch as i32, num as i32]);
            *learn_source = -1;
        }
    }
    if active { JNI_TRUE } else { JNI_FALSE }
}

/// A source went away: release the notes and panel buttons it held.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiClose(_env: JNIEnv, _: JClass, source: jint) {
    if let Some(h) = host().as_mut() {
        if let Some(mut p) = h.parsers.remove(&source) {
            let now = now_ns();
            let engine = &h.engine;
            p.release_all_with_panel(
                &mut |m| engine.midi(m, 0, now),
                &mut |a| if let PanelAction::Key { id, down } = a { engine.send(Cmd::Key { id, down }); },
            );
        }
    }
}

/// All notes off on every channel, and every source's held notes and panel buttons released.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_panic(_env: JNIEnv, _: JClass) {
    if let Some(h) = host().as_mut() {
        let now = now_ns();
        let Host { engine, parsers, .. } = h;
        for p in parsers.values_mut() {
            p.release_all_with_panel(
                &mut |m| engine.midi(m, 0, now),
                &mut |a| if let PanelAction::Key { id, down } = a { engine.send(Cmd::Key { id, down }); },
            );
        }
        for m in panic_messages() {
            engine.midi(&m, 0, now);
        }
    }
}

// ----------------------------------------------------------------------------------- the view

/// Copy the screen (240x240 RGB565, little-endian) into `buf` (a direct buffer of 115200 bytes)
/// if its revision isn't `since` (the caller's last copy). Returns the revision copied (or `since`).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_lcd(env: JNIEnv, _: JClass, buf: JByteBuffer, since: jlong) -> jlong {
    let h = host();
    let Some(h) = h.as_ref() else { return since };
    if h.engine.shared.lcd_rev.load(std::sync::atomic::Ordering::Acquire) as jlong == since {
        return since;
    }
    let v = h.engine.shared.view.lock().unwrap();
    if v.lcd_rev as jlong != since {
        if let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) {
            if cap >= LCD_PIXELS * 2 {
                // SAFETY: a direct buffer of at least that many bytes, owned by the caller.
                let dst = unsafe { std::slice::from_raw_parts_mut(ptr, LCD_PIXELS * 2) };
                for (d, p) in dst.chunks_exact_mut(2).zip(v.lcd.iter()) {
                    d.copy_from_slice(&p.to_le_bytes());
                }
                return v.lcd_rev as jlong;
            }
        }
    }
    since
}

/// The LEDs' on-fractions over the last ~16 ms (41 floats; SMK-37: its button LEDs first).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_leds(env: JNIEnv, _: JClass, out: JFloatArray) {
    if let Some(h) = host().as_ref() {
        let leds = h.engine.shared.view.lock().unwrap().leds;
        let _ = env.set_float_array_region(&out, 0, &leds);
    }
}

/// External MIDI panel state (8 ints): cumulative clicks for encoders 0..6, then MASTER pot 0..1023.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_panelControls(env: JNIEnv, _: JClass, out: JIntArray) {
    if let Some(h) = host().as_ref() {
        let vals = [
            h.enc_clicks[0],
            h.enc_clicks[1],
            h.enc_clicks[2],
            h.enc_clicks[3],
            h.enc_clicks[4],
            h.enc_clicks[5],
            h.enc_clicks[6],
            h.pot as i32,
        ];
        let _ = env.set_int_array_region(&out, 0, &vals);
    }
}

/// Status fields, in the order of NativeEngine.Status (Kotlin): see there.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_status(env: JNIEnv, _: JClass, out: JDoubleArray) {
    let h = host();
    let Some(h) = h.as_ref() else { return };
    let v = h.engine.shared.view.lock().unwrap();
    let a = h.audio.info();
    let state = match v.state { RunState::Idle => 0.0, RunState::Running => 1.0, RunState::Halted => 2.0, RunState::Failed => 3.0 };
    let fields = [
        state,
        v.boot_id as f64,
        (v.board == Board::Smk37) as u8 as f64,
        v.mhz as f64,
        v.auto_clock as u8 as f64,
        v.clock_steps as f64,
        v.jit as u8 as f64,
        v.speed as f64,
        v.late as f64,
        v.starved as f64,
        v.peak[0] as f64,
        v.peak[1] as f64,
        v.clip as u8 as f64,
        v.flash.revision as f64,
        v.flash.boot_id as f64,
        v.midi_dropped as f64,
        v.lead as f64,
        a.sample_rate as f64,
        a.frames_per_burst as f64,
        a.buffer_frames as f64,
        a.running as u8 as f64,
        a.exclusive as u8 as f64,
        a.xruns as f64,
        a.device_id as f64,
        v.midi_in as f64,
        h.recorder.as_ref().map_or(-1.0, |r| r.frames() as f64),
    ];
    let _ = env.set_double_array_region(&out, 0, &fields);
}

/// The last boot error, or "".
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_error(env: JNIEnv, _: JClass) -> jstring {
    let msg = host().as_ref().map(|h| h.engine.shared.view.lock().unwrap().error.clone()).unwrap_or_default();
    env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Take any new engine diagnostic lines (internal resets, cpu1 release, console, unknown MMIO), or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_pollDiagnostic(env: JNIEnv, _: JClass) -> jstring {
    let h = host();
    let Some(h) = h.as_ref() else { return std::ptr::null_mut() };
    if !h.engine.shared.diag_pending.swap(false, std::sync::atomic::Ordering::AcqRel) {
        return std::ptr::null_mut();
    }
    let msg = std::mem::take(&mut h.engine.shared.view.lock().unwrap().diagnostic);
    if msg.is_empty() {
        return std::ptr::null_mut();
    }
    env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The flash sectors last published (sector store format), for the boot `bootId`, or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_flashSectors(mut env: JNIEnv, _: JClass) -> jbyteArray {
    let data = host().as_ref().map(|h| savefile::sectors_to_bytes(&h.engine.shared.view.lock().unwrap().flash.sectors));
    match data { Some(d) => new_bytes(&mut env, &d), None => std::ptr::null_mut() }
}

/// Wait (at most `timeoutMs`) for the firmware to finish writing flash, then return its sectors.
/// Null if it is still writing or nothing is running. Blocks: call off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_flush(mut env: JNIEnv, _: JClass, timeout_ms: jint) -> jbyteArray {
    // (no lock held while waiting: the engine thread answers)
    let request = {
        let h = host();
        h.as_ref().map(|h| {
            let (tx, rx) = std::sync::mpsc::channel();
            h.engine.send(Cmd::Flush(tx));
            rx
        })
    };
    let flash = request.and_then(|rx| rx.recv_timeout(Duration::from_millis(timeout_ms.max(0) as u64)).ok().flatten());
    match flash { Some(f) => new_bytes(&mut env, &savefile::sectors_to_bytes(&f.sectors)), None => std::ptr::null_mut() }
}

// -------------------------------------------------------------------------------- recording

/// Start recording the firmware's output as a WAV into `fd` (a writable, seekable file; this
/// takes ownership and closes it). False if a recording is already running or the file failed.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_recordStart(_env: JNIEnv, _: JClass, fd: jint) -> jboolean {
    #[cfg(unix)]
    {
        use std::os::fd::FromRawFd;
        // SAFETY: the caller hands over an open descriptor it no longer uses (detachFd).
        let file = unsafe { std::fs::File::from_raw_fd(fd) };
        let mut h = host();
        let Some(h) = h.as_mut().filter(|h| h.recorder.is_none()) else { return JNI_FALSE };
        match Recorder::start(file) {
            Ok((rec, tap)) => {
                h.engine.send(Cmd::Record(Some(tap)));
                h.recorder = Some(rec);
                JNI_TRUE
            }
            Err(e) => {
                log::error!("could not start a recording: {e}");
                JNI_FALSE
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = fd;
        JNI_FALSE
    }
}

/// End the recording: [frames, dropped frames], or null if none was running or the file failed.
/// Blocks until the file is complete: call off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_recordStop(env: JNIEnv, _: JClass) -> jlongArray {
    // (the writer finishes once the engine thread drops its tap: not under the host's lock)
    let rec = {
        let mut h = host();
        h.as_mut().and_then(|h| {
            h.engine.send(Cmd::Record(None));
            h.recorder.take()
        })
    };
    let Some(rec) = rec else { return std::ptr::null_mut() };
    match rec.finish() {
        Ok(s) => {
            let Ok(arr) = env.new_long_array(2) else { return std::ptr::null_mut() };
            let _ = env.set_long_array_region(&arr, 0, &[s.frames as jlong, s.dropped as jlong]);
            arr.into_raw()
        }
        Err(e) => {
            log::error!("the recording failed: {e}");
            std::ptr::null_mut()
        }
    }
}

// ------------------------------------------------------------------------- packages and saves

/// Check a package unpacks. Returns "fm1" or "smk37"; throws with the reason otherwise.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_packageBoard(mut env: JNIEnv, _: JClass, pkg: JByteArray) -> jstring {
    let pkg = bytes(&env, &pkg);
    match std::panic::catch_unwind(|| harbor_engine::machine::package_board(&pkg)).unwrap_or_else(|_| Err("malformed firmware package".into())) {
        Ok(b) => env.new_string(if b == Board::Smk37 { "smk37" } else { "fm1" }).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => { throw(&mut env, &e); std::ptr::null_mut() }
    }
}

/// Encode a `.fm1save`. Throws on invalid data.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_saveEncode(
    mut env: JNIEnv, _: JClass, name: JString, pkg: JByteArray, sectors: JByteArray, include_package: jboolean,
) -> jbyteArray {
    let name: String = env.get_string(&name).map(Into::into).unwrap_or_default();
    let pkg = bytes(&env, &pkg);
    let result = savefile::sectors_from_bytes(&bytes(&env, &sectors))
        .and_then(|sectors| savefile::encode(&savefile::SaveData { name, pkg, sectors }, include_package != 0));
    match result {
        Ok(b) => new_bytes(&mut env, &b),
        Err(e) => { throw(&mut env, &e); std::ptr::null_mut() }
    }
}

/// Decode a `.fm1save` into [name: String, package: byte[], sectors: byte[]]. `fallback`: the
/// package to use when the save carries none. Throws with the reason on invalid files.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_saveDecode(
    mut env: JNIEnv, _: JClass, data: JByteArray, fallback: JByteArray,
) -> jobjectArray {
    let data = bytes(&env, &data);
    let fallback = (!fallback.is_null()).then(|| bytes(&env, &fallback));
    let save = match savefile::decode(&data, fallback.as_deref()) {
        Ok(s) => s,
        Err(e) => { throw(&mut env, &e); return std::ptr::null_mut(); }
    };
    let build = |env: &mut JNIEnv| -> jni::errors::Result<jobjectArray> {
        let arr = env.new_object_array(3, "java/lang/Object", JObject::null())?;
        let name = env.new_string(&save.name)?;
        let pkg = env.byte_array_from_slice(&save.pkg)?;
        let sectors = env.byte_array_from_slice(&savefile::sectors_to_bytes(&save.sectors))?;
        env.set_object_array_element(&arr, 0, name)?;
        env.set_object_array_element(&arr, 1, pkg)?;
        env.set_object_array_element(&arr, 2, sectors)?;
        Ok(arr.into_raw())
    };
    build(&mut env).unwrap_or(std::ptr::null_mut())
}

/// The package hash a save names (to find a bring-your-own package), or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_savePeekHash(env: JNIEnv, _: JClass, data: JByteArray) -> jstring {
    match savefile::peek_hash(&bytes(&env, &data)) {
        Some(h) => env.new_string(h).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Whether this build compiles hot firmware code (AArch64) or interprets it.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_jitAvailable(_env: JNIEnv, _: JClass) -> jboolean {
    harbor_engine::machine::jit_usable() as jboolean
}
