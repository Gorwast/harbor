//! The emulated FM-1 on a thread of its own, running a little ahead of the audio output.
//!
//! As in fm1-plugin's engine: the firmware makes its audio in DMA halves whose emulation time
//! varies, so the machine doesn't run in the audio callback. This thread keeps a ring of
//! 44.1 kHz frames `lead` frames ahead of what the output has taken; the output side
//! ([`AudioOut`]) only copies (and resamples) from the ring. MIDI is stamped with the frame it
//! belongs to (`lead` frames after the frame playing when it arrived), so notes keep their
//! spacing and the delay is a constant.
//!
//! Harbor adds an automatic clock: a firmware that can't keep real time on this device is
//! booted again one clock step lower, with what it has written to flash, instead of stuttering.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use fm1_soc::{Action, Board};

use crate::machine::Machine;
use crate::recorder::Tap;
use crate::savefile::Sector;

/// The firmware's output rate.
pub const RATE: u32 = 44_100;
/// The automatic clock's steps, in MHz: the chip's own clock first.
pub const CLOCKS: [u32; 4] = [240, 200, 160, 120];
/// Below this many times real time (emulated time over the time spent emulating it), with the
/// output starving, a firmware counts as not keeping up.
pub const SLOW_SPEED: f32 = 1.15;
/// Seconds in a row a firmware must be slow before the clock steps down: long enough that a
/// one-off stall (a GC pause, an app switch, a thermal blip) never costs a step.
const SLOW_SECONDS: u32 = 5;
/// Seconds after a boot, a resume or an audio restart that don't count: the boot runs flat out to
/// fill the ring, and the JIT is still compiling the firmware's code paths for a while after.
const WARMUP_SECONDS: u32 = 6;
const RING: usize = 16_384;
/// The 240x240 RGB565 screen (fm1-soc lcd.rs).
pub const LCD_PIXELS: usize = 240 * 240;
const CHUNK: u32 = 20_000;
/// Told `(busy, audio, background)`: the engine spent `busy` making `audio` worth of output
/// (about [`REPORT_EVERY`] at a time), and whether Harbor is running in the background.
pub type WorkReport = Box<dyn FnMut(Duration, Duration, bool)>;
/// Optional callback invoked on the engine thread when CPU affinity mode changes (`true` = prime cores).
pub type PinCallback = Box<dyn FnMut(bool) + Send>;
/// How much audio each [`WorkReport`] call covers.
pub const REPORT_EVERY: Duration = Duration::from_millis(10);

/// The shortest stretch of emulated time the LEDs are averaged over.
const LED_WINDOW_MS: u64 = 25;

/// What the app asks of the machine.
pub enum Cmd {
    Boot(Boot),
    /// Stop the machine (nothing docked).
    Unload,
    Key { id: u8, down: bool },
    Enc { enc: u8, clicks: i32 },
    /// The MASTER pot, 0..1023 (kept across boots).
    Pot(u16),
    /// SMK-37: an analog input (fm1_soc::smk's ANALOG_*).
    Analog { idx: u8, value: u16 },
    /// SMK-37: the next keys' velocity, microseconds between a key's two contacts.
    Vel(u32),
    /// The clock: `auto` steps down from `mhz` on its own when the firmware can't keep up.
    Clock { mhz: u32, auto: bool },
    /// Reply with the flash as it is once no SPI write is in progress (at most a few seconds).
    Flush(mpsc::Sender<Option<Flash>>),
    /// The output was paused or reopened: what follows is a new warm-up, not slowness.
    Settle,
    /// Start copying the firmware's output to a recording, or stop (`None`: the tap is dropped,
    /// which ends the recording).
    Record(Option<Tap>),
    /// Switch CPU core affinity (`true`: prime cores only; `false`: big + prime cores).
    PinPrime(bool),
}

pub struct Boot {
    pub pkg: Arc<Vec<u8>>,
    pub sectors: Vec<Sector>,
    pub mhz: u32,
    pub auto_clock: bool,
    pub syx: Option<Vec<u8>>,
    pub jit: bool,
    /// The app's id for this boot, echoed in the view.
    pub id: u64,
}

/// Flash changed by the firmware, for one boot.
#[derive(Clone, Default)]
pub struct Flash {
    pub boot_id: u64,
    /// Counts up each time `sectors` changes.
    pub revision: u64,
    pub sectors: Vec<Sector>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum RunState {
    #[default]
    Idle,
    Running,
    Halted,
    Failed,
}

/// The LCD framebuffer, kept under its own mutex so 60 Hz UI reads never contend with status/LEDs.
pub struct LcdFrame {
    pub rev: u64,
    pub pixels: Vec<u16>,
}

impl Default for LcdFrame {
    fn default() -> Self {
        Self { rev: 0, pixels: vec![0; LCD_PIXELS] }
    }
}

/// What the app shows: the status, the LEDs and how the machine is doing.
pub struct View {
    /// Counts up whenever `Shared::lcd` changes.
    pub lcd_rev: u64,
    pub leds: [f32; 41],
    pub pads: [u32; 16],
    pub board: Board,
    pub state: RunState,
    pub boot_id: u64,
    pub error: String,
    pub mhz: u32,
    pub auto_clock: bool,
    /// Automatic clock steps taken since the boot request.
    pub clock_steps: u32,
    pub jit: bool,
    /// Emulated time over the time spent emulating it, over the last second: how much faster
    /// than real time the machine could run (it runs at the output's pace).
    pub speed: f32,
    /// Frames the output found missing in the last second, and in all.
    pub late: u64,
    pub starved: u64,
    /// Output peaks since the last view update, and whether a sample hit full scale.
    pub peak: [f32; 2],
    pub clip: bool,
    pub flash: Flash,
    pub midi_dropped: u64,
    /// MIDI bytes put into the MIDI IN jack since the engine started.
    pub midi_in: u64,
    /// Frames between a MIDI message's arrival and its sound.
    pub lead: usize,
    /// Diagnostic notices (firmware internal resets, cpu1 release, console output, unknown MMIO).
    pub diag_rev: u64,
    pub diagnostic: String,
}

impl Default for View {
    fn default() -> Self {
        View {
            lcd_rev: 0,
            leds: [0.0; 41],
            pads: [0; 16],
            board: Board::Fm1,
            state: RunState::Idle,
            boot_id: 0,
            error: String::new(),
            mhz: CLOCKS[0],
            auto_clock: true,
            clock_steps: 0,
            jit: false,
            speed: 0.0,
            late: 0,
            starved: 0,
            peak: [0.0; 2],
            clip: false,
            flash: Flash::default(),
            midi_dropped: 0,
            midi_in: 0,
            lead: 0,
            diag_rev: 0,
            diagnostic: String::new(),
        }
    }
}

/// The output's position, shared with the engine: frames taken from the ring, and when.
#[derive(Default)]
pub struct AudioClock {
    pub taken: AtomicU64,
    /// CLOCK_MONOTONIC nanoseconds (Java's `System.nanoTime`) at the last take
    pub taken_ns: AtomicU64,
    pub starved: AtomicU64,
}

impl AudioClock {
    /// The ring frame playing at `ns`.
    fn frame_at(&self, ns: u64) -> u64 {
        let taken = self.taken.load(Ordering::Acquire);
        let at = self.taken_ns.load(Ordering::Acquire);
        if at == 0 || ns <= at {
            return taken;
        }
        taken + ((ns - at) as u128 * RATE as u128 / 1_000_000_000) as u64
    }
}

pub struct Shared {
    pub view: Mutex<View>,
    pub lcd: Mutex<LcdFrame>,
    pub clock: AudioClock,
    pub lead: AtomicUsize,
    /// Mirrors `lcd.rev` so the UI thread skips locking `lcd` when the screen didn't change.
    pub lcd_rev: AtomicU64,
    pub diag_pending: AtomicBool,
    pub background: AtomicBool,
    quit: AtomicBool,
    /// Tests: sleep this many times the emulation time after each chunk, as a slower device
    /// would take (f32 bits; 0 = off).
    throttle: AtomicU32,
}

/// A MIDI message for the machine, with the ring frame it is due at.
struct Midi {
    frame: u64,
    bytes: Vec<u8>,
}

/// The app's handle on the engine thread.
pub struct Engine {
    cmds: mpsc::Sender<Cmd>,
    midi: mpsc::Sender<Midi>,
    pub shared: Arc<Shared>,
    thread: Option<JoinHandle<()>>,
}

impl Engine {
    /// Start the engine thread and return it with the output side, for the audio callback.
    /// `on_start` runs on the thread first (priority and the like) and may return a [`WorkReport`]
    /// to be told how long each stretch of audio took to make.
    pub fn start(lead: usize, on_start: impl FnOnce() -> Option<WorkReport> + Send + 'static) -> (Engine, AudioOut) {
        Self::start_with_pin(lead, on_start, None)
    }

    pub fn start_with_pin(
        lead: usize,
        on_start: impl FnOnce() -> Option<WorkReport> + Send + 'static,
        on_pin: Option<PinCallback>,
    ) -> (Engine, AudioOut) {
        let (prod, cons) = rtrb::RingBuffer::new(RING);
        let shared = Arc::new(Shared {
            view: Mutex::new(View::default()),
            lcd: Mutex::new(LcdFrame::default()),
            clock: AudioClock::default(),
            lead: AtomicUsize::new(lead.clamp(64, RING / 2)),
            lcd_rev: AtomicU64::new(0),
            diag_pending: AtomicBool::new(false),
            background: AtomicBool::new(false),
            quit: AtomicBool::new(false),
            throttle: AtomicU32::new(0),
        });
        let (cmds, cmd_rx) = mpsc::channel();
        let (midi, midi_rx) = mpsc::channel();
        let s = shared.clone();
        let thread = std::thread::Builder::new()
            .name("harbor-engine".into())
            .spawn(move || {
                let report = on_start();
                run(s, cmd_rx, midi_rx, prod, report, on_pin)
            })
            .expect("spawn the engine thread");
        let out = AudioOut { ring: cons, shared: shared.clone(), prev: [0.0; 2], next: [0.0; 2], pos: 1.0 };
        (Engine { cmds, midi, shared, thread: Some(thread) }, out)
    }

    pub fn send(&self, c: Cmd) { let _ = self.cmds.send(c); }

    /// Complete MIDI messages (see [`crate::midi::MidiParser`]) that arrived at `ns`
    /// (CLOCK_MONOTONIC; 0 = now).
    pub fn midi(&self, msg: &[u8], ns: u64, now_ns: u64) {
        let ns = if ns == 0 || ns > now_ns { now_ns } else { ns };
        let lead = self.shared.lead.load(Ordering::Relaxed) as u64;
        let frame = self.shared.clock.frame_at(ns) + lead;
        let _ = self.midi.send(Midi { frame, bytes: msg.to_vec() });
    }

    /// Tests: make the emulation `factor` times slower than this machine runs it.
    #[doc(hidden)]
    pub fn set_throttle(&self, factor: f32) {
        self.shared.throttle.store(factor.max(0.0).to_bits(), Ordering::Relaxed);
    }

    pub fn set_lead(&self, frames: usize) {
        self.shared.lead.store(frames.clamp(64, RING / 2), Ordering::Relaxed);
    }

    pub fn set_background(&self, bg: bool) {
        self.shared.background.store(bg, Ordering::Relaxed);
    }

    /// The flash once quiet, waiting at most `timeout`.
    pub fn flush(&self, timeout: Duration) -> Option<Flash> {
        let (tx, rx) = mpsc::channel();
        self.send(Cmd::Flush(tx));
        rx.recv_timeout(timeout).ok().flatten()
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.shared.quit.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

/// The audio callback's side: frames out of the ring, resampled to the device's rate.
pub struct AudioOut {
    ring: rtrb::Consumer<[f32; 2]>,
    shared: Arc<Shared>,
    prev: [f32; 2],
    next: [f32; 2],
    /// position between `prev` and `next`, 0..1 (1: take the next frame first)
    pos: f64,
}

impl AudioOut {
    /// Fill `out` (interleaved stereo, `out.len() / 2` frames at `rate` Hz). No allocation, no
    /// locks: safe in a real-time callback. `now_ns`: CLOCK_MONOTONIC now.
    pub fn render(&mut self, out: &mut [f32], rate: u32, now_ns: u64) {
        let frames = out.len() / 2;
        let mut taken = 0u64;
        let mut starved = 0u64;
        if rate == RATE {
            let avail = self.ring.slots().min(frames);
            if avail > 0 {
                if let Ok(chunk) = self.ring.read_chunk(avail) {
                    let (a, b) = chunk.as_slices();
                    let mut idx = 0;
                    for v in a.iter().chain(b.iter()) {
                        out[idx] = v[0];
                        out[idx + 1] = v[1];
                        idx += 2;
                    }
                    taken = (a.len() + b.len()) as u64;
                    chunk.commit_all();
                }
            }
            let got = taken as usize;
            if got < frames {
                out[got * 2..frames * 2].fill(0.0);
                starved = (frames - got) as u64;
            }
        } else if frames > 0 {
            let step = RATE as f64 / rate as f64;
            // Upper bound on input frames consumed across `frames` output steps (+2 guards FP rounding).
            let max_need = (self.pos + step * frames as f64).floor().max(0.0) as usize + 2;
            let avail = self.ring.slots().min(max_need);
            let chunk = if avail > 0 { self.ring.read_chunk(avail).ok() } else { None };
            let (a, b) = chunk.as_ref().map_or((&[][..], &[][..]), |c| c.as_slices());
            let mut consumed = 0usize;
            for f in out.chunks_exact_mut(2) {
                while self.pos >= 1.0 {
                    self.prev = self.next;
                    self.next = if consumed < a.len() {
                        let v = a[consumed];
                        consumed += 1;
                        taken += 1;
                        v
                    } else if consumed - a.len() < b.len() {
                        let v = b[consumed - a.len()];
                        consumed += 1;
                        taken += 1;
                        v
                    } else {
                        starved += 1;
                        [0.0; 2]
                    };
                    self.pos -= 1.0;
                }
                let t = self.pos as f32;
                f[0] = self.prev[0] + (self.next[0] - self.prev[0]) * t;
                f[1] = self.prev[1] + (self.next[1] - self.prev[1]) * t;
                self.pos += step;
            }
            if let Some(chunk) = chunk {
                chunk.commit(consumed);
            }
        }
        let c = &self.shared.clock;
        c.taken.fetch_add(taken, Ordering::AcqRel);
        c.taken_ns.store(now_ns, Ordering::Release);
        if starved > 0 {
            c.starved.fetch_add(starved, Ordering::Relaxed);
        }
    }
}

struct Running {
    m: Machine,
    pkg: Arc<Vec<u8>>,
    boot_id: u64,
    /// the clock the boot asked for (automatic steps go down from it)
    auto: bool,
    seconds: u32,
    slow: u32,
    flash_rev_seen: u32,
    flash_quiet_since: Instant,
    published_rev: u32,
    cpu1_logged: bool,
    console_seen: usize,
    resets_seen: u32,
    unknown_seen: usize,
    lcd_soc_rev: u32,
}

struct PendingFlush {
    reply: mpsc::Sender<Option<Flash>>,
    since: Instant,
}

fn run(
    shared: Arc<Shared>,
    cmds: mpsc::Receiver<Cmd>,
    midi: mpsc::Receiver<Midi>,
    mut out: rtrb::Producer<[f32; 2]>,
    mut report: Option<WorkReport>,
    mut on_pin: Option<PinCallback>,
) {
    let mut r: Option<Running> = None;
    let mut made: u64 = 0; // frames pushed into the ring
    let mut due: VecDeque<Midi> = VecDeque::new();
    let mut pot: u16 = 512;
    let mut flushes: Vec<PendingFlush> = Vec::new();
    let mut flash_revision: u64 = 0;
    let mut last_view = Instant::now();
    let (mut speed_t, mut speed_ms, mut busy) = (Instant::now(), 0u64, Duration::ZERO);
    let mut starved0 = shared.clock.starved.load(Ordering::Relaxed);
    let mut peak = [0f32; 2];
    let mut clip = false;
    let mut midi_in = 0u64;
    let mut led_ms = 0u64;
    let mut tap: Option<Tap> = None;
    let mut housekeep_chunks: u32 = 0;
    let (mut work_busy, mut work_frames) = (Duration::ZERO, 0u64);
    let report_frames = RATE as u64 * REPORT_EVERY.as_millis() as u64 / 1000;

    let boot = |b: &Boot, mhz: u32, sectors: &[Sector], pot: u16| -> Result<Machine, String> {
        let mut m = Machine::boot(&b.pkg, sectors, mhz, b.syx.as_deref(), b.jit)?;
        if m.board() == Board::Fm1 {
            m.soc.periph.apply(&Action::Adc { ch: 4, value: pot });
        }
        Ok(m)
    };

    while !shared.quit.load(Ordering::Relaxed) {
        while let Ok(c) = cmds.try_recv() {
            match c {
                Cmd::Boot(b) => {
                    for f in flushes.drain(..) {
                        let _ = f.reply.send(r.as_ref().map(|r| current_flash(r, flash_revision)));
                    }
                    let result = boot(&b, b.mhz, &b.sectors, pot);
                    let mut v = shared.view.lock().unwrap();
                    v.boot_id = b.id;
                    v.clock_steps = 0;
                    v.auto_clock = b.auto_clock;
                    v.speed = 0.0;
                    v.late = 0;
                    due.clear();
                    flash_revision += 1;
                    v.flash = Flash { boot_id: b.id, revision: flash_revision, sectors: b.sectors.clone() };
                    match result {
                        Ok(m) => {
                            v.state = RunState::Running;
                            v.error.clear();
                            v.board = m.board();
                            v.mhz = m.mhz();
                            v.jit = m.jit();
                            let rev = m.soc.flash.revision;
                            r = Some(Running {
                                m, pkg: b.pkg.clone(), boot_id: b.id, auto: b.auto_clock, seconds: 0, slow: 0,
                                flash_rev_seen: rev, flash_quiet_since: Instant::now(), published_rev: rev,
                                cpu1_logged: false, console_seen: 0, resets_seen: 0, unknown_seen: 0, lcd_soc_rev: u32::MAX,
                            });
                        }
                        Err(e) => {
                            v.state = RunState::Failed;
                            v.error = e;
                            r = None;
                        }
                    }
                    (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                    starved0 = shared.clock.starved.load(Ordering::Relaxed);
                    // (the boot request's sectors stay in the view: a failed boot loses nothing)
                }
                Cmd::Unload => {
                    r = None;
                    due.clear();
                    let mut v = shared.view.lock().unwrap();
                    v.state = RunState::Idle;
                    v.leds = [0.0; 41];
                }
                Cmd::Key { id, down } => apply(&mut r, Action::Key { id, down }),
                Cmd::Enc { enc, clicks } => apply(&mut r, Action::Enc { enc, clicks }),
                Cmd::Pot(p) => {
                    pot = p.min(1023);
                    if r.as_ref().is_some_and(|r| r.m.board() == Board::Fm1) {
                        apply(&mut r, Action::Adc { ch: 4, value: pot });
                    }
                }
                Cmd::Analog { idx, value } => apply(&mut r, Action::Adc { ch: idx, value: value.min(1023) }),
                Cmd::Vel(us) => {
                    if let Some(r) = r.as_mut() {
                        r.m.soc.periph.smk_vel_us = us;
                    }
                }
                Cmd::Settle => {
                    if let Some(rr) = r.as_mut() {
                        rr.seconds = 0;
                        rr.slow = 0;
                    }
                    (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                    starved0 = shared.clock.starved.load(Ordering::Relaxed);
                }
                Cmd::Clock { mhz, auto } => {
                    if let Some(rr) = r.as_mut() {
                        rr.auto = auto;
                        if rr.m.mhz() != mhz {
                            reclock(&shared, rr, mhz, pot, &mut due, &mut flash_revision);
                        }
                        (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                        let mut v = shared.view.lock().unwrap();
                        v.auto_clock = auto;
                        v.mhz = rr.m.mhz();
                        v.clock_steps = 0;
                    }
                }
                Cmd::Flush(reply) => flushes.push(PendingFlush { reply, since: Instant::now() }),
                Cmd::Record(t) => tap = t,
                Cmd::PinPrime(prime) => {
                    if let Some(pin) = on_pin.as_mut() {
                        pin(prime);
                    }
                }
            }
        }
        while let Ok(e) = midi.try_recv() {
            // keep the queue in frame order (several sources stamp independently)
            let at = due.iter().rposition(|d| d.frame <= e.frame).map_or(0, |i| i + 1);
            due.insert(at, e);
        }

        let lead = shared.lead.load(Ordering::Relaxed);
        let fill = RING - out.slots();
        let Some(rr) = r.as_mut().filter(|r| !r.m.halted) else {
            // nothing to run: silence, so the output's clock and ours stay together
            let want = lead.saturating_sub(fill).min(out.slots());
            if want > 0 {
                if let Ok(chunk) = out.write_chunk_uninit(want) {
                    let written = chunk.fill_from_iter(std::iter::repeat([0.0f32, 0.0]).take(want));
                    made += written as u64;
                }
            }
            due.retain(|e| e.frame > made);
            for f in flushes.drain(..) {
                let _ = f.reply.send(r.as_ref().map(|r| current_flash(r, flash_revision)));
            }
            if let Some(rr) = r.as_ref() {
                let mut v = shared.view.lock().unwrap();
                if rr.m.halted && v.state == RunState::Running {
                    v.state = RunState::Halted;
                    v.error = rr.m.fault.clone();
                }
            }
            std::thread::sleep(Duration::from_millis(2));
            continue;
        };

        let now = if fill >= lead {
            let surplus_us = (fill - lead) as u64 * 1_000_000 / RATE as u64;
            std::thread::sleep(Duration::from_micros((surplus_us / 2).clamp(250, 2_000)));
            housekeep_chunks = 0;
            Some(Instant::now())
        } else {
            while due.front().is_some_and(|e| e.frame <= made) {
                let e = due.pop_front().unwrap();
                for &b in &e.bytes {
                    rr.m.soc.periph.apply(&Action::Midi { b });
                }
                midi_in += e.bytes.len() as u64;
            }
            // Scale chunk up when no MIDI event is imminent (~2 ms window) to amortize outer-loop
            // and JIT entry overhead, pushing harder (80k cycles) when catching up on buffer fill.
            let midi_soon = due.front().is_some_and(|e| e.frame <= made + 96);
            let chunk = if midi_soon {
                CHUNK
            } else if fill < lead / 2 {
                CHUNK * 4
            } else {
                CHUNK * 2
            };
            let t0 = Instant::now();
            rr.m.run(chunk);
            let throttle = f32::from_bits(shared.throttle.load(Ordering::Relaxed));
            if throttle > 0.0 {
                std::thread::sleep(t0.elapsed().mul_f32(throttle));
            }
            let t1 = Instant::now();
            let spent = t1.duration_since(t0);
            busy += spent;
            let a = std::mem::take(&mut rr.m.soc.periph.audio);
            work_busy += spent;
            let n_frames = a.len() / 2;
            work_frames += n_frames as u64;
            if work_frames >= report_frames {
                if let Some(report) = report.as_mut() {
                    report(
                        work_busy,
                        Duration::from_nanos(work_frames * 1_000_000_000 / RATE as u64),
                        shared.background.load(Ordering::Relaxed),
                    );
                }
                (work_busy, work_frames) = (Duration::ZERO, 0);
            }
            if let Some(t) = tap.as_mut() {
                t.push(&a);
            }
            let to_write = out.slots().min(n_frames);
            if to_write > 0 {
                if let Ok(chunk) = out.write_chunk_uninit(to_write) {
                    let written = chunk.fill_from_iter(a.chunks_exact(2).take(to_write).map(|f| {
                        let frame = [f[0] as f32 / 32768.0, f[1] as f32 / 32768.0];
                        peak[0] = peak[0].max(frame[0].abs());
                        peak[1] = peak[1].max(frame[1].abs());
                        clip |= f[0] == i16::MAX || f[0] == i16::MIN || f[1] == i16::MAX || f[1] == i16::MIN;
                        frame
                    }));
                    made += written as u64;
                }
            }
            rr.m.soc.periph.audio = a;
            rr.m.soc.periph.audio.clear();
            housekeep_chunks += 1;
            if housekeep_chunks >= 4 || !flushes.is_empty() {
                housekeep_chunks = 0;
                Some(t1)
            } else {
                None
            }
        };

        let Some(now) = now else {
            continue;
        };

        // flash: publish the changed sectors once the firmware has stopped writing for a moment
        let rev = rr.m.soc.flash.revision;
        if rev != rr.flash_rev_seen {
            rr.flash_rev_seen = rev;
            rr.flash_quiet_since = now;
        }
        let quiet = !rr.m.soc.flash.busy() && now.duration_since(rr.flash_quiet_since) >= Duration::from_millis(250);
        if quiet && rev != rr.published_rev {
            rr.published_rev = rev;
            flash_revision += 1;
            let flash = current_flash(rr, flash_revision);
            shared.view.lock().unwrap().flash = flash;
        }
        flushes.retain(|f| {
            if quiet {
                let _ = f.reply.send(Some(current_flash(rr, flash_revision)));
                false
            } else if now.duration_since(f.since) > Duration::from_secs(3) {
                let _ = f.reply.send(None);
                false
            } else {
                true
            }
        });

        if now.duration_since(last_view) >= Duration::from_millis(16) {
            last_view = now;
            // Compare LCD revision first (O(1)) and copy directly into Shared.lcd without holding Shared.view.
            let soc_lcd_rev = rr.m.soc.lcd.revision;
            let mut new_lcd_rev = None;
            if soc_lcd_rev != rr.lcd_soc_rev {
                rr.lcd_soc_rev = soc_lcd_rev;
                let mut lcd = shared.lcd.lock().unwrap();
                lcd.pixels.copy_from_slice(&rr.m.soc.lcd.fb);
                lcd.rev += 1;
                let rev = lcd.rev;
                drop(lcd);
                shared.lcd_rev.store(rev, Ordering::Release);
                new_lcd_rev = Some(rev);
            }
            let mut v = shared.view.lock().unwrap();
            if let Some(rev) = new_lcd_rev {
                v.lcd_rev = rev;
            }
            // LEDs: the firmware multiplexes and PWMs them, so a short window aliases into flicker.
            // Measure over at least 25 ms of emulated time, scale as panel.rs does (a fully lit
            // FM-1 LED is on about one tick in eleven), and let them fade rather than blink off.
            let ms_now = rr.m.soc.periph.ms();
            if ms_now < led_ms || ms_now >= led_ms + LED_WINDOW_MS {
                led_ms = ms_now;
                let raw = if rr.m.board() == Board::Smk37 {
                    let mut l = [0.0; 41];
                    l[..fm1_soc::smk::NLED].copy_from_slice(&rr.m.soc.periph.smk.led_take());
                    l
                } else {
                    rr.m.soc.periph.led_take().map(|f| (f / 0.08).min(1.0))
                };
                for (l, b) in v.leds.iter_mut().zip(raw) {
                    *l = if b >= *l { b } else { *l * 0.45 + b * 0.55 };
                }
            }
            v.pads = rr.m.soc.periph.smk.pads;
            v.peak = std::mem::take(&mut peak);
            v.clip = std::mem::take(&mut clip);
            v.midi_dropped = rr.m.soc.periph.midi_dropped();
            v.midi_in = midi_in;
            v.lead = lead;
            if rr.m.halted && v.state != RunState::Halted {
                v.state = RunState::Halted;
                v.error = rr.m.fault.clone();
            }
            // Collect non-hot-path firmware diagnostics (second core, internal resets, console, unknown MMIO).
            if !rr.cpu1_logged && rr.m.cpu1.is_some() {
                rr.cpu1_logged = true;
                push_diag(&shared, &mut v, format!("firmware released second core (cpu1) at {} ms", ms_now));
            }
            if rr.m.firmware_resets > rr.resets_seen {
                rr.resets_seen = rr.m.firmware_resets;
                push_diag(&shared, &mut v, format!("firmware reset itself internally (#{}, {} ms, pc {:#010x}, {} MHz)",
                    rr.resets_seen, ms_now, rr.m.cpu.pc, rr.m.mhz()));
            }
            if rr.m.soc.console.len() > rr.console_seen {
                let tail = &rr.m.soc.console[rr.console_seen..];
                rr.console_seen = rr.m.soc.console.len();
                let msg = String::from_utf8_lossy(tail).replace(['\r', '\n'], " ").trim().to_owned();
                if !msg.is_empty() {
                    let short: String = msg.chars().take(180).collect();
                    push_diag(&shared, &mut v, format!("fw console: {short}"));
                }
            }
            let unk_len = rr.m.soc.unknown_accesses().len();
            if unk_len > rr.unknown_seen {
                let first_new = rr.unknown_seen == 0;
                let sample: Vec<String> = rr.m.soc.unknown_accesses()[rr.unknown_seen..]
                    .iter()
                    .take(4)
                    .map(|u| format!("{}{:#x}={:#x}", if u.is_write { "W@" } else { "R@" }, u.addr, u.value))
                    .collect();
                rr.unknown_seen = unk_len;
                if first_new && !sample.is_empty() {
                    push_diag(&shared, &mut v, format!("unknown MMIO ({} accesses, sample: {})", unk_len, sample.join(", ")));
                }
            }
            if now.duration_since(speed_t) >= Duration::from_secs(1) {
                let ms = rr.m.soc.periph.ms();
                let starved = shared.clock.starved.load(Ordering::Relaxed);
                if speed_ms > 0 && busy > Duration::ZERO {
                    v.speed = ms.saturating_sub(speed_ms) as f32 / busy.as_secs_f32() / 1000.0;
                    v.late = starved - starved0;
                }
                v.starved = starved;
                starved0 = starved;
                (speed_t, speed_ms, busy) = (now, ms.max(1), Duration::ZERO);
                rr.seconds += 1;
                let slow = rr.seconds > WARMUP_SECONDS && v.speed > 0.0 && v.speed < SLOW_SPEED && v.late > 0;
                rr.slow = if slow { rr.slow + 1 } else { 0 };
                let lower = CLOCKS.iter().copied().find(|&c| c < rr.m.mhz());
                if rr.auto && rr.slow >= SLOW_SECONDS && !rr.m.soc.flash.busy() {
                    if let Some(mhz) = lower {
                        drop(v);
                        reclock(&shared, rr, mhz, pot, &mut due, &mut flash_revision);
                        let mut v = shared.view.lock().unwrap();
                        v.clock_steps += 1;
                        v.mhz = mhz;
                        (speed_t, speed_ms, busy) = (Instant::now(), 0, Duration::ZERO);
                    }
                }
            }
        }
    }
}

/// Boot the running firmware again at `mhz`, with what it has written to flash: the machine's
/// timers count its cycles, so a new clock means a new machine.
fn reclock(shared: &Shared, rr: &mut Running, mhz: u32, pot: u16, due: &mut VecDeque<Midi>, revision: &mut u64) {
    let sectors = rr.m.changed_sectors();
    let jit = rr.m.jit();
    match Machine::boot(&rr.pkg, &sectors, mhz, None, jit) {
        Ok(mut m) => {
            if m.board() == Board::Fm1 {
                m.soc.periph.apply(&Action::Adc { ch: 4, value: pot });
            }
            let rev = m.soc.flash.revision;
            rr.m = m;
            rr.seconds = 0;
            rr.slow = 0;
            rr.flash_rev_seen = rev;
            rr.published_rev = rev;
            rr.cpu1_logged = false;
            rr.console_seen = 0;
            rr.resets_seen = 0;
            rr.unknown_seen = 0;
            rr.lcd_soc_rev = u32::MAX;
            due.clear();
            // the restored sectors are the flash's state: publish them so nothing looks lost
            *revision += 1;
            shared.view.lock().unwrap().flash = Flash { boot_id: rr.boot_id, revision: *revision, sectors };
        }
        Err(e) => {
            let mut v = shared.view.lock().unwrap();
            v.error = e;
        }
    }
}

fn push_diag(shared: &Shared, v: &mut View, line: String) {
    if v.diagnostic.is_empty() {
        v.diagnostic = line;
    } else if v.diagnostic.len() < 1024 {
        v.diagnostic.push('\n');
        v.diagnostic.push_str(&line);
    }
    v.diag_rev += 1;
    shared.diag_pending.store(true, Ordering::Release);
}

fn current_flash(r: &Running, revision: u64) -> Flash {
    Flash { boot_id: r.boot_id, revision, sectors: r.m.changed_sectors() }
}

fn apply(r: &mut Option<Running>, a: Action) {
    if let Some(r) = r.as_mut() {
        r.m.soc.periph.apply(&a);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn idle_engine_keeps_the_ring_at_lead_with_silence() {
        let (engine, mut out) = Engine::start(512, || None);
        std::thread::sleep(Duration::from_millis(50));
        let mut buf = vec![1.0f32; 2 * 256];
        out.render(&mut buf, RATE, 1);
        assert!(buf.iter().all(|&s| s == 0.0));
        assert_eq!(engine.shared.clock.taken.load(Ordering::Relaxed), 256);
        assert_eq!(engine.shared.clock.starved.load(Ordering::Relaxed), 0);
        assert_eq!(engine.shared.view.lock().unwrap().state, RunState::Idle);
    }

    #[test]
    fn a_bad_package_fails_the_boot_and_keeps_its_sectors() {
        let (engine, _out) = Engine::start(512, || None);
        let sectors = vec![Sector { at: 0x1000, data: vec![7; 4096] }];
        engine.send(Cmd::Boot(Boot {
            pkg: Arc::new(vec![0; 960]), sectors: sectors.clone(), mhz: 240, auto_clock: true,
            syx: None, jit: false, id: 9,
        }));
        std::thread::sleep(Duration::from_millis(50));
        let v = engine.shared.view.lock().unwrap();
        assert_eq!((v.state, v.boot_id), (RunState::Failed, 9));
        assert!(!v.error.is_empty());
        assert_eq!(v.flash.sectors, sectors);
    }

    #[test]
    fn resampling_to_48k_takes_44k1_frames_per_second() {
        let (engine, mut out) = Engine::start(8192, || None);
        std::thread::sleep(Duration::from_millis(50));
        let mut buf = vec![0f32; 2 * 4800];
        for i in 0..10 {
            out.render(&mut buf, 48_000, 1 + i);
            std::thread::sleep(Duration::from_millis(5));
        }
        let taken = engine.shared.clock.taken.load(Ordering::Relaxed) as i64;
        assert!((taken - 44_100).abs() <= 2, "{taken}");
    }

    #[test]
    fn frame_at_extrapolates_from_the_last_take() {
        let c = AudioClock::default();
        c.taken.store(1000, Ordering::Relaxed);
        c.taken_ns.store(1_000_000_000, Ordering::Relaxed);
        assert_eq!(c.frame_at(2_000_000_000), 1000 + 44_100);
        assert_eq!(c.frame_at(500), 1000);
    }

    #[test]
    fn batched_render_handles_partial_starvation_and_resampling() {
        let (mut prod, cons) = rtrb::RingBuffer::<[f32; 2]>::new(16);
        let shared = Arc::new(Shared {
            view: Mutex::new(View::default()),
            lcd: Mutex::new(LcdFrame::default()),
            clock: AudioClock::default(),
            lead: AtomicUsize::new(8),
            lcd_rev: AtomicU64::new(0),
            diag_pending: AtomicBool::new(false),
            background: AtomicBool::new(false),
            quit: AtomicBool::new(false),
            throttle: AtomicU32::new(0),
        });
        let mut out = AudioOut { ring: cons, shared: shared.clone(), prev: [0.0; 2], next: [0.0; 2], pos: 1.0 };

        // Push 12 frames, consume 8, push 8 more so the ring wraps across the boundary.
        let chunk = prod.write_chunk_uninit(12).unwrap();
        chunk.fill_from_iter((0..12).map(|i| [i as f32, -(i as f32)]));
        let mut buf = vec![99.0f32; 2 * 8];
        out.render(&mut buf, RATE, 1);
        assert_eq!(buf[0], 0.0);
        assert_eq!(buf[1], 0.0);
        assert_eq!(buf[14], 7.0);
        assert_eq!(buf[15], -7.0);
        assert_eq!(shared.clock.starved.load(Ordering::Relaxed), 0);

        let chunk = prod.write_chunk_uninit(8).unwrap();
        chunk.fill_from_iter((12..20).map(|i| [i as f32, -(i as f32)]));
        // 12 frames remain in the ring (8..20); request 16 so the final 4 starve to 0.0.
        let mut buf2 = vec![99.0f32; 2 * 16];
        out.render(&mut buf2, RATE, 2);
        assert_eq!(buf2[0], 8.0);
        assert_eq!(buf2[22], 19.0);
        assert_eq!(buf2[23], -19.0);
        assert!(buf2[24..].iter().all(|&s| s == 0.0));
        assert_eq!(shared.clock.taken.load(Ordering::Relaxed), 20);
        assert_eq!(shared.clock.starved.load(Ordering::Relaxed), 4);
    }

    #[test]
    fn pin_prime_command_invokes_affinity_callback() {
        let (tx, rx) = mpsc::channel();
        let (engine, _out) = Engine::start_with_pin(
            512,
            || None,
            Some(Box::new(move |prime| {
                let _ = tx.send(prime);
            })),
        );
        engine.send(Cmd::PinPrime(true));
        engine.send(Cmd::PinPrime(false));
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)).unwrap(), true);
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)).unwrap(), false);
    }
}
