#![forbid(unsafe_code)]

use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

pub const MIN_ROWS: u16 = 2;
pub const MAX_ROWS: u16 = 200;
pub const MIN_COLUMNS: u16 = 2;
pub const MAX_COLUMNS: u16 = 400;
pub const MAX_GRAPHEME_CODEPOINTS: usize = 16;
pub const DAMAGE_PROTOCOL_VERSION: u32 = 3;
pub const DAMAGE_HEADER_SIZE: usize = 32;
pub const DAMAGE_CELL_SIZE: usize = 76;
pub const MAX_DAMAGE_BYTES: usize =
    DAMAGE_HEADER_SIZE + MAX_ROWS as usize * MAX_COLUMNS as usize * DAMAGE_CELL_SIZE;
const DAMAGE_MAGIC: u32 = u32::from_le_bytes(*b"ATRM");
const MAX_CSI_PARAMETERS: usize = 16;
const MAX_STRING_BYTES: usize = 8 * 1024;
const DEFAULT_FOREGROUND: u32 = 7;
const DEFAULT_BACKGROUND: u32 = 0;
const DIRECT_COLOR_FLAG: u32 = 1 << 24;
const FLAG_CURSOR_VISIBLE: u32 = 1;
const FLAG_APPLICATION_CURSOR: u32 = 1 << 1;
const FLAG_APPLICATION_KEYPAD: u32 = 1 << 2;
const FLAG_BRACKETED_PASTE: u32 = 1 << 3;
const FLAG_NEW_LINE_MODE: u32 = 1 << 4;
const FLAG_BACKARROW_KEY: u32 = 1 << 5;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Cell {
    pub codepoint: u32,
    pub trailing_codepoints: [u32; MAX_GRAPHEME_CODEPOINTS - 1],
    pub foreground: u32,
    pub background: u32,
    pub attributes: u8,
    pub grapheme_len: u8,
    pub width: u8,
}

impl Cell {
    const fn blank() -> Self {
        Self {
            codepoint: 0x20,
            trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
            foreground: DEFAULT_FOREGROUND,
            background: DEFAULT_BACKGROUND,
            attributes: 0,
            grapheme_len: 1,
            width: 1,
        }
    }

    const fn continuation(foreground: u32, background: u32, attributes: u8) -> Self {
        Self {
            codepoint: 0,
            trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
            foreground,
            background,
            attributes,
            grapheme_len: 0,
            width: 0,
        }
    }

    fn codepoint(&self, index: usize) -> u32 {
        if index == 0 {
            self.codepoint
        } else {
            self.trailing_codepoints[index - 1]
        }
    }

    fn set_codepoint(&mut self, index: usize, codepoint: u32) {
        if index == 0 {
            self.codepoint = codepoint;
        } else {
            self.trailing_codepoints[index - 1] = codepoint;
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TerminalError {
    InvalidSize,
    OutputTooSmall,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ParserState {
    Ground,
    Escape,
    Csi,
    Osc,
    OscEscape,
    CharsetG0,
    CharsetG1,
    CharsetOther,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Charset {
    Ascii,
    DecSpecial,
}

#[derive(Debug)]
pub struct Terminal {
    rows: u16,
    columns: u16,
    cells: Vec<Cell>,
    inactive_cells: Vec<Cell>,
    cursor_row: u16,
    cursor_column: u16,
    wrap_pending: bool,
    saved_row: u16,
    saved_column: u16,
    scroll_top: u16,
    scroll_bottom: u16,
    inactive_cursor_row: u16,
    inactive_cursor_column: u16,
    inactive_wrap_pending: bool,
    inactive_saved_row: u16,
    inactive_saved_column: u16,
    inactive_scroll_top: u16,
    inactive_scroll_bottom: u16,
    alternate_active: bool,
    cursor_visible: bool,
    application_cursor: bool,
    application_keypad: bool,
    bracketed_paste: bool,
    new_line_mode: bool,
    backarrow_key: bool,
    insert_mode: bool,
    origin_mode: bool,
    auto_wrap: bool,
    tab_stops: [bool; MAX_COLUMNS as usize],
    last_printed: Option<Cell>,
    g0_charset: Charset,
    g1_charset: Charset,
    use_g1: bool,
    parser_state: ParserState,
    csi_parameters: [u16; MAX_CSI_PARAMETERS],
    csi_count: usize,
    csi_value: u16,
    csi_has_value: bool,
    csi_private: bool,
    string_bytes: usize,
    utf8_codepoint: u32,
    utf8_minimum: u32,
    utf8_remaining: u8,
    foreground: u32,
    background: u32,
    attributes: u8,
    dirty_start: u16,
    dirty_end: u16,
    revision: u64,
}

impl Terminal {
    pub fn new(rows: u16, columns: u16) -> Result<Self, TerminalError> {
        validate_size(rows, columns)?;
        let mut tab_stops = [false; MAX_COLUMNS as usize];
        for column in (8..MAX_COLUMNS as usize).step_by(8) {
            tab_stops[column] = true;
        }
        Ok(Self {
            rows,
            columns,
            cells: vec![Cell::blank(); usize::from(rows) * usize::from(columns)],
            inactive_cells: vec![Cell::blank(); usize::from(rows) * usize::from(columns)],
            cursor_row: 0,
            cursor_column: 0,
            wrap_pending: false,
            saved_row: 0,
            saved_column: 0,
            scroll_top: 0,
            scroll_bottom: rows - 1,
            inactive_cursor_row: 0,
            inactive_cursor_column: 0,
            inactive_wrap_pending: false,
            inactive_saved_row: 0,
            inactive_saved_column: 0,
            inactive_scroll_top: 0,
            inactive_scroll_bottom: rows - 1,
            alternate_active: false,
            cursor_visible: true,
            application_cursor: false,
            application_keypad: false,
            bracketed_paste: false,
            new_line_mode: false,
            backarrow_key: false,
            insert_mode: false,
            origin_mode: false,
            auto_wrap: true,
            tab_stops,
            last_printed: None,
            g0_charset: Charset::Ascii,
            g1_charset: Charset::Ascii,
            use_g1: false,
            parser_state: ParserState::Ground,
            csi_parameters: [0; MAX_CSI_PARAMETERS],
            csi_count: 0,
            csi_value: 0,
            csi_has_value: false,
            csi_private: false,
            string_bytes: 0,
            utf8_codepoint: 0,
            utf8_minimum: 0,
            utf8_remaining: 0,
            foreground: DEFAULT_FOREGROUND,
            background: DEFAULT_BACKGROUND,
            attributes: 0,
            dirty_start: 0,
            dirty_end: rows,
            revision: 1,
        })
    }

    pub const fn rows(&self) -> u16 {
        self.rows
    }

    pub const fn columns(&self) -> u16 {
        self.columns
    }

    pub const fn cursor(&self) -> (u16, u16) {
        (self.cursor_row, self.cursor_column)
    }

    pub fn cells(&self) -> &[Cell] {
        &self.cells
    }

    pub fn cell(&self, row: u16, column: u16) -> Option<Cell> {
        self.cells.get(self.index(row, column)).copied()
    }

    pub fn take_dirty_rows(&mut self) -> Option<(u16, u16)> {
        if self.dirty_start >= self.dirty_end {
            return None;
        }
        let dirty = (self.dirty_start, self.dirty_end);
        self.dirty_start = self.rows;
        self.dirty_end = 0;
        Some(dirty)
    }

    pub fn required_damage_bytes(&self) -> usize {
        DAMAGE_HEADER_SIZE
            + usize::from(self.dirty_end.saturating_sub(self.dirty_start))
                * usize::from(self.columns)
                * DAMAGE_CELL_SIZE
    }

    pub fn write_damage(&mut self, output: &mut [u8]) -> Result<usize, TerminalError> {
        self.write_damage_range(output, self.dirty_start, self.dirty_end)
    }

    pub fn write_full_damage(&mut self, output: &mut [u8]) -> Result<usize, TerminalError> {
        self.write_damage_range(output, 0, self.rows)
    }

    fn write_damage_range(
        &mut self,
        output: &mut [u8],
        dirty_start: u16,
        dirty_end: u16,
    ) -> Result<usize, TerminalError> {
        let required = DAMAGE_HEADER_SIZE
            + usize::from(dirty_end.saturating_sub(dirty_start))
                * usize::from(self.columns)
                * DAMAGE_CELL_SIZE;
        if output.len() < required {
            return Err(TerminalError::OutputTooSmall);
        }
        output[..required].fill(0);
        output[0..4].copy_from_slice(&DAMAGE_MAGIC.to_le_bytes());
        output[4..8].copy_from_slice(&DAMAGE_PROTOCOL_VERSION.to_le_bytes());
        output[8..10].copy_from_slice(&self.rows.to_le_bytes());
        output[10..12].copy_from_slice(&self.columns.to_le_bytes());
        output[12..14].copy_from_slice(&self.cursor_row.to_le_bytes());
        output[14..16].copy_from_slice(&self.cursor_column.to_le_bytes());
        output[16..18].copy_from_slice(&dirty_start.to_le_bytes());
        output[18..20].copy_from_slice(&dirty_end.to_le_bytes());
        let flags = if self.cursor_visible {
            FLAG_CURSOR_VISIBLE
        } else {
            0
        } | if self.application_cursor {
            FLAG_APPLICATION_CURSOR
        } else {
            0
        } | if self.application_keypad {
            FLAG_APPLICATION_KEYPAD
        } else {
            0
        } | if self.bracketed_paste {
            FLAG_BRACKETED_PASTE
        } else {
            0
        } | if self.new_line_mode {
            FLAG_NEW_LINE_MODE
        } else {
            0
        } | if self.backarrow_key {
            FLAG_BACKARROW_KEY
        } else {
            0
        };
        output[20..24].copy_from_slice(&flags.to_le_bytes());
        output[24..32].copy_from_slice(&self.revision.to_le_bytes());
        let mut offset = DAMAGE_HEADER_SIZE;
        for row in dirty_start..dirty_end {
            for column in 0..self.columns {
                let cell = self.cells[self.index(row, column)];
                for codepoint_index in 0..MAX_GRAPHEME_CODEPOINTS {
                    let start = offset + codepoint_index * 4;
                    output[start..start + 4]
                        .copy_from_slice(&cell.codepoint(codepoint_index).to_le_bytes());
                }
                output[offset + 64..offset + 68].copy_from_slice(&cell.foreground.to_le_bytes());
                output[offset + 68..offset + 72].copy_from_slice(&cell.background.to_le_bytes());
                output[offset + 72] = cell.attributes;
                output[offset + 73] = cell.width;
                output[offset + 74] = cell.grapheme_len;
                offset += DAMAGE_CELL_SIZE;
            }
        }
        self.dirty_start = self.rows;
        self.dirty_end = 0;
        Ok(required)
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            self.feed_byte(byte);
        }
    }

    pub fn resize(&mut self, rows: u16, columns: u16) -> Result<(), TerminalError> {
        validate_size(rows, columns)?;
        if rows == self.rows && columns == self.columns {
            return Ok(());
        }
        let (cells, source_row) = resize_cells(
            &self.cells,
            self.rows,
            self.columns,
            rows,
            columns,
            self.cursor_row,
        );
        let (inactive_cells, inactive_source_row) = resize_cells(
            &self.inactive_cells,
            self.rows,
            self.columns,
            rows,
            columns,
            self.inactive_cursor_row,
        );
        self.cells = cells;
        self.inactive_cells = inactive_cells;
        self.rows = rows;
        self.columns = columns;
        self.cursor_row = self.cursor_row.saturating_sub(source_row).min(rows - 1);
        self.cursor_column = self.cursor_column.min(columns - 1);
        self.wrap_pending = false;
        self.scroll_top = 0;
        self.scroll_bottom = rows - 1;
        self.inactive_cursor_row = self
            .inactive_cursor_row
            .saturating_sub(inactive_source_row)
            .min(rows - 1);
        self.inactive_cursor_column = self.inactive_cursor_column.min(columns - 1);
        self.inactive_wrap_pending = false;
        self.inactive_scroll_top = 0;
        self.inactive_scroll_bottom = rows - 1;
        self.dirty_start = 0;
        self.dirty_end = rows;
        self.revision = self.revision.saturating_add(1);
        Ok(())
    }

    fn feed_byte(&mut self, byte: u8) {
        let old_cursor = (self.cursor_row, self.cursor_column);
        match self.parser_state {
            ParserState::Ground => self.feed_ground(byte),
            ParserState::Escape => self.feed_escape(byte),
            ParserState::Csi => self.feed_csi(byte),
            ParserState::Osc => {
                if byte == 0x07 {
                    self.parser_state = ParserState::Ground;
                } else if byte == 0x1b {
                    self.parser_state = ParserState::OscEscape;
                } else if self.string_bytes < MAX_STRING_BYTES {
                    self.string_bytes += 1;
                }
            }
            ParserState::OscEscape => {
                self.parser_state = if byte == b'\\' {
                    ParserState::Ground
                } else {
                    ParserState::Osc
                };
            }
            ParserState::CharsetG0 => {
                self.g0_charset = charset_designation(byte);
                self.parser_state = ParserState::Ground;
            }
            ParserState::CharsetG1 => {
                self.g1_charset = charset_designation(byte);
                self.parser_state = ParserState::Ground;
            }
            ParserState::CharsetOther => {
                self.parser_state = ParserState::Ground;
            }
        }
        if old_cursor != (self.cursor_row, self.cursor_column) {
            self.mark_dirty(old_cursor.0);
            self.mark_dirty(self.cursor_row);
        }
    }

    fn feed_ground(&mut self, byte: u8) {
        if self.utf8_remaining != 0 {
            if byte & 0xc0 == 0x80 {
                self.utf8_codepoint = (self.utf8_codepoint << 6) | u32::from(byte & 0x3f);
                self.utf8_remaining -= 1;
                if self.utf8_remaining == 0 {
                    let codepoint = self.utf8_codepoint;
                    self.put_codepoint(if codepoint >= self.utf8_minimum {
                        char::from_u32(codepoint).map(u32::from).unwrap_or(0xfffd)
                    } else {
                        0xfffd
                    });
                }
                return;
            }
            self.utf8_remaining = 0;
            self.put_codepoint(0xfffd);
        }
        match byte {
            0x1b => {
                self.wrap_pending = false;
                self.parser_state = ParserState::Escape;
            }
            b'\r' => {
                self.wrap_pending = false;
                self.cursor_column = 0;
            }
            b'\n' | 0x0b | 0x0c => {
                self.wrap_pending = false;
                self.line_feed();
            }
            0x08 => {
                self.wrap_pending = false;
                self.cursor_column = self.cursor_column.saturating_sub(1);
            }
            b'\t' => {
                self.wrap_pending = false;
                self.tab_forward(1);
            }
            0x0e => self.use_g1 = true,
            0x0f => self.use_g1 = false,
            0x20..=0x7e => {
                let charset = if self.use_g1 {
                    self.g1_charset
                } else {
                    self.g0_charset
                };
                self.put_codepoint(map_charset(charset, byte));
            }
            0xc2..=0xdf => {
                self.utf8_codepoint = u32::from(byte & 0x1f);
                self.utf8_minimum = 0x80;
                self.utf8_remaining = 1;
            }
            0xe0..=0xef => {
                self.utf8_codepoint = u32::from(byte & 0x0f);
                self.utf8_minimum = 0x800;
                self.utf8_remaining = 2;
            }
            0xf0..=0xf4 => {
                self.utf8_codepoint = u32::from(byte & 0x07);
                self.utf8_minimum = 0x10000;
                self.utf8_remaining = 3;
            }
            _ if byte >= 0x80 => self.put_codepoint(0xfffd),
            _ => {}
        }
    }

    fn feed_escape(&mut self, byte: u8) {
        self.parser_state = ParserState::Ground;
        match byte {
            b'[' => {
                self.reset_csi();
                self.parser_state = ParserState::Csi;
            }
            b']' | b'P' | b'_' | b'^' => {
                self.string_bytes = 0;
                self.parser_state = ParserState::Osc;
            }
            b'(' => self.parser_state = ParserState::CharsetG0,
            b')' => self.parser_state = ParserState::CharsetG1,
            b'*' | b'+' => self.parser_state = ParserState::CharsetOther,
            b'7' => {
                self.saved_row = self.cursor_row;
                self.saved_column = self.cursor_column;
            }
            b'8' => {
                self.cursor_row = self.saved_row.min(self.rows - 1);
                self.cursor_column = self.saved_column.min(self.columns - 1);
            }
            b'=' => self.set_application_keypad(true),
            b'>' => self.set_application_keypad(false),
            b'D' => self.line_feed(),
            b'E' => {
                self.cursor_column = 0;
                self.line_feed();
            }
            b'H' => {
                self.tab_stops[usize::from(self.cursor_column)] = true;
            }
            b'M' => self.reverse_index(),
            b'c' => self.reset(),
            _ => {}
        }
    }

    fn feed_csi(&mut self, byte: u8) {
        match byte {
            b'0'..=b'9' => {
                self.csi_value = self
                    .csi_value
                    .saturating_mul(10)
                    .saturating_add(u16::from(byte - b'0'))
                    .min(9999);
                self.csi_has_value = true;
            }
            b';' => self.push_csi_parameter(),
            b'?' if self.csi_count == 0 && !self.csi_has_value => self.csi_private = true,
            0x40..=0x7e => {
                self.push_csi_parameter();
                self.execute_csi(byte);
                self.parser_state = ParserState::Ground;
            }
            0x20..=0x3f => {}
            _ => self.parser_state = ParserState::Ground,
        }
    }

    fn reset_csi(&mut self) {
        self.csi_parameters.fill(0);
        self.csi_count = 0;
        self.csi_value = 0;
        self.csi_has_value = false;
        self.csi_private = false;
    }

    fn push_csi_parameter(&mut self) {
        if self.csi_count < MAX_CSI_PARAMETERS {
            self.csi_parameters[self.csi_count] = if self.csi_has_value {
                self.csi_value
            } else {
                0
            };
            self.csi_count += 1;
        }
        self.csi_value = 0;
        self.csi_has_value = false;
    }

    fn parameter(&self, index: usize, default: u16) -> u16 {
        self.csi_parameters
            .get(index)
            .copied()
            .filter(|value| *value != 0)
            .unwrap_or(default)
    }

    fn execute_csi(&mut self, final_byte: u8) {
        if self.csi_private {
            self.execute_private_csi(final_byte);
            return;
        }
        self.wrap_pending = false;
        let (vertical_top, vertical_bottom) = self.vertical_bounds();
        match final_byte {
            b'@' => self.insert_characters(self.parameter(0, 1)),
            b'A' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_sub(self.parameter(0, 1))
                    .max(vertical_top);
            }
            b'B' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_add(self.parameter(0, 1))
                    .min(vertical_bottom);
            }
            b'C' => {
                self.cursor_column = self
                    .cursor_column
                    .saturating_add(self.parameter(0, 1))
                    .min(self.columns - 1);
            }
            b'D' => {
                self.cursor_column = self.cursor_column.saturating_sub(self.parameter(0, 1));
            }
            b'E' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_add(self.parameter(0, 1))
                    .min(vertical_bottom);
                self.cursor_column = 0;
            }
            b'F' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_sub(self.parameter(0, 1))
                    .max(vertical_top);
                self.cursor_column = 0;
            }
            b'G' => {
                self.cursor_column = self.parameter(0, 1).saturating_sub(1).min(self.columns - 1)
            }
            b'H' | b'f' => {
                self.cursor_row = vertical_top
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(vertical_bottom);
                self.cursor_column = self.parameter(1, 1).saturating_sub(1).min(self.columns - 1);
            }
            b'I' => self.tab_forward(self.parameter(0, 1)),
            b'J' => self.erase_display(self.csi_parameters[0]),
            b'K' => self.erase_line(self.csi_parameters[0]),
            b'L' => self.insert_lines(self.parameter(0, 1)),
            b'M' => self.delete_lines(self.parameter(0, 1)),
            b'P' => self.delete_characters(self.parameter(0, 1)),
            b'S' => self.scroll_up_by(self.parameter(0, 1)),
            b'T' => self.scroll_down_by(self.parameter(0, 1)),
            b'X' => self.erase_characters(self.parameter(0, 1)),
            b'Z' => self.tab_backward(self.parameter(0, 1)),
            b'`' => {
                self.cursor_column = self.parameter(0, 1).saturating_sub(1).min(self.columns - 1)
            }
            b'b' => self.repeat_last(self.parameter(0, 1)),
            b'd' => {
                self.cursor_row = vertical_top
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(vertical_bottom);
            }
            b'g' => self.clear_tab_stops(self.csi_parameters[0]),
            b'm' => self.select_graphics(),
            b'h' | b'l' => self.execute_ansi_mode(final_byte == b'h'),
            b'r' => {
                let top = self.parameter(0, 1).saturating_sub(1).min(self.rows - 1);
                let bottom = self
                    .parameter(1, self.rows)
                    .saturating_sub(1)
                    .min(self.rows - 1);
                if top < bottom {
                    self.scroll_top = top;
                    self.scroll_bottom = bottom;
                    self.cursor_row = if self.origin_mode { top } else { 0 };
                    self.cursor_column = 0;
                }
            }
            b's' => {
                self.saved_row = self.cursor_row;
                self.saved_column = self.cursor_column;
            }
            b'u' => {
                self.cursor_row = self.saved_row.min(self.rows - 1);
                self.cursor_column = self.saved_column.min(self.columns - 1);
            }
            _ => {}
        }
    }

    fn execute_private_csi(&mut self, final_byte: u8) {
        if final_byte != b'h' && final_byte != b'l' {
            return;
        }
        self.wrap_pending = false;
        let enabled = final_byte == b'h';
        for index in 0..self.csi_count {
            match self.csi_parameters[index] {
                1 => self.set_application_cursor(enabled),
                6 => self.set_origin_mode(enabled),
                7 => self.set_auto_wrap(enabled),
                25 => {
                    if self.cursor_visible != enabled {
                        self.cursor_visible = enabled;
                        self.mark_dirty(self.cursor_row);
                    }
                }
                47 => self.set_alternate_screen(enabled, false),
                66 => self.set_application_keypad(enabled),
                67 => self.set_backarrow_key(enabled),
                1047 | 1049 => self.set_alternate_screen(enabled, enabled),
                1048 if enabled => {
                    self.saved_row = self.cursor_row;
                    self.saved_column = self.cursor_column;
                }
                1048 => {
                    self.cursor_row = self.saved_row.min(self.rows - 1);
                    self.cursor_column = self.saved_column.min(self.columns - 1);
                }
                2004 => self.set_bracketed_paste(enabled),
                _ => {}
            }
        }
    }

    fn execute_ansi_mode(&mut self, enabled: bool) {
        for index in 0..self.csi_count {
            match self.csi_parameters[index] {
                4 if self.insert_mode != enabled => {
                    self.insert_mode = enabled;
                    self.mark_dirty(self.cursor_row);
                }
                20 if self.new_line_mode != enabled => {
                    self.new_line_mode = enabled;
                    self.mark_dirty(self.cursor_row);
                }
                _ => {}
            }
        }
    }

    fn set_application_cursor(&mut self, enabled: bool) {
        if self.application_cursor != enabled {
            self.application_cursor = enabled;
            self.mark_dirty(self.cursor_row);
        }
    }

    fn set_application_keypad(&mut self, enabled: bool) {
        if self.application_keypad != enabled {
            self.application_keypad = enabled;
            self.mark_dirty(self.cursor_row);
        }
    }

    fn set_bracketed_paste(&mut self, enabled: bool) {
        if self.bracketed_paste != enabled {
            self.bracketed_paste = enabled;
            self.mark_dirty(self.cursor_row);
        }
    }

    fn set_backarrow_key(&mut self, enabled: bool) {
        if self.backarrow_key != enabled {
            self.backarrow_key = enabled;
            self.mark_dirty(self.cursor_row);
        }
    }

    fn set_origin_mode(&mut self, enabled: bool) {
        self.origin_mode = enabled;
        self.cursor_row = if enabled { self.scroll_top } else { 0 };
        self.cursor_column = 0;
        self.wrap_pending = false;
    }

    fn set_auto_wrap(&mut self, enabled: bool) {
        self.auto_wrap = enabled;
        if !enabled {
            self.wrap_pending = false;
        }
    }

    fn set_alternate_screen(&mut self, enabled: bool, clear_on_entry: bool) {
        if enabled == self.alternate_active {
            return;
        }
        std::mem::swap(&mut self.cells, &mut self.inactive_cells);
        std::mem::swap(&mut self.cursor_row, &mut self.inactive_cursor_row);
        std::mem::swap(&mut self.cursor_column, &mut self.inactive_cursor_column);
        std::mem::swap(&mut self.wrap_pending, &mut self.inactive_wrap_pending);
        std::mem::swap(&mut self.saved_row, &mut self.inactive_saved_row);
        std::mem::swap(&mut self.saved_column, &mut self.inactive_saved_column);
        std::mem::swap(&mut self.scroll_top, &mut self.inactive_scroll_top);
        std::mem::swap(&mut self.scroll_bottom, &mut self.inactive_scroll_bottom);
        self.alternate_active = enabled;
        if enabled && clear_on_entry {
            self.cells.fill(Cell::blank());
            self.cursor_row = 0;
            self.cursor_column = 0;
            self.wrap_pending = false;
            self.saved_row = 0;
            self.saved_column = 0;
            self.scroll_top = 0;
            self.scroll_bottom = self.rows - 1;
        }
        self.mark_dirty_range(0, self.rows);
    }

    fn put_codepoint(&mut self, codepoint: u32) {
        if self.try_append_codepoint(codepoint) {
            return;
        }
        if self.auto_wrap && self.wrap_pending {
            self.cursor_column = 0;
            self.line_feed();
            self.wrap_pending = false;
        }
        let mut width = codepoint_width(codepoint).clamp(1, 2);
        if width == 2 && self.cursor_column + 1 >= self.columns {
            if self.auto_wrap {
                self.cursor_column = 0;
                self.line_feed();
            } else {
                width = 1;
            }
        }
        if self.insert_mode {
            self.insert_characters(u16::from(width));
        }
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, u16::from(width));
        let index = self.index(self.cursor_row, self.cursor_column);
        self.cells[index] = Cell {
            codepoint,
            trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
            foreground: self.foreground,
            background: self.background,
            attributes: self.attributes,
            grapheme_len: 1,
            width,
        };
        if width == 2 {
            self.cells[index + 1] =
                Cell::continuation(self.foreground, self.background, self.attributes);
        }
        self.last_printed = Some(self.cells[index]);
        self.mark_dirty(self.cursor_row);
        let last_column = self.cursor_column + u16::from(width) - 1;
        if self.auto_wrap && last_column + 1 >= self.columns {
            self.cursor_column = last_column;
            self.wrap_pending = true;
        } else {
            self.cursor_column = (last_column + 1).min(self.columns - 1);
            self.wrap_pending = false;
        }
    }

    fn try_append_codepoint(&mut self, codepoint: u32) -> bool {
        let Some((row, column)) = self.previous_grapheme_position() else {
            return false;
        };
        let index = self.index(row, column);
        let mut cell = self.cells[index];
        if cell.grapheme_len == 0 {
            return false;
        }
        let length = usize::from(cell.grapheme_len);
        let mut candidate = [0_u32; MAX_GRAPHEME_CODEPOINTS + 1];
        for (candidate_codepoint, source_index) in candidate[..length].iter_mut().zip(0..length) {
            *candidate_codepoint = cell.codepoint(source_index);
        }
        candidate[length] = codepoint;
        if !is_single_grapheme(&candidate[..=length]) {
            return false;
        }
        if length >= MAX_GRAPHEME_CODEPOINTS {
            cell.set_codepoint(MAX_GRAPHEME_CODEPOINTS - 1, 0xfffd);
            self.cells[index] = cell;
            self.mark_dirty(row);
            return true;
        }

        cell.set_codepoint(length, codepoint);
        cell.grapheme_len += 1;
        let old_width = cell.width.max(1);
        let new_width = grapheme_width(&candidate[..=length]).clamp(1, 2);
        if new_width > old_width && column + 1 < self.columns {
            self.clear_wide_intersections(row, column, 2);
            self.cells[index + 1] =
                Cell::continuation(cell.foreground, cell.background, cell.attributes);
            if self.wrap_pending {
                if column + 1 >= self.columns - 1 {
                    self.cursor_column = self.columns - 1;
                }
            } else if self.cursor_row == row && self.cursor_column == column + 1 {
                self.cursor_column = (column + 2).min(self.columns - 1);
                self.wrap_pending = self.auto_wrap && column + 2 >= self.columns;
            }
            cell.width = new_width;
        } else if new_width < old_width {
            if column + 1 < self.columns {
                self.cells[index + 1] = Cell::blank();
            }
            if self.cursor_row == row {
                self.cursor_column = column + 1;
                self.wrap_pending = false;
            }
            cell.width = new_width;
        }
        self.cells[index] = cell;
        self.last_printed = Some(cell);
        self.mark_dirty(row);
        true
    }

    fn previous_grapheme_position(&self) -> Option<(u16, u16)> {
        let mut column = if self.wrap_pending {
            self.cursor_column
        } else {
            self.cursor_column.checked_sub(1)?
        };
        if self.cells[self.index(self.cursor_row, column)].width == 0 {
            column = column.checked_sub(1)?;
        }
        (self.cells[self.index(self.cursor_row, column)].grapheme_len != 0)
            .then_some((self.cursor_row, column))
    }

    fn line_feed(&mut self) {
        if self.cursor_row == self.scroll_bottom {
            self.scroll_up();
        } else {
            self.cursor_row = (self.cursor_row + 1).min(self.rows - 1);
        }
    }

    fn scroll_up(&mut self) {
        self.scroll_up_by(1);
    }

    fn reverse_index(&mut self) {
        if self.cursor_row == self.scroll_top {
            self.scroll_down_by(1);
        } else {
            self.cursor_row = self.cursor_row.saturating_sub(1);
        }
    }

    fn insert_characters(&mut self, count: u16) {
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, 1);
        let row_start = self.index(self.cursor_row, 0);
        let start = row_start + usize::from(self.cursor_column);
        let end = row_start + usize::from(self.columns);
        let count = usize::from(count).min(end - start);
        if count == 0 {
            return;
        }
        self.cells.copy_within(start..end - count, start + count);
        self.cells[start..start + count].fill(Cell::blank());
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn delete_characters(&mut self, count: u16) {
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, count);
        let row_start = self.index(self.cursor_row, 0);
        let start = row_start + usize::from(self.cursor_column);
        let end = row_start + usize::from(self.columns);
        let count = usize::from(count).min(end - start);
        if count == 0 {
            return;
        }
        self.cells.copy_within(start + count..end, start);
        self.cells[end - count..end].fill(Cell::blank());
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn erase_characters(&mut self, count: u16) {
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, count);
        let start = self.index(self.cursor_row, self.cursor_column);
        let count = usize::from(count).min(usize::from(self.columns - self.cursor_column));
        if count == 0 {
            return;
        }
        self.cells[start..start + count].fill(Cell::blank());
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn insert_lines(&mut self, count: u16) {
        if !(self.scroll_top..=self.scroll_bottom).contains(&self.cursor_row) {
            return;
        }
        let count = count.min(self.scroll_bottom - self.cursor_row + 1);
        let columns = usize::from(self.columns);
        let start = self.index(self.cursor_row, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        self.cells.copy_within(start..end - offset, start + offset);
        self.cells[start..start + offset].fill(Cell::blank());
        self.mark_dirty_range(self.cursor_row, self.scroll_bottom + 1);
    }

    fn delete_lines(&mut self, count: u16) {
        if !(self.scroll_top..=self.scroll_bottom).contains(&self.cursor_row) {
            return;
        }
        let count = count.min(self.scroll_bottom - self.cursor_row + 1);
        let columns = usize::from(self.columns);
        let start = self.index(self.cursor_row, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        self.cells.copy_within(start + offset..end, start);
        self.cells[end - offset..end].fill(Cell::blank());
        self.mark_dirty_range(self.cursor_row, self.scroll_bottom + 1);
    }

    fn scroll_up_by(&mut self, count: u16) {
        let count = count.min(self.scroll_bottom - self.scroll_top + 1);
        let columns = usize::from(self.columns);
        let top = self.index(self.scroll_top, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        self.cells.copy_within(top + offset..end, top);
        self.cells[end - offset..end].fill(Cell::blank());
        self.mark_dirty_range(self.scroll_top, self.scroll_bottom + 1);
    }

    fn scroll_down_by(&mut self, count: u16) {
        let count = count.min(self.scroll_bottom - self.scroll_top + 1);
        let columns = usize::from(self.columns);
        let top = self.index(self.scroll_top, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        self.cells.copy_within(top..end - offset, top + offset);
        self.cells[top..top + offset].fill(Cell::blank());
        self.mark_dirty_range(self.scroll_top, self.scroll_bottom + 1);
    }

    fn tab_forward(&mut self, count: u16) {
        for _ in 0..count.min(self.columns) {
            let mut next = self.cursor_column + 1;
            while next < self.columns && !self.tab_stops[usize::from(next)] {
                next += 1;
            }
            self.cursor_column = next.min(self.columns - 1);
        }
    }

    fn tab_backward(&mut self, count: u16) {
        for _ in 0..count.min(self.columns) {
            let mut previous = self.cursor_column.saturating_sub(1);
            while previous > 0 && !self.tab_stops[usize::from(previous)] {
                previous -= 1;
            }
            self.cursor_column = previous;
        }
    }

    fn clear_tab_stops(&mut self, mode: u16) {
        match mode {
            0 => self.tab_stops[usize::from(self.cursor_column)] = false,
            3 => self.tab_stops.fill(false),
            _ => {}
        }
    }

    fn repeat_last(&mut self, count: u16) {
        if let Some(cell) = self.last_printed {
            for _ in 0..count {
                for index in 0..usize::from(cell.grapheme_len) {
                    self.put_codepoint(cell.codepoint(index));
                }
            }
        }
    }

    fn erase_display(&mut self, mode: u16) {
        match mode {
            0 => {
                let start = self.index(self.cursor_row, self.cursor_column);
                self.clear_wide_intersections(
                    self.cursor_row,
                    self.cursor_column,
                    self.columns - self.cursor_column,
                );
                self.cells[start..].fill(Cell::blank());
                for row in self.cursor_row..self.rows {
                    normalize_cell_row(&mut self.cells, self.columns, row);
                }
                self.mark_dirty_range(self.cursor_row, self.rows);
            }
            1 => {
                let end = self.index(self.cursor_row, self.cursor_column) + 1;
                self.clear_wide_intersections(self.cursor_row, 0, self.cursor_column + 1);
                self.cells[..end].fill(Cell::blank());
                for row in 0..=self.cursor_row {
                    normalize_cell_row(&mut self.cells, self.columns, row);
                }
                self.mark_dirty_range(0, self.cursor_row + 1);
            }
            2 | 3 => {
                self.cells.fill(Cell::blank());
                self.mark_dirty_range(0, self.rows);
            }
            _ => {}
        }
    }

    fn erase_line(&mut self, mode: u16) {
        let start = self.index(self.cursor_row, 0);
        let column = usize::from(self.cursor_column);
        let end = start + usize::from(self.columns);
        match mode {
            0 => {
                self.clear_wide_intersections(
                    self.cursor_row,
                    self.cursor_column,
                    self.columns - self.cursor_column,
                );
                self.cells[start + column..end].fill(Cell::blank());
            }
            1 => {
                self.clear_wide_intersections(self.cursor_row, 0, self.cursor_column + 1);
                self.cells[start..=start + column].fill(Cell::blank());
            }
            2 => self.cells[start..end].fill(Cell::blank()),
            _ => return,
        }
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn select_graphics(&mut self) {
        let parameter_count = self.csi_count.max(1);
        let mut index = 0;
        while index < parameter_count {
            match self.csi_parameters[index] {
                0 => {
                    self.foreground = DEFAULT_FOREGROUND;
                    self.background = DEFAULT_BACKGROUND;
                    self.attributes = 0;
                }
                1 => self.attributes |= 1,
                4 => self.attributes |= 2,
                7 => self.attributes |= 4,
                22 => self.attributes &= !1,
                24 => self.attributes &= !2,
                27 => self.attributes &= !4,
                30..=37 => self.foreground = u32::from(self.csi_parameters[index] - 30),
                39 => self.foreground = DEFAULT_FOREGROUND,
                40..=47 => self.background = u32::from(self.csi_parameters[index] - 40),
                49 => self.background = DEFAULT_BACKGROUND,
                38 | 48 => {
                    let target_foreground = self.csi_parameters[index] == 38;
                    let color = if index + 2 < parameter_count
                        && self.csi_parameters[index + 1] == 5
                    {
                        index += 2;
                        Some(u32::from(self.csi_parameters[index].min(255)))
                    } else if index + 4 < parameter_count && self.csi_parameters[index + 1] == 2 {
                        let red = self.csi_parameters[index + 2].min(255) as u8;
                        let green = self.csi_parameters[index + 3].min(255) as u8;
                        let blue = self.csi_parameters[index + 4].min(255) as u8;
                        index += 4;
                        Some(
                            DIRECT_COLOR_FLAG
                                | (u32::from(red) << 16)
                                | (u32::from(green) << 8)
                                | u32::from(blue),
                        )
                    } else {
                        None
                    };
                    if let Some(color) = color {
                        if target_foreground {
                            self.foreground = color;
                        } else {
                            self.background = color;
                        }
                    }
                }
                90..=97 => self.foreground = u32::from(self.csi_parameters[index] - 82),
                100..=107 => self.background = u32::from(self.csi_parameters[index] - 92),
                _ => {}
            }
            index += 1;
        }
    }

    fn reset(&mut self) {
        if self.alternate_active {
            self.set_alternate_screen(false, false);
        }
        self.cells.fill(Cell::blank());
        self.inactive_cells.fill(Cell::blank());
        self.cursor_row = 0;
        self.cursor_column = 0;
        self.wrap_pending = false;
        self.saved_row = 0;
        self.saved_column = 0;
        self.scroll_top = 0;
        self.scroll_bottom = self.rows - 1;
        self.inactive_cursor_row = 0;
        self.inactive_cursor_column = 0;
        self.inactive_wrap_pending = false;
        self.inactive_saved_row = 0;
        self.inactive_saved_column = 0;
        self.inactive_scroll_top = 0;
        self.inactive_scroll_bottom = self.rows - 1;
        self.cursor_visible = true;
        self.application_cursor = false;
        self.application_keypad = false;
        self.bracketed_paste = false;
        self.new_line_mode = false;
        self.backarrow_key = false;
        self.insert_mode = false;
        self.origin_mode = false;
        self.auto_wrap = true;
        self.tab_stops.fill(false);
        for column in (8..MAX_COLUMNS as usize).step_by(8) {
            self.tab_stops[column] = true;
        }
        self.last_printed = None;
        self.g0_charset = Charset::Ascii;
        self.g1_charset = Charset::Ascii;
        self.use_g1 = false;
        self.foreground = DEFAULT_FOREGROUND;
        self.background = DEFAULT_BACKGROUND;
        self.attributes = 0;
        self.utf8_remaining = 0;
        self.mark_dirty_range(0, self.rows);
    }

    fn index(&self, row: u16, column: u16) -> usize {
        usize::from(row) * usize::from(self.columns) + usize::from(column)
    }

    fn vertical_bounds(&self) -> (u16, u16) {
        if self.origin_mode {
            (self.scroll_top, self.scroll_bottom)
        } else {
            (0, self.rows - 1)
        }
    }

    fn clear_wide_intersections(&mut self, row: u16, column: u16, count: u16) {
        if count == 0 || row >= self.rows || column >= self.columns {
            return;
        }
        let start = column;
        let end = column.saturating_add(count).min(self.columns);
        if start > 0 {
            let previous = self.index(row, start - 1);
            if self.cells[previous].width == 2 {
                self.cells[previous] = Cell::blank();
                self.cells[previous + 1] = Cell::blank();
            }
        }
        for current in start..end {
            let index = self.index(row, current);
            if self.cells[index].width == 2 {
                self.cells[index] = Cell::blank();
                if current + 1 < self.columns {
                    self.cells[index + 1] = Cell::blank();
                }
            } else if self.cells[index].width == 0 {
                self.cells[index] = Cell::blank();
            }
        }
    }

    fn mark_dirty(&mut self, row: u16) {
        self.mark_dirty_range(row, row + 1);
    }

    fn mark_dirty_range(&mut self, start: u16, end: u16) {
        self.dirty_start = self.dirty_start.min(start);
        self.dirty_end = self.dirty_end.max(end);
        self.revision = self.revision.saturating_add(1);
    }
}

fn resize_cells(
    source: &[Cell],
    old_rows: u16,
    old_columns: u16,
    rows: u16,
    columns: u16,
    cursor_row: u16,
) -> (Vec<Cell>, u16) {
    let mut replacement = vec![Cell::blank(); usize::from(rows) * usize::from(columns)];
    let source_row = if rows < old_rows {
        cursor_row.saturating_sub(rows - 1)
    } else {
        0
    };
    let copied_rows = rows.min(old_rows - source_row);
    let copied_columns = columns.min(old_columns);
    for row in 0..copied_rows {
        let old = usize::from(source_row + row) * usize::from(old_columns);
        let new = usize::from(row) * usize::from(columns);
        replacement[new..new + usize::from(copied_columns)]
            .copy_from_slice(&source[old..old + usize::from(copied_columns)]);
    }
    for row in 0..rows {
        normalize_cell_row(&mut replacement, columns, row);
    }
    (replacement, source_row)
}

fn normalize_cell_row(cells: &mut [Cell], columns: u16, row: u16) {
    let row_start = usize::from(row) * usize::from(columns);
    let mut column = 0;
    while column < columns {
        let index = row_start + usize::from(column);
        match cells[index].width {
            2 if column + 1 < columns => {
                cells[index + 1] = Cell::continuation(
                    cells[index].foreground,
                    cells[index].background,
                    cells[index].attributes,
                );
                column += 2;
            }
            2 => {
                cells[index].width = 1;
                column += 1;
            }
            0 => {
                cells[index] = Cell::blank();
                column += 1;
            }
            _ => {
                cells[index].width = 1;
                column += 1;
            }
        }
    }
}

fn validate_size(rows: u16, columns: u16) -> Result<(), TerminalError> {
    if !(MIN_ROWS..=MAX_ROWS).contains(&rows) || !(MIN_COLUMNS..=MAX_COLUMNS).contains(&columns) {
        return Err(TerminalError::InvalidSize);
    }
    Ok(())
}

fn codepoint_width(codepoint: u32) -> u8 {
    grapheme_width(&[codepoint])
}

fn grapheme_width(codepoints: &[u32]) -> u8 {
    with_codepoints_as_str(codepoints, |text| text.width() as u8).unwrap_or(1)
}

fn is_single_grapheme(codepoints: &[u32]) -> bool {
    with_codepoints_as_str(codepoints, |text| text.graphemes(true).count() == 1).unwrap_or(false)
}

fn with_codepoints_as_str<T>(codepoints: &[u32], operation: impl FnOnce(&str) -> T) -> Option<T> {
    let mut utf8 = [0_u8; (MAX_GRAPHEME_CODEPOINTS + 1) * 4];
    let mut length = 0;
    for &codepoint in codepoints {
        let character = char::from_u32(codepoint)?;
        let encoded = character.encode_utf8(&mut utf8[length..]);
        length += encoded.len();
    }
    std::str::from_utf8(&utf8[..length]).ok().map(operation)
}

const fn charset_designation(byte: u8) -> Charset {
    if byte == b'0' {
        Charset::DecSpecial
    } else {
        Charset::Ascii
    }
}

const fn map_charset(charset: Charset, byte: u8) -> u32 {
    if !matches!(charset, Charset::DecSpecial) {
        return byte as u32;
    }
    match byte {
        b'`' => 0x25c6,
        b'a' => 0x2592,
        b'b' => 0x2409,
        b'c' => 0x240c,
        b'd' => 0x240d,
        b'e' => 0x240a,
        b'f' => 0x00b0,
        b'g' => 0x00b1,
        b'h' => 0x2424,
        b'i' => 0x240b,
        b'j' => 0x2518,
        b'k' => 0x2510,
        b'l' => 0x250c,
        b'm' => 0x2514,
        b'n' => 0x253c,
        b'o' => 0x23ba,
        b'p' => 0x23bb,
        b'q' => 0x2500,
        b'r' => 0x23bc,
        b's' => 0x23bd,
        b't' => 0x251c,
        b'u' => 0x2524,
        b'v' => 0x2534,
        b'w' => 0x252c,
        b'x' => 0x2502,
        b'y' => 0x2264,
        b'z' => 0x2265,
        b'{' => 0x03c0,
        b'|' => 0x2260,
        b'}' => 0x00a3,
        b'~' => 0x00b7,
        _ => byte as u32,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(terminal: &Terminal, row: u16) -> String {
        (0..terminal.columns())
            .map(|column| {
                let cell = terminal.cell(row, column).unwrap();
                if cell.grapheme_len == 0 {
                    ' '
                } else {
                    char::from_u32(cell.codepoint).unwrap_or('\u{fffd}')
                }
            })
            .collect()
    }

    fn grapheme(terminal: &Terminal, row: u16, column: u16) -> String {
        let cell = terminal.cell(row, column).unwrap();
        (0..usize::from(cell.grapheme_len))
            .map(|index| char::from_u32(cell.codepoint(index)).unwrap_or('\u{fffd}'))
            .collect()
    }

    #[test]
    fn printable_utf8_controls_and_wrapping_are_streaming() {
        let mut terminal = Terminal::new(3, 8).unwrap();
        terminal.feed(b"abc\r\n");
        terminal.feed(&[0xe2]);
        terminal.feed(&[0x98, 0x83, b'!']);
        assert_eq!(text(&terminal, 0), "abc     ");
        assert_eq!(text(&terminal, 1), "\u{2603}!      ");
        assert_eq!(terminal.cursor(), (1, 2));
    }

    #[test]
    fn unicode_graphemes_combine_and_occupy_bounded_terminal_columns() {
        let mut terminal = Terminal::new(3, 12).unwrap();
        terminal.feed("e\u{301}界🇺🇸👨‍👩‍👧‍👦".as_bytes());

        assert_eq!(grapheme(&terminal, 0, 0), "e\u{301}");
        assert_eq!(terminal.cell(0, 0).unwrap().width, 1);
        assert_eq!(grapheme(&terminal, 0, 1), "界");
        assert_eq!(terminal.cell(0, 1).unwrap().width, 2);
        assert_eq!(terminal.cell(0, 2).unwrap().width, 0);
        assert_eq!(grapheme(&terminal, 0, 3), "🇺🇸");
        assert_eq!(terminal.cell(0, 3).unwrap().width, 2);
        assert_eq!(grapheme(&terminal, 0, 5), "👨‍👩‍👧‍👦");
        assert_eq!(terminal.cell(0, 5).unwrap().width, 2);
        assert_eq!(terminal.cursor(), (0, 7));
    }

    #[test]
    fn unicode_graphemes_remain_streaming_across_feed_boundaries_and_damage() {
        let mut terminal = Terminal::new(2, 8).unwrap();
        terminal.feed(&[b'e', 0xcc]);
        terminal.feed(&[0x81, 0xf0, 0x9f]);
        terminal.feed(&[0x87, 0xba, 0xf0, 0x9f, 0x87, 0xb8]);
        assert_eq!(grapheme(&terminal, 0, 0), "e\u{301}");
        assert_eq!(grapheme(&terminal, 0, 1), "🇺🇸");

        let mut damage = vec![0_u8; MAX_DAMAGE_BYTES];
        terminal.write_full_damage(&mut damage).unwrap();
        let first = DAMAGE_HEADER_SIZE;
        assert_eq!(damage[first + 73], 1);
        assert_eq!(damage[first + 74], 2);
        assert_eq!(
            u32::from_le_bytes(damage[first + 4..first + 8].try_into().unwrap()),
            0x301
        );
        let flag = first + DAMAGE_CELL_SIZE;
        assert_eq!(damage[flag + 73], 2);
        assert_eq!(damage[flag + 74], 2);
    }

    #[test]
    fn edits_cannot_leave_orphaned_wide_cell_continuations() {
        let mut terminal = Terminal::new(2, 8).unwrap();
        terminal.feed("A界B".as_bytes());
        terminal.feed(b"\x1b[1;3HX");
        assert_eq!(text(&terminal, 0), "A XB    ");
        assert_eq!(terminal.cell(0, 1).unwrap().width, 1);
        assert_eq!(terminal.cell(0, 2).unwrap().width, 1);

        terminal.feed(b"\x1b[1;1H\x1b[2P");
        assert_eq!(text(&terminal, 0), "XB      ");
        assert!(
            terminal
                .cells()
                .iter()
                .all(|cell| cell.width != 0 || cell.grapheme_len == 0)
        );
    }

    #[test]
    fn csi_cursor_erase_color_and_scroll_are_bounded() {
        let mut terminal = Terminal::new(3, 5).unwrap();
        terminal.feed(b"one\r\ntwo\r\nthree\r\nfour");
        assert_eq!(text(&terminal, 1), "three");
        assert_eq!(text(&terminal, 2), "four ");
        terminal.feed(b"\x1b[1;2H\x1b[31;1mX\x1b[K");
        let cell = terminal.cell(0, 1).unwrap();
        assert_eq!(cell.codepoint, u32::from(b'X'));
        assert_eq!(cell.foreground, 1);
        assert_eq!(cell.attributes & 1, 1);
        assert_eq!(text(&terminal, 0), "tX   ");
    }

    #[test]
    fn indexed_and_rgb_sgr_colors_are_distinct_on_the_wire() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        terminal.feed(b"\x1b[38;5;196;48;5;25mA\x1b[38;2;255;0;0;48;2;0;95;175mB");
        let indexed = terminal.cell(0, 0).unwrap();
        assert_eq!(indexed.foreground, 196);
        assert_eq!(indexed.background, 25);
        let rgb = terminal.cell(0, 1).unwrap();
        assert_eq!(rgb.foreground, DIRECT_COLOR_FLAG | 0xff0000);
        assert_eq!(rgb.background, DIRECT_COLOR_FLAG | 0x005faf);

        let mut damage = [0_u8; 2048];
        terminal.write_full_damage(&mut damage).unwrap();
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + 64..DAMAGE_HEADER_SIZE + 68]
                    .try_into()
                    .unwrap()
            ),
            196
        );
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + 68..DAMAGE_HEADER_SIZE + 72]
                    .try_into()
                    .unwrap()
            ),
            25
        );
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + DAMAGE_CELL_SIZE + 64
                    ..DAMAGE_HEADER_SIZE + DAMAGE_CELL_SIZE + 68]
                    .try_into()
                    .unwrap()
            ),
            DIRECT_COLOR_FLAG | 0xff0000
        );
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + DAMAGE_CELL_SIZE + 68
                    ..DAMAGE_HEADER_SIZE + DAMAGE_CELL_SIZE + 72]
                    .try_into()
                    .unwrap()
            ),
            DIRECT_COLOR_FLAG | 0x005faf
        );
    }

    #[test]
    fn designated_dec_charsets_render_lines_without_leaking_reset_bytes() {
        let mut terminal = Terminal::new(2, 12).unwrap();
        terminal.feed(b"\x1b(0lqk\x1b(B ASCII");
        assert_eq!(text(&terminal, 0), "\u{250c}\u{2500}\u{2510} ASCII   ");

        terminal.feed(b"\r\x1b)0\x0elqk\x0f!");
        assert_eq!(text(&terminal, 0), "\u{250c}\u{2500}\u{2510}!ASCII   ");
    }

    #[test]
    fn resize_preserves_intersection_and_rejects_unbounded_grids() {
        let mut terminal = Terminal::new(2, 3).unwrap();
        terminal.feed(b"ab");
        terminal.resize(3, 4).unwrap();
        assert_eq!(text(&terminal, 0), "ab  ");
        assert_eq!(
            terminal.resize(MAX_ROWS + 1, 80),
            Err(TerminalError::InvalidSize)
        );

        let mut shrinking = Terminal::new(4, 2).unwrap();
        shrinking.feed(b"a\r\nb\r\nc\r\nd");
        shrinking.resize(2, 2).unwrap();
        assert_eq!(text(&shrinking, 0), "c ");
        assert_eq!(text(&shrinking, 1), "d ");
        assert_eq!(shrinking.cursor(), (1, 1));
    }

    #[test]
    fn alternate_screen_is_preallocated_restored_and_resized() {
        let mut terminal = Terminal::new(3, 8).unwrap();
        terminal.feed(b"primary");
        let primary_cursor = terminal.cursor();

        terminal.feed(b"\x1b[?1049h");
        assert_eq!(text(&terminal, 0), "        ");
        assert_eq!(terminal.cursor(), (0, 0));
        terminal.feed(b"alternate\r\nsecond");
        assert_eq!(text(&terminal, 0), "alternat");
        assert_eq!(text(&terminal, 1), "e       ");
        assert_eq!(text(&terminal, 2), "second  ");

        terminal.resize(2, 10).unwrap();
        assert_eq!(text(&terminal, 0), "e         ");
        assert_eq!(text(&terminal, 1), "second    ");
        terminal.feed(b"\x1b[?1049l");
        assert_eq!(text(&terminal, 0), "primary   ");
        assert_eq!(terminal.cursor(), primary_cursor);
    }

    #[test]
    fn dec_private_screen_and_cursor_modes_publish_full_damage() {
        let mut terminal = Terminal::new(2, 5).unwrap();
        let mut output = [0_u8; 2048];
        terminal.write_damage(&mut output).unwrap();

        terminal.feed(b"main\x1b[?47halt\x1b[?25l");
        assert_eq!(text(&terminal, 0), "alt  ");
        let length = terminal.write_damage(&mut output).unwrap();
        assert_eq!(length, DAMAGE_HEADER_SIZE + 2 * 5 * DAMAGE_CELL_SIZE);
        assert_eq!(u32::from_le_bytes(output[20..24].try_into().unwrap()), 0);

        terminal.feed(b"\x1b[?47l");
        assert_eq!(text(&terminal, 0), "main ");
        terminal.feed(b"\x1b[?47h");
        assert_eq!(text(&terminal, 0), "alt  ");
        terminal.feed(b"\x1b[?25h");
        terminal.write_damage(&mut output).unwrap();
        assert_eq!(u32::from_le_bytes(output[20..24].try_into().unwrap()), 1);
    }

    #[test]
    fn input_modes_are_versioned_in_damage_flags() {
        let mut terminal = Terminal::new(2, 5).unwrap();
        let mut output = [0_u8; 2048];
        terminal.write_damage(&mut output).unwrap();

        terminal.feed(b"\x1b[?1;66;67;2004h\x1b=\x1b[20h");
        terminal.write_damage(&mut output).unwrap();
        assert_eq!(
            u32::from_le_bytes(output[20..24].try_into().unwrap()),
            FLAG_CURSOR_VISIBLE
                | FLAG_APPLICATION_CURSOR
                | FLAG_APPLICATION_KEYPAD
                | FLAG_BRACKETED_PASTE
                | FLAG_NEW_LINE_MODE
                | FLAG_BACKARROW_KEY
        );

        terminal.feed(b"\x1b[?1;66;67;2004l\x1b>\x1b[20l");
        terminal.write_damage(&mut output).unwrap();
        assert_eq!(
            u32::from_le_bytes(output[20..24].try_into().unwrap()),
            FLAG_CURSOR_VISIBLE
        );
    }

    #[test]
    fn editing_controls_shift_erase_repeat_and_insert_without_growth() {
        let mut terminal = Terminal::new(2, 8).unwrap();
        terminal.feed(b"abcdef\x1b[3D\x1b[2@XY\x1b[2P\x1b[2X");
        assert_eq!(text(&terminal, 0), "abcXY   ");

        terminal.feed(b"A\x1b[3b");
        assert_eq!(text(&terminal, 0), "abcXYAAA");
        assert_eq!(text(&terminal, 1), "A       ");

        let mut insert = Terminal::new(2, 6).unwrap();
        insert.feed(b"abcd\x1b[1;2H\x1b[4hX\x1b[4lY");
        assert_eq!(text(&insert, 0), "aXYcd ");
    }

    #[test]
    fn line_and_region_controls_preserve_rows_outside_the_margin() {
        let mut terminal = Terminal::new(5, 4).unwrap();
        terminal.feed(
            b"\x1b[1;1H1111\x1b[2;1H2222\x1b[3;1H3333\
              \x1b[4;1H4444\x1b[5;1H5555\x1b[2;4r",
        );

        terminal.feed(b"\x1b[3;1H\x1b[LIIII\x1b[M");
        assert_eq!(text(&terminal, 0), "1111");
        assert_eq!(text(&terminal, 1), "2222");
        assert_eq!(text(&terminal, 2), "3333");
        assert_eq!(text(&terminal, 3), "    ");
        assert_eq!(text(&terminal, 4), "5555");

        terminal.feed(b"\x1b[S\x1b[T");
        assert_eq!(text(&terminal, 0), "1111");
        assert_eq!(text(&terminal, 1), "    ");
        assert_eq!(text(&terminal, 2), "3333");
        assert_eq!(text(&terminal, 3), "    ");
        assert_eq!(text(&terminal, 4), "5555");

        terminal.feed(b"\x1b[2;1H\x1bM");
        assert_eq!(text(&terminal, 0), "1111");
        assert_eq!(text(&terminal, 1), "    ");
        assert_eq!(text(&terminal, 2), "    ");
        assert_eq!(text(&terminal, 3), "3333");
        assert_eq!(text(&terminal, 4), "5555");
    }

    #[test]
    fn dec_auto_wrap_can_be_disabled_and_restored() {
        let mut terminal = Terminal::new(3, 4).unwrap();
        terminal.feed(b"ABCD");
        assert_eq!(terminal.cursor(), (0, 3));
        terminal.feed(b"E");
        assert_eq!(text(&terminal, 0), "ABCD");
        assert_eq!(text(&terminal, 1), "E   ");

        terminal.feed(b"\x1b[1;1H\x1b[2J\x1b[?7lABCDE");
        assert_eq!(text(&terminal, 0), "ABCE");
        assert_eq!(text(&terminal, 1), "    ");
        assert_eq!(terminal.cursor(), (0, 3));

        terminal.feed(b"\x1b[?7hFG");
        assert_eq!(text(&terminal, 0), "ABCF");
        assert_eq!(text(&terminal, 1), "G   ");
        assert_eq!(terminal.cursor(), (1, 1));
    }

    #[test]
    fn dec_origin_mode_makes_cursor_rows_relative_to_scrolling_margins() {
        let mut terminal = Terminal::new(5, 6).unwrap();
        terminal.feed(b"\x1b[2;4r");
        assert_eq!(terminal.cursor(), (0, 0));

        terminal.feed(b"\x1b[?6h");
        assert_eq!(terminal.cursor(), (1, 0));
        terminal.feed(b"\x1b[2;3H");
        assert_eq!(terminal.cursor(), (2, 2));
        terminal.feed(b"\x1b[99B");
        assert_eq!(terminal.cursor(), (3, 2));
        terminal.feed(b"\x1b[99A");
        assert_eq!(terminal.cursor(), (1, 2));
        terminal.feed(b"\x1b[3d");
        assert_eq!(terminal.cursor(), (3, 2));

        terminal.feed(b"\x1b[?6l");
        assert_eq!(terminal.cursor(), (0, 0));
        terminal.feed(b"\x1b[5;6H");
        assert_eq!(terminal.cursor(), (4, 5));
        terminal.feed(b"\x1b[2;4r");
        assert_eq!(terminal.cursor(), (0, 0));
    }

    #[test]
    fn cursor_tab_and_save_restore_controls_are_bounded() {
        let mut terminal = Terminal::new(3, 20).unwrap();
        terminal.feed(b"\t");
        assert_eq!(terminal.cursor(), (0, 8));

        terminal.feed(b"\x1b[3g\x1b[1;6H\x1bH\x1b[1;1H\x1b[I");
        assert_eq!(terminal.cursor(), (0, 5));
        terminal.feed(b"\x1b[Z");
        assert_eq!(terminal.cursor(), (0, 0));
        terminal.feed(b"\x1b[1;6H\x1b[g\x1b[1;1H\t");
        assert_eq!(terminal.cursor(), (0, 19));

        terminal.feed(b"\x1b[2;4H\x1b[s\x1b[3E\x1b[2F\x1b[9G\x1b[u");
        assert_eq!(terminal.cursor(), (1, 3));
        terminal.feed(b"\x1b[3d\x1b[7`");
        assert_eq!(terminal.cursor(), (2, 6));
    }

    #[test]
    fn bounded_control_strings_never_escape_into_the_grid() {
        let mut terminal = Terminal::new(2, 12).unwrap();
        terminal.take_dirty_rows();
        terminal.feed(b"ok\x1b]0;secret\x07\x1bPignored\x1b\\!");
        assert_eq!(text(&terminal, 0), "ok!         ");
        assert_eq!(terminal.take_dirty_rows(), Some((0, 1)));
    }

    #[test]
    fn damage_wire_format_is_versioned_bounded_and_consumed_atomically() {
        let mut terminal = Terminal::new(2, 3).unwrap();
        terminal.take_dirty_rows();
        terminal.feed(b"A");
        let required = terminal.required_damage_bytes();
        assert_eq!(required, DAMAGE_HEADER_SIZE + 3 * DAMAGE_CELL_SIZE);
        assert_eq!(
            terminal.write_damage(&mut [0; DAMAGE_HEADER_SIZE]),
            Err(TerminalError::OutputTooSmall)
        );
        assert_eq!(terminal.required_damage_bytes(), required);
        let mut output = [0_u8; 2048];
        assert_eq!(terminal.write_damage(&mut output), Ok(required));
        assert_eq!(&output[0..4], b"ATRM");
        assert_eq!(
            u32::from_le_bytes(output[4..8].try_into().unwrap()),
            DAMAGE_PROTOCOL_VERSION
        );
        assert_eq!(u16::from_le_bytes(output[16..18].try_into().unwrap()), 0);
        assert_eq!(u16::from_le_bytes(output[18..20].try_into().unwrap()), 1);
        assert_eq!(
            u32::from_le_bytes(output[32..36].try_into().unwrap()),
            u32::from(b'A')
        );
        assert_eq!(terminal.required_damage_bytes(), DAMAGE_HEADER_SIZE);
        assert_eq!(
            terminal.write_full_damage(&mut output),
            Ok(DAMAGE_HEADER_SIZE + 2 * 3 * DAMAGE_CELL_SIZE)
        );
        assert_eq!(u16::from_le_bytes(output[16..18].try_into().unwrap()), 0);
        assert_eq!(u16::from_le_bytes(output[18..20].try_into().unwrap()), 2);
        let first_revision = u64::from_le_bytes(output[24..32].try_into().unwrap());
        terminal.resize(3, 3).unwrap();
        terminal.write_damage(&mut output).unwrap();
        assert!(
            u64::from_le_bytes(output[24..32].try_into().unwrap()) > first_revision,
            "resize must publish a new revision"
        );
    }
}
