//! MIDI from one source, made safe to merge with others into the FM-1's single MIDI IN jack:
//! every message leaves complete and with its own status byte (no running status across
//! sources), SysEx leaves whole, real-time bytes leave at once, a channel remap moves channel
//! messages, and the notes a source holds are tracked so they can be released when it goes.
//!
//! Before channel remap, messages that match a device's control mappings are intercepted and
//! turned into front-panel actions (`Enc`, `Pot`, `Key`) so controllers whose knobs send arbitrary
//! CCs (such as the Novation Launchkey Mini MK3) work with every firmware.

/// The longest SysEx passed on; a longer one is dropped (the jack's queue holds about a second).
const MAX_SYSEX: usize = 3125;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TriggerKind {
    Cc = 0,
    Note = 1,
}

/// A MIDI message pattern from a controller (matched before per-source channel remap).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Trigger {
    pub kind: TriggerKind,
    pub number: u8,
    /// `None`: match any MIDI channel (0..15); `Some(ch)`: match only `ch`.
    pub channel: Option<u8>,
}

impl Trigger {
    pub fn matches(&self, status: u8, data1: u8) -> bool {
        if (data1 & 0x7f) != (self.number & 0x7f) {
            return false;
        }
        let ch = status & 0x0f;
        if self.channel.is_some_and(|c| c != ch) {
            return false;
        }
        match self.kind {
            TriggerKind::Cc => (status & 0xf0) == 0xb0,
            TriggerKind::Note => matches!(status & 0xf0, 0x80 | 0x90),
        }
    }
}

/// How continuous MIDI CC knobs are interpreted when driving an FM-1 encoder.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum KnobMode {
    /// Standard 0..127 pot: value changes (`current - previous`) become encoder clicks.
    #[default]
    Absolute = 0,
    /// Endless relative encoder: 1..63 = +1..+63, 127..65 = -1..-63.
    RelativeTwosComp = 1,
    /// Endless relative encoder: 65..127 = +1..+63, 63..0 = -1..-64.
    RelativeBinaryOffset = 2,
}

impl KnobMode {
    pub fn from_i32(v: i32) -> Self {
        match v {
            1 => KnobMode::RelativeTwosComp,
            2 => KnobMode::RelativeBinaryOffset,
            _ => KnobMode::Absolute,
        }
    }
}

/// How many CC units of an absolute 0..127 pot make one encoder detent click.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Sensitivity {
    /// 4 CC steps per click (~32 clicks across a full sweep).
    Fine = 0,
    /// 2 CC steps per click (~64 clicks across a full sweep).
    #[default]
    Normal = 1,
    /// 1 CC step per click (127 clicks across a full sweep).
    Fast = 2,
}

impl Sensitivity {
    pub fn from_i32(v: i32) -> Self {
        match v {
            0 => Sensitivity::Fine,
            2 => Sensitivity::Fast,
            _ => Sensitivity::Normal,
        }
    }

    pub fn step(self) -> i32 {
        match self {
            Sensitivity::Fine => 4,
            Sensitivity::Normal => 2,
            Sensitivity::Fast => 1,
        }
    }
}

/// What a mapped MIDI control drives on the FM-1 front panel.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ControlTarget {
    /// Continuous knob driving encoder `0..=6`.
    Enc(u8),
    /// Button or pad stepping encoder `0..=6` by `clicks` (typically -1 or +1).
    EncStep { enc: u8, clicks: i32 },
    /// Continuous knob driving the absolute `MASTER` pot (`0..=1023`).
    Pot,
    /// Button or pad nudging the `MASTER` pot by `delta` (e.g. -32 or +32).
    PotStep(i16),
    /// Button or pad pressing momentary panel button `0..=13`.
    Button(u8),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MappingRule {
    pub trigger: Trigger,
    pub target: ControlTarget,
}

/// Front-panel action produced by a mapped MIDI message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PanelAction {
    Enc { enc: u8, clicks: i32 },
    Pot(u16),
    PotDelta(i16),
    Key { id: u8, down: bool },
}

pub struct MidiParser {
    /// Move all channel messages to this channel (0..15).
    pub remap: Option<u8>,
    pub rules: Vec<MappingRule>,
    pub knob_mode: KnobMode,
    pub sensitivity: Sensitivity,
    status: u8,
    data: [u8; 2],
    have: usize,
    sysex: Option<Vec<u8>>,
    /// notes held after the remap, per channel
    held: [u128; 16],
    /// FM-1 panel buttons (0..13) currently held down by this MIDI source
    held_buttons: u16,
    /// last seen CC value (0..127) per CC number, for delta tracking and rising-edge CC buttons
    last_cc: [Option<u8>; 128],
    /// sub-detent CC delta remainder per CC number
    cc_rem: [i8; 128],
}

impl Default for MidiParser {
    fn default() -> Self {
        MidiParser {
            remap: None,
            rules: Vec::new(),
            knob_mode: KnobMode::Absolute,
            sensitivity: Sensitivity::Normal,
            status: 0,
            data: [0; 2],
            have: 0,
            sysex: None,
            held: [0; 16],
            held_buttons: 0,
            last_cc: [None; 128],
            cc_rem: [0; 128],
        }
    }
}

/// Data bytes a status byte takes (None: SysEx start or a byte that carries none).
fn data_len(status: u8) -> usize {
    match status {
        0x80..=0xbf | 0xe0..=0xef | 0xf2 => 2,
        0xc0..=0xdf | 0xf1 | 0xf3 => 1,
        _ => 0,
    }
}

impl MidiParser {
    pub fn new(remap: Option<u8>) -> Self { MidiParser { remap, ..Default::default() } }

    /// Replace this source's control mappings and knob behaviour.
    pub fn set_mapping(&mut self, rules: Vec<MappingRule>, knob_mode: KnobMode, sensitivity: Sensitivity) {
        self.rules = rules;
        self.knob_mode = knob_mode;
        self.sensitivity = sensitivity;
        self.last_cc = [None; 128];
        self.cc_rem = [0; 128];
    }

    /// Parse `bytes`, emitting complete messages. Returns true if any message other than
    /// real-time (clock, active sensing…) went out, for the activity indicator.
    pub fn feed(&mut self, bytes: &[u8], out: &mut impl FnMut(&[u8])) -> bool {
        let mut learned = None;
        self.feed_mapped(bytes, out, &mut |_| {}, &mut learned)
    }

    /// Parse `bytes`, intercepting mapped controls into `panel` before channel remap and sending
    /// remaining MIDI messages to `out`. Also records the first learnable CC or Note-On in `learned`.
    pub fn feed_mapped(
        &mut self,
        bytes: &[u8],
        out: &mut impl FnMut(&[u8]),
        panel: &mut impl FnMut(PanelAction),
        learned: &mut Option<(TriggerKind, u8, u8)>,
    ) -> bool {
        let mut active = false;
        for &b in bytes {
            if b >= 0xf8 {
                out(&[b]);
                continue;
            }
            if let Some(sx) = self.sysex.as_mut() {
                if b < 0x80 {
                    if sx.len() < MAX_SYSEX { sx.push(b); }
                    continue;
                }
                let mut sx = self.sysex.take().unwrap();
                if b == 0xf7 && sx.len() < MAX_SYSEX {
                    sx.push(0xf7);
                    out(&sx);
                    active = true;
                    continue;
                }
                // any other status ends an unterminated SysEx, which is dropped; fall through
            }
            if b >= 0x80 {
                match b {
                    0xf0 => {
                        self.sysex = Some(vec![0xf0]);
                        self.status = 0;
                    }
                    0xf7 => {} // a stray end of SysEx
                    0xf1..=0xf6 => {
                        self.status = 0; // system common cancels running status
                        if data_len(b) == 0 {
                            if b == 0xf6 { out(&[b]); active = true; }
                        } else {
                            self.status = b;
                            self.have = 0;
                        }
                    }
                    _ => {
                        self.status = b;
                        self.have = 0;
                    }
                }
                continue;
            }
            // a data byte
            if self.status == 0 {
                continue; // no status to go with it
            }
            self.data[self.have] = b;
            self.have += 1;
            if self.have == data_len(self.status) {
                self.have = 0;
                let raw_status = self.status;
                let d0 = self.data[0];
                let d1 = self.data[1];

                if learned.is_none() {
                    match raw_status & 0xf0 {
                        0xb0 => *learned = Some((TriggerKind::Cc, raw_status & 0x0f, d0 & 0x7f)),
                        0x90 if d1 > 0 => *learned = Some((TriggerKind::Note, raw_status & 0x0f, d0 & 0x7f)),
                        _ => {}
                    }
                }

                if self.apply_mapping(raw_status, d0, d1, panel) {
                    active = true;
                    continue;
                }

                let mut status = raw_status;
                if status < 0xf0 {
                    if let Some(ch) = self.remap { status = (status & 0xf0) | (ch & 0x0f); }
                }
                let msg = [status, d0, d1];
                let n = 1 + data_len(status);
                self.track(&msg[..n]);
                out(&msg[..n]);
                active = true;
                if self.status >= 0xf0 {
                    self.status = 0; // system common messages don't run
                }
            }
        }
        active
    }

    /// Check whether `(status, d0, d1)` matches any rule; if so, emit panel actions and consume it.
    fn apply_mapping(&mut self, status: u8, d0: u8, d1: u8, panel: &mut impl FnMut(PanelAction)) -> bool {
        if self.rules.is_empty() || status >= 0xf0 {
            return false;
        }
        let mut matched = false;
        let cc_idx = (d0 & 0x7f) as usize;
        let prev_cc = self.last_cc[cc_idx];

        for i in 0..self.rules.len() {
            let rule = self.rules[i];
            if !rule.trigger.matches(status, d0) {
                continue;
            }
            matched = true;
            match (rule.trigger.kind, rule.target) {
                (TriggerKind::Cc, ControlTarget::Pot) => {
                    match self.knob_mode {
                        KnobMode::Absolute => {
                            let v = ((d1 as u32 * 1023 + 63) / 127).min(1023) as u16;
                            panel(PanelAction::Pot(v));
                        }
                        KnobMode::RelativeTwosComp | KnobMode::RelativeBinaryOffset => {
                            let clicks = self.relative_clicks(d1);
                            if clicks != 0 {
                                panel(PanelAction::PotDelta((clicks * 16).clamp(-1023, 1023) as i16));
                            }
                        }
                    }
                }
                (TriggerKind::Cc, ControlTarget::Enc(enc)) => {
                    let clicks = match self.knob_mode {
                        KnobMode::Absolute => {
                            let step = self.sensitivity.step();
                            if let Some(prev) = prev_cc {
                                let total = (d1 as i32 - prev as i32) + self.cc_rem[cc_idx] as i32;
                                let c = total / step;
                                self.cc_rem[cc_idx] = (total % step) as i8;
                                c
                            } else {
                                // First CC establishes the physical knob's current position without jumping.
                                self.cc_rem[cc_idx] = 0;
                                0
                            }
                        }
                        KnobMode::RelativeTwosComp | KnobMode::RelativeBinaryOffset => self.relative_clicks(d1),
                    };
                    if clicks != 0 {
                        panel(PanelAction::Enc { enc, clicks });
                    }
                }
                (TriggerKind::Cc, ControlTarget::EncStep { enc, clicks }) => {
                    let was_high = prev_cc.is_some_and(|p| p >= 64);
                    if d1 >= 64 && !was_high {
                        panel(PanelAction::Enc { enc, clicks });
                    }
                }
                (TriggerKind::Cc, ControlTarget::PotStep(delta)) => {
                    let was_high = prev_cc.is_some_and(|p| p >= 64);
                    if d1 >= 64 && !was_high {
                        panel(PanelAction::PotDelta(delta));
                    }
                }
                (TriggerKind::Cc, ControlTarget::Button(id)) if id < 14 => {
                    let down = d1 >= 64;
                    let mask = 1u16 << id;
                    let was_down = (self.held_buttons & mask) != 0;
                    if down != was_down {
                        if down { self.held_buttons |= mask; } else { self.held_buttons &= !mask; }
                        panel(PanelAction::Key { id, down });
                    }
                }
                (TriggerKind::Note, ControlTarget::EncStep { enc, clicks }) => {
                    if (status & 0xf0) == 0x90 && d1 > 0 {
                        panel(PanelAction::Enc { enc, clicks });
                    }
                }
                (TriggerKind::Note, ControlTarget::PotStep(delta)) => {
                    if (status & 0xf0) == 0x90 && d1 > 0 {
                        panel(PanelAction::PotDelta(delta));
                    }
                }
                (TriggerKind::Note, ControlTarget::Button(id)) if id < 14 => {
                    let down = (status & 0xf0) == 0x90 && d1 > 0;
                    let mask = 1u16 << id;
                    let was_down = (self.held_buttons & mask) != 0;
                    if down != was_down {
                        if down { self.held_buttons |= mask; } else { self.held_buttons &= !mask; }
                        panel(PanelAction::Key { id, down });
                    }
                }
                _ => {}
            }
        }
        if matched && (status & 0xf0) == 0xb0 {
            self.last_cc[cc_idx] = Some(d1 & 0x7f);
        }
        matched
    }

    fn relative_clicks(&self, val: u8) -> i32 {
        let v = (val & 0x7f) as i32;
        match self.knob_mode {
            KnobMode::RelativeTwosComp => {
                if v == 0 || v == 64 { 0 } else if v < 64 { v } else { v - 128 }
            }
            KnobMode::RelativeBinaryOffset => v - 64,
            KnobMode::Absolute => 0,
        }
    }

    fn track(&mut self, msg: &[u8]) {
        let ch = (msg[0] & 0x0f) as usize;
        match msg[0] & 0xf0 {
            0x90 if msg[2] > 0 => self.held[ch] |= 1u128 << msg[1],
            0x80 | 0x90 => self.held[ch] &= !(1u128 << msg[1]),
            0xb0 if msg[1] == 123 || msg[1] == 120 => self.held[ch] = 0,
            _ => {}
        }
    }

    /// Note-offs for every note this source still holds (it disconnected, or Panic).
    pub fn release_all(&mut self, out: &mut impl FnMut(&[u8])) {
        self.release_all_with_panel(out, &mut |_| {});
    }

    /// Release held MIDI notes and any FM-1 panel buttons held by this source.
    pub fn release_all_with_panel(&mut self, out: &mut impl FnMut(&[u8]), panel: &mut impl FnMut(PanelAction)) {
        for ch in 0..16 {
            let mut notes = std::mem::take(&mut self.held[ch]);
            while notes != 0 {
                let n = notes.trailing_zeros() as u8;
                notes &= notes - 1;
                out(&[0x80 | ch as u8, n, 0]);
            }
        }
        let mut btns = std::mem::take(&mut self.held_buttons);
        while btns != 0 {
            let id = btns.trailing_zeros() as u8;
            btns &= btns - 1;
            panel(PanelAction::Key { id, down: false });
        }
        self.status = 0;
        self.have = 0;
        self.sysex = None;
    }
}

/// All notes off and all sound off on every channel: the Panic button.
pub fn panic_messages() -> Vec<[u8; 3]> {
    (0..16u8).flat_map(|ch| [[0xb0 | ch, 123, 0], [0xb0 | ch, 120, 0]]).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(p: &mut MidiParser, bytes: &[u8]) -> Vec<Vec<u8>> {
        let mut v = Vec::new();
        p.feed(bytes, &mut |m| v.push(m.to_vec()));
        v
    }

    #[test]
    fn running_status_leaves_as_full_messages() {
        let mut p = MidiParser::new(None);
        assert_eq!(run(&mut p, &[0x90, 60, 100, 62, 100]), vec![vec![0x90, 60, 100], vec![0x90, 62, 100]]);
        assert_eq!(run(&mut p, &[0xc0, 5, 6]), vec![vec![0xc0, 5], vec![0xc0, 6]]);
    }

    #[test]
    fn messages_split_across_feeds_and_realtime_in_the_middle() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[0x90, 60]).is_empty());
        assert_eq!(run(&mut p, &[0xf8, 100]), vec![vec![0xf8], vec![0x90, 60, 100]]);
    }

    #[test]
    fn clock_only_traffic_is_not_activity() {
        let mut p = MidiParser::new(None);
        assert!(!p.feed(&[0xf8, 0xf8, 0xfe], &mut |_| {}));
        assert!(p.feed(&[0xb0, 1, 64], &mut |_| {}));
    }

    #[test]
    fn remap_moves_channel_messages_only() {
        let mut p = MidiParser::new(Some(1));
        assert_eq!(run(&mut p, &[0x95, 60, 1, 0xf2, 1, 2]), vec![vec![0x91, 60, 1], vec![0xf2, 1, 2]]);
    }

    #[test]
    fn sysex_leaves_whole_and_unterminated_sysex_is_dropped() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[0xf0, 1, 2]).is_empty());
        assert_eq!(run(&mut p, &[0xf8, 3, 0xf7]), vec![vec![0xf8], vec![0xf0, 1, 2, 3, 0xf7]]);
        assert_eq!(run(&mut p, &[0xf0, 1, 0x90, 60, 1]), vec![vec![0x90, 60, 1]]);
    }

    #[test]
    fn stray_data_without_status_is_ignored() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[60, 100, 0xf2, 1, 2, 3]).len() == 1);
    }

    #[test]
    fn release_all_frees_exactly_the_held_notes() {
        let mut p = MidiParser::new(None);
        run(&mut p, &[0x90, 60, 100, 0x90, 64, 100, 0x90, 60, 0, 0x93, 127, 1]);
        let mut offs = Vec::new();
        p.release_all(&mut |m| offs.push(m.to_vec()));
        assert_eq!(offs, vec![vec![0x80, 64, 0], vec![0x83, 127, 0]]);
        let mut again = 0;
        p.release_all(&mut |_| again += 1);
        assert_eq!(again, 0);
    }

    #[test]
    fn launchkey_absolute_cc_drives_encoders_and_master_and_is_consumed() {
        let mut p = MidiParser::new(Some(1));
        p.set_mapping(
            vec![
                MappingRule {
                    trigger: Trigger { kind: TriggerKind::Cc, number: 21, channel: None },
                    target: ControlTarget::Enc(2), // KNOB 1
                },
                MappingRule {
                    trigger: Trigger { kind: TriggerKind::Cc, number: 28, channel: None },
                    target: ControlTarget::Pot, // MASTER
                },
            ],
            KnobMode::Absolute,
            Sensitivity::Normal, // 2 CC steps per click
        );
        let mut uart = Vec::new();
        let mut actions = Vec::new();
        let mut learned = None;

        // First CC on knob 21 sets initial position (60) without jumping; next CCs (64, 58) emit +2 and -3 clicks.
        // CC 28 (value 127) sets MASTER to 1023. None of these leak into the emulated UART.
        assert!(p.feed_mapped(
            &[0xb0, 21, 60, 0xb0, 21, 64, 0xb0, 21, 58, 0xb5, 28, 127],
            &mut |m| uart.push(m.to_vec()),
            &mut |a| actions.push(a),
            &mut learned,
        ));
        assert!(uart.is_empty());
        assert_eq!(
            actions,
            vec![
                PanelAction::Enc { enc: 2, clicks: 2 },
                PanelAction::Enc { enc: 2, clicks: -3 },
                PanelAction::Pot(1023),
            ],
        );
        assert_eq!(learned, Some((TriggerKind::Cc, 0, 21)));
    }

    #[test]
    fn channel_specific_pad_note_controls_button_or_step_without_blocking_keyboard_channel() {
        let mut p = MidiParser::new(Some(1)); // keyboard remapped to channel 2 (index 1)
        p.set_mapping(
            vec![
                // Ch 10 (index 9) pad Note 36 -> PLAY button (id 12)
                MappingRule {
                    trigger: Trigger { kind: TriggerKind::Note, number: 36, channel: Some(9) },
                    target: ControlTarget::Button(12),
                },
                // Ch 10 (index 9) pad Note 37 -> PRESETS (enc 6) step +1
                MappingRule {
                    trigger: Trigger { kind: TriggerKind::Note, number: 37, channel: Some(9) },
                    target: ControlTarget::EncStep { enc: 6, clicks: 1 },
                },
            ],
            KnobMode::Absolute,
            Sensitivity::Normal,
        );
        let mut uart = Vec::new();
        let mut actions = Vec::new();
        let mut learned = None;

        // Ch 1 Note 36 (piano key) passes through remapped to Ch 2; Ch 10 Note 36 & 37 drive panel controls.
        p.feed_mapped(
            &[0x90, 36, 100, 0x99, 36, 110, 0x99, 37, 90, 0x89, 37, 0],
            &mut |m| uart.push(m.to_vec()),
            &mut |a| actions.push(a),
            &mut learned,
        );
        assert_eq!(uart, vec![vec![0x91, 36, 100]]);
        assert_eq!(
            actions,
            vec![
                PanelAction::Key { id: 12, down: true },
                PanelAction::Enc { enc: 6, clicks: 1 },
            ],
        );

        // Disconnecting releases both the held Ch 2 note and the held PLAY button.
        uart.clear();
        actions.clear();
        p.release_all_with_panel(&mut |m| uart.push(m.to_vec()), &mut |a| actions.push(a));
        assert_eq!(uart, vec![vec![0x81, 36, 0]]);
        assert_eq!(actions, vec![PanelAction::Key { id: 12, down: false }]);
    }
}
