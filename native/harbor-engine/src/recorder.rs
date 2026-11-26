//! Recording: the firmware's output as it makes it (16-bit stereo at 44.1 kHz, before any
//! resampling for the device) into a WAV file.
//!
//! The engine thread only copies each chunk into a ring ([`Tap::push`]) and never waits on the
//! file; a writer thread of its own drains the ring to disk. The recording ends when the engine
//! lets go of its tap: the writer drains what is left, fills in the WAV header's sizes and
//! closes the file.

use std::fs::File;
use std::io::{self, BufWriter, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use crate::engine::RATE;

/// Two seconds of frames: the writer may stall that long (a slow flash write) before frames drop.
const RING: usize = 2 * RATE as usize;
const WAV_HEADER: usize = 44;
const AIFF_HEADER: usize = 54;

/// Output container / bit depth written by [`Recorder`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    Wav16,
    Wav24,
    Aiff16,
}

impl Format {
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => Self::Wav24,
            2 => Self::Aiff16,
            _ => Self::Wav16,
        }
    }

    fn bytes_per_frame(self) -> u64 {
        match self {
            Self::Wav16 | Self::Aiff16 => 4,
            Self::Wav24 => 6,
        }
    }

    fn max_frames(self) -> u64 {
        (u32::MAX as u64 - 46) / self.bytes_per_frame()
    }
}

/// The engine thread's end: frames in, never blocking.
pub struct Tap {
    ring: rtrb::Producer<[i16; 2]>,
    dropped: Arc<AtomicU64>,
}

impl Tap {
    /// Interleaved stereo samples, as the firmware made them.
    pub fn push(&mut self, samples: &[i16]) {
        let mut lost = 0;
        for f in samples.chunks_exact(2) {
            if self.ring.push([f[0], f[1]]).is_err() {
                lost += 1;
            }
        }
        if lost > 0 {
            self.dropped.fetch_add(lost, Ordering::Relaxed);
        }
    }
}

/// What a finished recording holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Summary {
    pub frames: u64,
    /// frames lost because the writer fell two seconds behind
    pub dropped: u64,
}

/// The app's end: how far the recording has got, and its end.
pub struct Recorder {
    frames: Arc<AtomicU64>,
    dropped: Arc<AtomicU64>,
    thread: Option<JoinHandle<io::Result<u64>>>,
}

impl Recorder {
    /// Start a 16-bit WAV in `file` (empty, writable and seekable). Give the [`Tap`] to the engine.
    pub fn start(file: File) -> io::Result<(Recorder, Tap)> {
        Self::start_with(file, Format::Wav16)
    }

    /// Start a recording in `file` using `format`. Give the [`Tap`] to the engine.
    pub fn start_with(file: File, format: Format) -> io::Result<(Recorder, Tap)> {
        let mut w = BufWriter::with_capacity(64 * 1024, file);
        write_header(&mut w, format, 0)?;
        let (prod, cons) = rtrb::RingBuffer::new(RING);
        let frames = Arc::new(AtomicU64::new(0));
        let dropped = Arc::new(AtomicU64::new(0));
        let f = frames.clone();
        let thread = std::thread::Builder::new()
            .name("harbor-recorder".into())
            .spawn(move || write(w, cons, f, format))?;
        Ok((Recorder { frames, dropped: dropped.clone(), thread: Some(thread) }, Tap { ring: prod, dropped }))
    }

    /// Frames written so far.
    pub fn frames(&self) -> u64 { self.frames.load(Ordering::Relaxed) }

    /// Wait for the writer to finish the file. Only returns once the engine has dropped the tap.
    pub fn finish(mut self) -> io::Result<Summary> {
        let frames = match self.thread.take().map(|t| t.join()) {
            Some(Ok(r)) => r?,
            _ => return Err(io::Error::other("the recording's writer failed")),
        };
        Ok(Summary { frames, dropped: self.dropped.load(Ordering::Relaxed) })
    }
}

fn write(mut w: BufWriter<File>, mut ring: rtrb::Consumer<[i16; 2]>, frames: Arc<AtomicU64>, format: Format) -> io::Result<u64> {
    let mut n = 0u64;
    let max_frames = format.max_frames();
    let mut bytes = Vec::with_capacity(16 * 1024);
    loop {
        // checked before draining, so whatever the engine pushed before letting go gets written
        let done = ring.is_abandoned();
        while let Ok(chunk) = ring.read_chunk(ring.slots().min(4096)) {
            if chunk.is_empty() {
                break;
            }
            let take = (chunk.len() as u64).min(max_frames - n) as usize;
            let (a, b) = chunk.as_slices();
            bytes.clear();
            for f in a.iter().chain(b).take(take) {
                match format {
                    Format::Wav16 => {
                        bytes.extend_from_slice(&f[0].to_le_bytes());
                        bytes.extend_from_slice(&f[1].to_le_bytes());
                    }
                    Format::Wav24 => {
                        let l = ((f[0] as i32) << 8).to_le_bytes();
                        let r = ((f[1] as i32) << 8).to_le_bytes();
                        bytes.extend_from_slice(&l[..3]);
                        bytes.extend_from_slice(&r[..3]);
                    }
                    Format::Aiff16 => {
                        bytes.extend_from_slice(&f[0].to_be_bytes());
                        bytes.extend_from_slice(&f[1].to_be_bytes());
                    }
                }
            }
            chunk.commit_all();
            w.write_all(&bytes)?;
            n += take as u64;
            frames.store(n, Ordering::Relaxed);
        }
        if done {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let mut file = w.into_inner().map_err(|e| e.into_error())?;
    file.seek(SeekFrom::Start(0))?;
    write_header(&mut file, format, n)?;
    file.flush()?;
    file.sync_all()?;
    Ok(n)
}

fn write_header<W: Write>(w: &mut W, format: Format, frames: u64) -> io::Result<()> {
    match format {
        Format::Wav16 => w.write_all(&wav_header(frames, 16)),
        Format::Wav24 => w.write_all(&wav_header(frames, 24)),
        Format::Aiff16 => w.write_all(&aiff_header(frames)),
    }
}

/// A canonical 44-byte WAV header for `frames` of 16- or 24-bit stereo at [`RATE`].
fn wav_header(frames: u64, bits: u16) -> [u8; WAV_HEADER] {
    let block = (bits / 8) * 2;
    let data = (frames * block as u64) as u32;
    let mut h = [0u8; WAV_HEADER];
    h[0..4].copy_from_slice(b"RIFF");
    h[4..8].copy_from_slice(&(36 + data).to_le_bytes());
    h[8..12].copy_from_slice(b"WAVE");
    h[12..16].copy_from_slice(b"fmt ");
    h[16..20].copy_from_slice(&16u32.to_le_bytes());
    h[20..22].copy_from_slice(&1u16.to_le_bytes()); // PCM
    h[22..24].copy_from_slice(&2u16.to_le_bytes());
    h[24..28].copy_from_slice(&RATE.to_le_bytes());
    h[28..32].copy_from_slice(&(RATE * block as u32).to_le_bytes());
    h[32..34].copy_from_slice(&block.to_le_bytes());
    h[34..36].copy_from_slice(&bits.to_le_bytes());
    h[36..40].copy_from_slice(b"data");
    h[40..44].copy_from_slice(&data.to_le_bytes());
    h
}

/// A canonical 54-byte AIFF header for `frames` of 16-bit big-endian stereo at [`RATE`].
fn aiff_header(frames: u64) -> [u8; AIFF_HEADER] {
    // 44100.0 in IEEE 754 80-bit extended float
    const RATE_F80: [u8; 10] = [0x40, 0x0E, 0xAC, 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    let data = (frames * 4) as u32;
    let mut h = [0u8; AIFF_HEADER];
    h[0..4].copy_from_slice(b"FORM");
    h[4..8].copy_from_slice(&(46 + data).to_be_bytes());
    h[8..12].copy_from_slice(b"AIFF");
    h[12..16].copy_from_slice(b"COMM");
    h[16..20].copy_from_slice(&18u32.to_be_bytes());
    h[20..22].copy_from_slice(&2u16.to_be_bytes());
    h[22..26].copy_from_slice(&(frames as u32).to_be_bytes());
    h[26..28].copy_from_slice(&16u16.to_be_bytes());
    h[28..38].copy_from_slice(&RATE_F80);
    h[38..42].copy_from_slice(b"SSND");
    h[42..46].copy_from_slice(&(8 + data).to_be_bytes());
    // offset (46..50) and blockSize (50..54) remain 0
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("harbor-{}-{name}.wav", std::process::id()))
    }

    #[test]
    fn writes_a_wav_with_its_sizes_filled_in() {
        let path = temp("wav");
        let file = File::options().read(true).write(true).create(true).truncate(true).open(&path).unwrap();
        let (rec, mut tap) = Recorder::start(file).unwrap();
        let samples: Vec<i16> = (0..2000).map(|i| i as i16 - 1000).collect();
        tap.push(&samples);
        tap.push(&samples[..4]);
        drop(tap);
        let s = rec.finish().unwrap();
        assert_eq!(s, Summary { frames: 1002, dropped: 0 });

        let b = std::fs::read(&path).unwrap();
        std::fs::remove_file(&path).ok();
        assert_eq!(b.len(), 44 + 1002 * 4);
        assert_eq!(&b[0..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(b[4..8].try_into().unwrap()), 36 + 1002 * 4);
        assert_eq!(u32::from_le_bytes(b[24..28].try_into().unwrap()), 44_100);
        assert_eq!(u32::from_le_bytes(b[40..44].try_into().unwrap()), 1002 * 4);
        assert_eq!(i16::from_le_bytes([b[44], b[45]]), -1000);
        assert_eq!(i16::from_le_bytes([b[46], b[47]]), -999);
    }

    #[test]
    fn a_full_ring_drops_frames_instead_of_waiting() {
        let (prod, _cons) = rtrb::RingBuffer::new(4);
        let dropped = Arc::new(AtomicU64::new(0));
        let mut tap = Tap { ring: prod, dropped: dropped.clone() };
        tap.push(&[0; 12]);
        assert_eq!(dropped.load(Ordering::Relaxed), 2);
    }

    #[test]
    fn writes_24bit_wav_and_aiff_headers() {
        let path24 = temp("wav24");
        let f24 = File::options().read(true).write(true).create(true).truncate(true).open(&path24).unwrap();
        let (rec24, mut tap24) = Recorder::start_with(f24, Format::Wav24).unwrap();
        tap24.push(&[0x1234, -0x1000]);
        drop(tap24);
        assert_eq!(rec24.finish().unwrap().frames, 1);
        let b24 = std::fs::read(&path24).unwrap();
        std::fs::remove_file(&path24).ok();
        assert_eq!(b24.len(), 44 + 6);
        assert_eq!(u16::from_le_bytes([b24[34], b24[35]]), 24);
        assert_eq!(&b24[44..47], &[0x00, 0x34, 0x12]);

        let path_aiff = temp("aiff");
        let fa = File::options().read(true).write(true).create(true).truncate(true).open(&path_aiff).unwrap();
        let (reca, mut tapa) = Recorder::start_with(fa, Format::Aiff16).unwrap();
        tapa.push(&[0x1234, -0x1000]);
        drop(tapa);
        assert_eq!(reca.finish().unwrap().frames, 1);
        let ba = std::fs::read(&path_aiff).unwrap();
        std::fs::remove_file(&path_aiff).ok();
        assert_eq!(ba.len(), 54 + 4);
        assert_eq!(&ba[0..4], b"FORM");
        assert_eq!(&ba[8..12], b"AIFF");
        assert_eq!(&ba[38..42], b"SSND");
        assert_eq!(i16::from_be_bytes([ba[54], ba[55]]), 0x1234);
    }
}
