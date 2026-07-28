#![forbid(unsafe_code)]

use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

pub const MIN_ROWS: u16 = 2;
pub const MAX_ROWS: u16 = 200;
pub const MIN_COLUMNS: u16 = 2;
pub const MAX_COLUMNS: u16 = 400;
pub const MAX_GRAPHEME_CODEPOINTS: usize = 16;
pub const DAMAGE_PROTOCOL_VERSION: u32 = 5;
pub const DAMAGE_HEADER_SIZE: usize = 48;
pub const DAMAGE_CELL_SIZE: usize = 76;
pub const MAX_DAMAGE_BYTES: usize =
    DAMAGE_HEADER_SIZE + MAX_ROWS as usize * MAX_COLUMNS as usize * DAMAGE_CELL_SIZE;
pub const MAX_SELECTION_BYTES: usize = 8 * 1024;
pub const MAX_REPLY_BYTES: usize = 64 * 1024;
pub const SCROLLBACK_BYTE_LIMIT: usize = 4 * 1024 * 1024;
pub const SCROLLBACK_LINE_LIMIT: usize = 4 * 1024;
const DAMAGE_MAGIC: u32 = u32::from_le_bytes(*b"ATRM");
const MAX_CSI_PARAMETERS: usize = 16;
const MAX_STRING_BYTES: usize = 8 * 1024;
const MAX_OSC_BYTES: usize = 512;
const MAX_OSC_COLOR_OPERATIONS: usize = 32;
const DEFAULT_FOREGROUND: u32 = 7;
const DEFAULT_BACKGROUND: u32 = 0;
const DIRECT_COLOR_FLAG: u32 = 1 << 24;
const FLAG_CURSOR_VISIBLE: u32 = 1;
const FLAG_APPLICATION_CURSOR: u32 = 1 << 1;
const FLAG_APPLICATION_KEYPAD: u32 = 1 << 2;
const FLAG_BRACKETED_PASTE: u32 = 1 << 3;
const FLAG_NEW_LINE_MODE: u32 = 1 << 4;
const FLAG_BACKARROW_KEY: u32 = 1 << 5;
const FLAG_REVERSE_SCREEN: u32 = 1 << 6;
const ATTRIBUTE_BOLD: u8 = 1;
const ATTRIBUTE_UNDERLINE: u8 = 1 << 1;
const ATTRIBUTE_INVERSE: u8 = 1 << 2;
const ATTRIBUTE_FAINT: u8 = 1 << 3;
const ATTRIBUTE_ITALIC: u8 = 1 << 4;
const ATTRIBUTE_STRIKE: u8 = 1 << 5;
const ATTRIBUTE_HIDDEN: u8 = 1 << 6;
const ATTRIBUTE_GRAPHEME_TRUNCATED: u8 = 1 << 7;

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
    InvalidSelection,
    OutputTooSmall,
}

#[derive(Clone, Copy, Debug, Default)]
struct StoredLine {
    start: u32,
    byte_length: u32,
    cells: u16,
    soft_wrapped: bool,
    visual_columns: u16,
    visual_rows: u16,
}

#[derive(Debug)]
struct Scrollback {
    bytes: Vec<u8>,
    lines: Vec<StoredLine>,
    first_line: usize,
    line_count: usize,
    write_offset: usize,
    bytes_used: usize,
    origin_epoch: u64,
}

impl Scrollback {
    fn new() -> Self {
        Self {
            bytes: vec![0; SCROLLBACK_BYTE_LIMIT],
            lines: vec![StoredLine::default(); SCROLLBACK_LINE_LIMIT],
            first_line: 0,
            line_count: 0,
            write_offset: 0,
            bytes_used: 0,
            origin_epoch: 1,
        }
    }

    fn clear(&mut self) {
        self.bytes.fill(0);
        self.lines.fill(StoredLine::default());
        self.first_line = 0;
        self.line_count = 0;
        self.write_offset = 0;
        self.bytes_used = 0;
        self.origin_epoch = self.origin_epoch.saturating_add(1);
    }

    fn append_line(&mut self, cells: &[Cell], soft_wrapped: bool, columns: u16) {
        let stored_cells = if soft_wrapped {
            cells
        } else {
            let meaningful = cells
                .iter()
                .rposition(|cell| *cell != Cell::blank())
                .map_or(1, |index| index + 1);
            &cells[..meaningful.min(cells.len())]
        };
        let byte_length = stored_cells.iter().fold(0_usize, |length, cell| {
            length + 12 + usize::from(cell.grapheme_len) * 4
        });
        if stored_cells.is_empty()
            || stored_cells.len() > usize::from(MAX_COLUMNS)
            || byte_length > self.bytes.len()
        {
            return;
        }
        let mut extend_previous = self.last_line().is_some_and(|line| {
            line.soft_wrapped
                && usize::from(line.cells)
                    .checked_add(stored_cells.len())
                    .is_some_and(|cells| cells <= usize::from(u16::MAX))
                && usize::try_from(line.byte_length)
                    .ok()
                    .and_then(|length| length.checked_add(byte_length))
                    .is_some_and(|length| u32::try_from(length).is_ok())
        });
        while self.bytes.len() - self.bytes_used < byte_length {
            if extend_previous && self.line_count == 1 {
                extend_previous = false;
            }
            self.evict_oldest();
        }
        if !extend_previous && self.line_count == self.lines.len() {
            self.evict_oldest();
        }
        let line_index = if extend_previous {
            (self.first_line + self.line_count - 1) % self.lines.len()
        } else {
            let index = (self.first_line + self.line_count) % self.lines.len();
            self.lines[index] = StoredLine {
                start: self.write_offset as u32,
                byte_length: 0,
                cells: 0,
                soft_wrapped: false,
                visual_columns: 0,
                visual_rows: 0,
            };
            self.line_count += 1;
            index
        };
        for cell in stored_cells {
            self.write_byte(cell.grapheme_len);
            self.write_byte(cell.width);
            self.write_byte(cell.attributes);
            self.write_byte(0);
            self.write_u32(cell.foreground);
            self.write_u32(cell.background);
            for index in 0..usize::from(cell.grapheme_len) {
                self.write_u32(cell.codepoint(index));
            }
        }
        self.lines[line_index].byte_length += byte_length as u32;
        self.lines[line_index].cells += stored_cells.len() as u16;
        self.lines[line_index].soft_wrapped = soft_wrapped;
        self.lines[line_index].visual_columns = 0;
        let visual_rows = self.measure_line_rows(self.lines[line_index], columns);
        self.lines[line_index].visual_columns = columns;
        self.lines[line_index].visual_rows = visual_rows;
        self.bytes_used += byte_length;
    }

    fn visual_rows(&self, columns: u16) -> u32 {
        self.line_iter().fold(0_u32, |rows, line| {
            rows.saturating_add(u32::from(self.line_visual_rows(line, columns)))
        })
    }

    fn fill_visual_row(&self, visual_row: u32, columns: u16, output: &mut [Cell]) -> bool {
        if output.len() < usize::from(columns) {
            return false;
        }
        output[..usize::from(columns)].fill(Cell::blank());
        let mut first_visual_row = 0_u32;
        for line in self.line_iter() {
            let line_rows = u32::from(self.line_visual_rows(line, columns));
            if visual_row < first_visual_row.saturating_add(line_rows) {
                self.decode_visual_row(line, visual_row - first_visual_row, columns, output);
                return true;
            }
            first_visual_row = first_visual_row.saturating_add(line_rows);
        }
        false
    }

    fn visual_row_soft_wrapped(&self, visual_row: u32, columns: u16) -> bool {
        let mut first_visual_row = 0_u32;
        for line in self.line_iter() {
            let line_rows = u32::from(self.line_visual_rows(line, columns));
            if visual_row < first_visual_row.saturating_add(line_rows) {
                let row_in_line = visual_row - first_visual_row;
                return row_in_line + 1 < line_rows || line.soft_wrapped;
            }
            first_visual_row = first_visual_row.saturating_add(line_rows);
        }
        false
    }

    fn line_iter(&self) -> impl Iterator<Item = StoredLine> + '_ {
        (0..self.line_count).map(|offset| self.lines[(self.first_line + offset) % self.lines.len()])
    }

    fn last_line(&self) -> Option<StoredLine> {
        (self.line_count != 0)
            .then(|| self.lines[(self.first_line + self.line_count - 1) % self.lines.len()])
    }

    fn line_visual_rows(&self, line: StoredLine, columns: u16) -> u16 {
        if line.visual_columns == columns && line.visual_rows != 0 {
            line.visual_rows
        } else {
            self.measure_line_rows(line, columns)
        }
    }

    fn measure_line_rows(&self, line: StoredLine, columns: u16) -> u16 {
        let mut source = line.start as usize;
        let mut consumed = 0_usize;
        let mut rows = 1_u16;
        let mut column = 0_u16;
        for _ in 0..line.cells {
            let grapheme_len = self.read_byte(source);
            let width = u16::from(self.read_byte(source + 1).min(2));
            let stored_length = 12 + usize::from(grapheme_len) * 4;
            if width != 0 {
                let width = width.min(columns);
                if column != 0 && column.saturating_add(width) > columns {
                    rows = rows.saturating_add(1);
                    column = 0;
                }
                column = column.saturating_add(width);
            }
            source = (source + stored_length) % self.bytes.len();
            consumed += stored_length;
            if consumed >= line.byte_length as usize {
                break;
            }
        }
        rows
    }

    fn reflow(&mut self, columns: u16) {
        for offset in 0..self.line_count {
            let index = (self.first_line + offset) % self.lines.len();
            let rows = self.measure_line_rows(self.lines[index], columns);
            self.lines[index].visual_columns = columns;
            self.lines[index].visual_rows = rows;
        }
    }

    fn take_trailing_soft_line(&mut self, output: &mut Vec<Cell>) -> bool {
        let Some(line) = self.last_line() else {
            return false;
        };
        if !line.soft_wrapped {
            return false;
        }
        output.reserve(usize::from(line.cells));
        let mut source = line.start as usize;
        let mut consumed = 0_usize;
        for _ in 0..line.cells {
            let grapheme_len = self.read_byte(source);
            let stored_length = 12 + usize::from(grapheme_len) * 4;
            let mut cell = Cell {
                codepoint: 0,
                trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
                foreground: self.read_u32(source + 4),
                background: self.read_u32(source + 8),
                attributes: self.read_byte(source + 2),
                grapheme_len: grapheme_len.min(MAX_GRAPHEME_CODEPOINTS as u8),
                width: self.read_byte(source + 1).min(2),
            };
            for codepoint_index in 0..usize::from(cell.grapheme_len) {
                cell.set_codepoint(
                    codepoint_index,
                    self.read_u32(source + 12 + codepoint_index * 4),
                );
            }
            output.push(cell);
            source = (source + stored_length) % self.bytes.len();
            consumed += stored_length;
            if consumed >= line.byte_length as usize {
                break;
            }
        }
        let index = (self.first_line + self.line_count - 1) % self.lines.len();
        self.lines[index] = StoredLine::default();
        self.line_count -= 1;
        self.bytes_used = self.bytes_used.saturating_sub(line.byte_length as usize);
        self.write_offset = line.start as usize;
        true
    }

    fn decode_visual_row(
        &self,
        line: StoredLine,
        target_row: u32,
        columns: u16,
        output: &mut [Cell],
    ) {
        let mut source = line.start as usize;
        let mut consumed = 0_usize;
        let mut row = 0_u32;
        let mut column = 0_u16;
        for _ in 0..line.cells {
            let grapheme_len = self.read_byte(source);
            let width = u16::from(self.read_byte(source + 1).min(2));
            let attributes = self.read_byte(source + 2);
            let foreground = self.read_u32(source + 4);
            let background = self.read_u32(source + 8);
            let stored_length = 12 + usize::from(grapheme_len) * 4;
            if width != 0 {
                let width = width.min(columns);
                if column != 0 && column.saturating_add(width) > columns {
                    row = row.saturating_add(1);
                    column = 0;
                }
            }
            if width != 0 && row == target_row {
                let mut cell = Cell {
                    codepoint: 0,
                    trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
                    foreground,
                    background,
                    attributes,
                    grapheme_len: grapheme_len.min(MAX_GRAPHEME_CODEPOINTS as u8),
                    width: width as u8,
                };
                for codepoint_index in 0..usize::from(cell.grapheme_len) {
                    cell.set_codepoint(
                        codepoint_index,
                        self.read_u32(source + 12 + codepoint_index * 4),
                    );
                }
                output[usize::from(column)] = cell;
                if width == 2 {
                    output[usize::from(column + 1)] =
                        Cell::continuation(foreground, background, attributes);
                }
            }
            column = column.saturating_add(width);
            source = (source + stored_length) % self.bytes.len();
            consumed += stored_length;
            if consumed >= line.byte_length as usize || row > target_row {
                break;
            }
        }
    }

    fn evict_oldest(&mut self) {
        if self.line_count == 0 {
            return;
        }
        let line = self.lines[self.first_line];
        self.bytes_used = self.bytes_used.saturating_sub(line.byte_length as usize);
        self.lines[self.first_line] = StoredLine::default();
        self.first_line = (self.first_line + 1) % self.lines.len();
        self.line_count -= 1;
        self.origin_epoch = self.origin_epoch.saturating_add(1);
    }

    fn write_byte(&mut self, value: u8) {
        self.bytes[self.write_offset] = value;
        self.write_offset = (self.write_offset + 1) % self.bytes.len();
    }

    fn write_u32(&mut self, value: u32) {
        for byte in value.to_le_bytes() {
            self.write_byte(byte);
        }
    }

    fn read_byte(&self, offset: usize) -> u8 {
        self.bytes[offset % self.bytes.len()]
    }

    fn read_u32(&self, offset: usize) -> u32 {
        u32::from_le_bytes([
            self.read_byte(offset),
            self.read_byte(offset + 1),
            self.read_byte(offset + 2),
            self.read_byte(offset + 3),
        ])
    }
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
    scroll_left: u16,
    scroll_right: u16,
    inactive_cursor_row: u16,
    inactive_cursor_column: u16,
    inactive_wrap_pending: bool,
    inactive_saved_row: u16,
    inactive_saved_column: u16,
    inactive_scroll_top: u16,
    inactive_scroll_bottom: u16,
    inactive_scroll_left: u16,
    inactive_scroll_right: u16,
    row_soft_wrapped: Vec<bool>,
    inactive_row_soft_wrapped: Vec<bool>,
    scrollback: Scrollback,
    view_row_scratch: Vec<Cell>,
    alternate_active: bool,
    cursor_visible: bool,
    application_cursor: bool,
    application_keypad: bool,
    bracketed_paste: bool,
    new_line_mode: bool,
    backarrow_key: bool,
    reverse_screen: bool,
    left_right_margin_mode: bool,
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
    csi_prefix: u8,
    csi_intermediate: u8,
    csi_unsupported: bool,
    string_bytes: usize,
    string_is_osc: bool,
    osc_bytes: [u8; MAX_OSC_BYTES],
    osc_length: usize,
    osc_unsupported: bool,
    utf8_codepoint: u32,
    utf8_minimum: u32,
    utf8_remaining: u8,
    foreground: u32,
    background: u32,
    attributes: u8,
    palette_colors: [u32; 256],
    palette_overridden: [bool; 256],
    dirty_start: u16,
    dirty_end: u16,
    revision: u64,
    reply_bytes: Vec<u8>,
    reply_start: usize,
    reply_length: usize,
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
            scroll_left: 0,
            scroll_right: columns - 1,
            inactive_cursor_row: 0,
            inactive_cursor_column: 0,
            inactive_wrap_pending: false,
            inactive_saved_row: 0,
            inactive_saved_column: 0,
            inactive_scroll_top: 0,
            inactive_scroll_bottom: rows - 1,
            inactive_scroll_left: 0,
            inactive_scroll_right: columns - 1,
            row_soft_wrapped: vec![false; usize::from(rows)],
            inactive_row_soft_wrapped: vec![false; usize::from(rows)],
            scrollback: Scrollback::new(),
            view_row_scratch: vec![Cell::blank(); usize::from(MAX_COLUMNS)],
            alternate_active: false,
            cursor_visible: true,
            application_cursor: false,
            application_keypad: false,
            bracketed_paste: false,
            new_line_mode: false,
            backarrow_key: false,
            reverse_screen: false,
            left_right_margin_mode: false,
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
            csi_prefix: 0,
            csi_intermediate: 0,
            csi_unsupported: false,
            string_bytes: 0,
            string_is_osc: false,
            osc_bytes: [0; MAX_OSC_BYTES],
            osc_length: 0,
            osc_unsupported: false,
            utf8_codepoint: 0,
            utf8_minimum: 0,
            utf8_remaining: 0,
            foreground: DEFAULT_FOREGROUND,
            background: DEFAULT_BACKGROUND,
            attributes: 0,
            palette_colors: [0; 256],
            palette_overridden: [false; 256],
            dirty_start: 0,
            dirty_end: rows,
            revision: 1,
            reply_bytes: vec![0; MAX_REPLY_BYTES],
            reply_start: 0,
            reply_length: 0,
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

    pub fn history_rows(&self) -> u32 {
        self.scrollback.visual_rows(self.columns)
    }

    pub const fn history_origin_epoch(&self) -> u64 {
        self.scrollback.origin_epoch
    }

    pub fn write_selection(
        &mut self,
        output: &mut [u8],
        origin_epoch: u64,
        start_row: u32,
        start_column: u16,
        end_row: u32,
        end_column: u16,
    ) -> Result<usize, TerminalError> {
        let history_rows = self.history_rows();
        let total_rows = history_rows.saturating_add(u32::from(self.rows));
        if output.is_empty()
            || output.len() > MAX_SELECTION_BYTES
            || origin_epoch != self.history_origin_epoch()
            || start_row > end_row
            || end_row >= total_rows
            || start_column >= self.columns
            || end_column >= self.columns
            || (start_row == end_row && start_column > end_column)
        {
            return Err(TerminalError::InvalidSelection);
        }
        let mut output_length = 0_usize;
        for row in start_row..=end_row {
            if !self.fill_combined_row(row, history_rows) {
                return Err(TerminalError::InvalidSelection);
            }
            let first_column = if row == start_row { start_column } else { 0 };
            let last_column = if row == end_row {
                end_column
            } else {
                self.columns - 1
            };
            let row_cells =
                &self.view_row_scratch[usize::from(first_column)..=usize::from(last_column)];
            let retained_cells = row_cells
                .iter()
                .rposition(|cell| !cell_is_text_blank(*cell))
                .map_or(0, |index| index + 1);
            for cell in &row_cells[..retained_cells] {
                if cell.width == 0 {
                    continue;
                }
                for index in 0..usize::from(cell.grapheme_len) {
                    let Some(character) = char::from_u32(cell.codepoint(index)) else {
                        return Err(TerminalError::InvalidSelection);
                    };
                    let mut encoded = [0_u8; 4];
                    let bytes = character.encode_utf8(&mut encoded).as_bytes();
                    if output_length + bytes.len() > output.len() {
                        return Err(TerminalError::OutputTooSmall);
                    }
                    output[output_length..output_length + bytes.len()].copy_from_slice(bytes);
                    output_length += bytes.len();
                }
            }
            if row != end_row && !self.combined_row_soft_wrapped(row, history_rows) {
                if output_length == output.len() {
                    return Err(TerminalError::OutputTooSmall);
                }
                output[output_length] = b'\n';
                output_length += 1;
            }
        }
        Ok(output_length)
    }

    fn fill_combined_row(&mut self, row: u32, history_rows: u32) -> bool {
        if row < history_rows {
            self.scrollback
                .fill_visual_row(row, self.columns, &mut self.view_row_scratch)
        } else {
            let screen_row = row - history_rows;
            if screen_row >= u32::from(self.rows) {
                return false;
            }
            let start = usize::try_from(screen_row).unwrap() * usize::from(self.columns);
            self.view_row_scratch[..usize::from(self.columns)]
                .copy_from_slice(&self.cells[start..start + usize::from(self.columns)]);
            true
        }
    }

    fn combined_row_soft_wrapped(&self, row: u32, history_rows: u32) -> bool {
        if row < history_rows {
            self.scrollback.visual_row_soft_wrapped(row, self.columns)
        } else {
            self.row_soft_wrapped[(row - history_rows) as usize]
        }
    }

    pub fn write_view_damage(
        &mut self,
        output: &mut [u8],
        viewport_offset: u32,
    ) -> Result<usize, TerminalError> {
        let history_rows = self.history_rows();
        let viewport_offset = viewport_offset.min(history_rows);
        if viewport_offset == 0 {
            return self.write_full_damage(output);
        }
        let required = DAMAGE_HEADER_SIZE
            + usize::from(self.rows) * usize::from(self.columns) * DAMAGE_CELL_SIZE;
        if output.len() < required {
            return Err(TerminalError::OutputTooSmall);
        }
        output[..required].fill(0);
        self.write_damage_header(output, 0, 0, 0, self.rows, history_rows, viewport_offset);
        let first_visual_row = history_rows - viewport_offset;
        let mut offset = DAMAGE_HEADER_SIZE;
        for viewport_row in 0..self.rows {
            let visual_row = first_visual_row + u32::from(viewport_row);
            if visual_row < history_rows {
                let (scrollback, scratch) = (&self.scrollback, &mut self.view_row_scratch);
                if !scrollback.fill_visual_row(visual_row, self.columns, scratch) {
                    scratch[..usize::from(self.columns)].fill(Cell::blank());
                }
                for cell in &scratch[..usize::from(self.columns)] {
                    write_wire_cell(
                        output,
                        offset,
                        *cell,
                        &self.palette_colors,
                        &self.palette_overridden,
                    );
                    offset += DAMAGE_CELL_SIZE;
                }
            } else {
                let screen_row = (visual_row - history_rows) as u16;
                for column in 0..self.columns {
                    write_wire_cell(
                        output,
                        offset,
                        self.cells[self.index(screen_row, column)],
                        &self.palette_colors,
                        &self.palette_overridden,
                    );
                    offset += DAMAGE_CELL_SIZE;
                }
            }
        }
        self.dirty_start = self.rows;
        self.dirty_end = 0;
        Ok(required)
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
        self.write_damage_header(
            output,
            self.cursor_row,
            self.cursor_column,
            dirty_start,
            dirty_end,
            self.history_rows(),
            0,
        );
        let mut offset = DAMAGE_HEADER_SIZE;
        for row in dirty_start..dirty_end {
            for column in 0..self.columns {
                write_wire_cell(
                    output,
                    offset,
                    self.cells[self.index(row, column)],
                    &self.palette_colors,
                    &self.palette_overridden,
                );
                offset += DAMAGE_CELL_SIZE;
            }
        }
        self.dirty_start = self.rows;
        self.dirty_end = 0;
        Ok(required)
    }

    // Keep the fixed wire fields primitive so this warmed path needs no
    // temporary aggregate or heap allocation.
    #[allow(clippy::too_many_arguments)]
    fn write_damage_header(
        &self,
        output: &mut [u8],
        cursor_row: u16,
        cursor_column: u16,
        dirty_start: u16,
        dirty_end: u16,
        history_rows: u32,
        viewport_offset: u32,
    ) {
        output[0..4].copy_from_slice(&DAMAGE_MAGIC.to_le_bytes());
        output[4..8].copy_from_slice(&DAMAGE_PROTOCOL_VERSION.to_le_bytes());
        output[8..10].copy_from_slice(&self.rows.to_le_bytes());
        output[10..12].copy_from_slice(&self.columns.to_le_bytes());
        output[12..14].copy_from_slice(&cursor_row.to_le_bytes());
        output[14..16].copy_from_slice(&cursor_column.to_le_bytes());
        output[16..18].copy_from_slice(&dirty_start.to_le_bytes());
        output[18..20].copy_from_slice(&dirty_end.to_le_bytes());
        let flags = if self.cursor_visible && viewport_offset == 0 {
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
        } | if self.reverse_screen {
            FLAG_REVERSE_SCREEN
        } else {
            0
        };
        output[20..24].copy_from_slice(&flags.to_le_bytes());
        output[24..32].copy_from_slice(&self.revision.to_le_bytes());
        output[32..36].copy_from_slice(&history_rows.to_le_bytes());
        output[36..40].copy_from_slice(&viewport_offset.to_le_bytes());
        output[40..48].copy_from_slice(&self.history_origin_epoch().to_le_bytes());
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            self.feed_byte(byte);
        }
    }

    pub fn pending_reply(&self) -> &[u8] {
        let contiguous = self
            .reply_length
            .min(MAX_REPLY_BYTES.saturating_sub(self.reply_start));
        &self.reply_bytes[self.reply_start..self.reply_start + contiguous]
    }

    pub fn consume_reply(&mut self, length: usize) {
        let consumed = length.min(self.reply_length);
        self.reply_start = (self.reply_start + consumed) % MAX_REPLY_BYTES;
        self.reply_length -= consumed;
        if self.reply_length == 0 {
            self.reply_start = 0;
        }
    }

    pub fn resize(&mut self, rows: u16, columns: u16) -> Result<(), TerminalError> {
        validate_size(rows, columns)?;
        if rows == self.rows && columns == self.columns {
            return Ok(());
        }
        if self.alternate_active {
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
            self.row_soft_wrapped = resize_row_flags(&self.row_soft_wrapped, rows, source_row);
            self.inactive_row_soft_wrapped =
                resize_row_flags(&self.inactive_row_soft_wrapped, rows, inactive_source_row);
            self.cursor_row = self.cursor_row.saturating_sub(source_row).min(rows - 1);
            self.cursor_column = self.cursor_column.min(columns - 1);
            self.inactive_cursor_row = self
                .inactive_cursor_row
                .saturating_sub(inactive_source_row)
                .min(rows - 1);
            self.inactive_cursor_column = self.inactive_cursor_column.min(columns - 1);
            self.scrollback.reflow(columns);
        } else {
            let (inactive_cells, inactive_source_row) = resize_cells(
                &self.inactive_cells,
                self.rows,
                self.columns,
                rows,
                columns,
                self.inactive_cursor_row,
            );
            self.inactive_cells = inactive_cells;
            self.inactive_row_soft_wrapped =
                resize_row_flags(&self.inactive_row_soft_wrapped, rows, inactive_source_row);
            self.inactive_cursor_row = self
                .inactive_cursor_row
                .saturating_sub(inactive_source_row)
                .min(rows - 1);
            self.inactive_cursor_column = self.inactive_cursor_column.min(columns - 1);
            let (cells, wrapped, cursor_row, cursor_column) =
                self.reflow_primary_screen(rows, columns);
            self.cells = cells;
            self.row_soft_wrapped = wrapped;
            self.cursor_row = cursor_row;
            self.cursor_column = cursor_column;
        }
        self.rows = rows;
        self.columns = columns;
        self.wrap_pending = false;
        self.scroll_top = 0;
        self.scroll_bottom = rows - 1;
        self.scroll_left = 0;
        self.scroll_right = columns - 1;
        self.inactive_wrap_pending = false;
        self.inactive_scroll_top = 0;
        self.inactive_scroll_bottom = rows - 1;
        self.inactive_scroll_left = 0;
        self.inactive_scroll_right = columns - 1;
        self.dirty_start = 0;
        self.dirty_end = rows;
        self.revision = self.revision.saturating_add(1);
        Ok(())
    }

    fn reflow_primary_screen(
        &mut self,
        rows: u16,
        columns: u16,
    ) -> (Vec<Cell>, Vec<bool>, u16, u16) {
        let old_columns = usize::from(self.columns);
        let mut logical_cells = Vec::new();
        self.scrollback.take_trailing_soft_line(&mut logical_cells);
        let mut line_starts = Vec::with_capacity(usize::from(self.rows) + 2);
        line_starts.push(0);

        let last_nonblank = (0..self.rows).rev().find(|row| {
            let start = usize::from(*row) * old_columns;
            self.cells[start..start + old_columns]
                .iter()
                .any(|cell| *cell != Cell::blank())
        });
        let last_wrapped = self
            .row_soft_wrapped
            .iter()
            .rposition(|wrapped| *wrapped)
            .map(|row| (row + 1).min(usize::from(self.rows) - 1) as u16);
        let last_row = self
            .cursor_row
            .max(last_nonblank.unwrap_or(0))
            .max(last_wrapped.unwrap_or(0));
        let mut cursor_source = None;
        let mut cursor_fixed_column = None;
        for row in 0..=last_row {
            let start = usize::from(row) * old_columns;
            let source = &self.cells[start..start + old_columns];
            let soft = self.row_soft_wrapped[usize::from(row)];
            let line_has_content = logical_cells.len() > *line_starts.last().unwrap_or(&0);
            let meaningful = source
                .iter()
                .rposition(|cell| *cell != Cell::blank())
                .map_or(1, |column| column + 1);
            let mut length = if soft { old_columns } else { meaningful };
            if row == self.cursor_row {
                let empty_hard_line =
                    !soft && meaningful == 1 && source[0] == Cell::blank() && !line_has_content;
                if empty_hard_line {
                    cursor_source = Some(logical_cells.len());
                    cursor_fixed_column = Some(self.cursor_column.min(columns - 1));
                } else {
                    length = length.max(usize::from(self.cursor_column) + 1);
                    cursor_source = Some(logical_cells.len() + usize::from(self.cursor_column));
                }
            }
            logical_cells.extend_from_slice(&source[..length]);
            if !soft {
                line_starts.push(logical_cells.len());
            }
        }
        if *line_starts.last().unwrap_or(&0) != logical_cells.len() {
            line_starts.push(logical_cells.len());
        }
        let capacity = logical_cells
            .len()
            .saturating_mul(2)
            .max(usize::from(rows) * usize::from(columns));
        let mut visual = Vec::with_capacity(capacity);
        let mut visual_wrapped = Vec::new();
        let mut cursor_visual = None;
        for line in line_starts.windows(2) {
            let mut column = 0_u16;
            for (source_index, cell) in logical_cells
                .iter()
                .copied()
                .enumerate()
                .take(line[1])
                .skip(line[0])
            {
                if cell.width == 0 {
                    continue;
                }
                let width = u16::from(cell.width.clamp(1, 2)).min(columns);
                if column != 0 && column.saturating_add(width) > columns {
                    visual.resize(visual.len() + usize::from(columns - column), Cell::blank());
                    visual_wrapped.push(true);
                    column = 0;
                }
                if cursor_source == Some(source_index) {
                    cursor_visual = Some((visual_wrapped.len() as u32, column.min(columns - 1)));
                }
                visual.push(cell);
                if width == 2 {
                    visual.push(Cell::continuation(
                        cell.foreground,
                        cell.background,
                        cell.attributes,
                    ));
                }
                column += width;
            }
            visual.resize(
                visual.len() + usize::from(columns.saturating_sub(column)),
                Cell::blank(),
            );
            visual_wrapped.push(false);
        }

        let (cursor_visual_row, mut cursor_visual_column) =
            cursor_visual.unwrap_or((0, self.cursor_column.min(columns - 1)));
        if let Some(column) = cursor_fixed_column {
            cursor_visual_column = column;
        }
        let desired_cursor_row = self.cursor_row.min(rows - 1);
        let mut first_visual_row = cursor_visual_row.saturating_sub(u32::from(desired_cursor_row));
        let total_rows = visual_wrapped.len() as u32;
        let bottom_start = total_rows.saturating_sub(u32::from(rows));
        first_visual_row = first_visual_row.max(bottom_start.min(cursor_visual_row));
        let leading_blank_rows = u32::from(desired_cursor_row).saturating_sub(cursor_visual_row);

        self.scrollback.reflow(columns);
        for visual_row in 0..first_visual_row {
            let start = visual_row as usize * usize::from(columns);
            self.scrollback.append_line(
                &visual[start..start + usize::from(columns)],
                visual_wrapped[visual_row as usize],
                columns,
            );
        }

        let mut replacement = vec![Cell::blank(); usize::from(rows) * usize::from(columns)];
        let mut replacement_wrapped = vec![false; usize::from(rows)];
        let available_rows = total_rows.saturating_sub(first_visual_row);
        let copied_rows = available_rows.min(u32::from(rows).saturating_sub(leading_blank_rows));
        for offset in 0..copied_rows {
            let source_row = first_visual_row + offset;
            let destination_row = leading_blank_rows + offset;
            let source_start = source_row as usize * usize::from(columns);
            let destination_start = destination_row as usize * usize::from(columns);
            replacement[destination_start..destination_start + usize::from(columns)]
                .copy_from_slice(&visual[source_start..source_start + usize::from(columns)]);
            replacement_wrapped[destination_row as usize] = visual_wrapped[source_row as usize];
        }
        let cursor_row = leading_blank_rows
            .saturating_add(cursor_visual_row.saturating_sub(first_visual_row))
            .min(u32::from(rows - 1)) as u16;
        (
            replacement,
            replacement_wrapped,
            cursor_row,
            cursor_visual_column,
        )
    }

    fn feed_byte(&mut self, byte: u8) {
        let old_cursor = (self.cursor_row, self.cursor_column);
        match self.parser_state {
            ParserState::Ground => self.feed_ground(byte),
            ParserState::Escape => self.feed_escape(byte),
            ParserState::Csi => self.feed_csi(byte),
            ParserState::Osc => {
                if byte == 0x07 {
                    self.finish_control_string();
                    self.parser_state = ParserState::Ground;
                } else if byte == 0x1b {
                    self.parser_state = ParserState::OscEscape;
                } else {
                    self.push_control_string_byte(byte);
                }
            }
            ParserState::OscEscape => {
                if byte == b'\\' {
                    self.finish_control_string();
                    self.parser_state = ParserState::Ground;
                } else {
                    self.osc_unsupported = true;
                    self.push_control_string_byte(byte);
                    self.parser_state = ParserState::Osc;
                }
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
                self.cursor_column = self.line_start_column();
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
            b']' => {
                self.begin_control_string(true);
                self.parser_state = ParserState::Osc;
            }
            b'P' | b'_' | b'^' => {
                self.begin_control_string(false);
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
                self.cursor_column = self.line_start_column();
                self.line_feed();
            }
            b'H' => {
                self.tab_stops[usize::from(self.cursor_column)] = true;
            }
            b'M' => self.reverse_index(),
            b'Z' => self.queue_reply(b"\x1b[?1;2c"),
            b'c' => self.reset(),
            _ => {}
        }
    }

    fn begin_control_string(&mut self, is_osc: bool) {
        self.string_bytes = 0;
        self.string_is_osc = is_osc;
        self.osc_length = 0;
        self.osc_unsupported = false;
    }

    fn push_control_string_byte(&mut self, byte: u8) {
        if self.string_bytes >= MAX_STRING_BYTES {
            self.osc_unsupported = true;
            return;
        }
        self.string_bytes += 1;
        if self.string_is_osc {
            if self.osc_length < self.osc_bytes.len() {
                self.osc_bytes[self.osc_length] = byte;
                self.osc_length += 1;
            } else {
                self.osc_unsupported = true;
            }
        }
    }

    fn finish_control_string(&mut self) {
        if self.string_is_osc && !self.osc_unsupported {
            let bytes = self.osc_bytes;
            self.execute_osc(&bytes[..self.osc_length]);
        }
        self.string_bytes = 0;
        self.string_is_osc = false;
        self.osc_length = 0;
        self.osc_unsupported = false;
    }

    fn execute_osc(&mut self, bytes: &[u8]) {
        let mut fields = bytes.split(|byte| *byte == b';');
        match fields.next() {
            Some(b"4") => self.execute_palette_change(fields),
            Some(b"104") => self.execute_palette_reset(fields),
            _ => {}
        }
    }

    fn execute_palette_change<'a>(&mut self, mut fields: impl Iterator<Item = &'a [u8]>) {
        let mut indexes = [0_u8; MAX_OSC_COLOR_OPERATIONS];
        let mut colors = [0_u32; MAX_OSC_COLOR_OPERATIONS];
        let mut queries = [false; MAX_OSC_COLOR_OPERATIONS];
        let mut count = 0;
        loop {
            let Some(index_field) = fields.next() else {
                break;
            };
            let Some(color_field) = fields.next() else {
                return;
            };
            if count == MAX_OSC_COLOR_OPERATIONS {
                return;
            }
            let Some(index) = parse_palette_index(index_field) else {
                return;
            };
            if color_field == b"?" {
                queries[count] = true;
            } else {
                let Some(color) = parse_palette_color(color_field) else {
                    return;
                };
                colors[count] = color;
            }
            indexes[count] = index;
            count += 1;
        }
        if count == 0 {
            return;
        }
        let mut changed = false;
        for operation in 0..count {
            let index = usize::from(indexes[operation]);
            if queries[operation] {
                self.report_palette_color(indexes[operation]);
            } else if !self.palette_overridden[index]
                || self.palette_colors[index] != colors[operation]
            {
                self.palette_colors[index] = colors[operation];
                self.palette_overridden[index] = true;
                changed = true;
            }
        }
        if changed {
            self.mark_dirty_range(0, self.rows);
        }
    }

    fn execute_palette_reset<'a>(&mut self, mut fields: impl Iterator<Item = &'a [u8]>) {
        let mut reset = [false; 256];
        let mut has_indexes = false;
        for field in fields.by_ref() {
            let Some(index) = parse_palette_index(field) else {
                return;
            };
            reset[usize::from(index)] = true;
            has_indexes = true;
        }
        let mut changed = false;
        if has_indexes {
            for (index, should_reset) in reset.into_iter().enumerate() {
                if should_reset && self.palette_overridden[index] {
                    self.palette_overridden[index] = false;
                    changed = true;
                }
            }
        } else {
            changed = self.palette_overridden.iter().any(|overridden| *overridden);
            self.palette_overridden.fill(false);
        }
        if changed {
            self.mark_dirty_range(0, self.rows);
        }
    }

    fn report_palette_color(&mut self, index: u8) {
        let color = self.palette_color(index);
        let mut response = [0_u8; 32];
        response[..4].copy_from_slice(b"\x1b]4;");
        let mut length = 4;
        length += write_decimal(&mut response[length..], u16::from(index));
        response[length..length + 5].copy_from_slice(b";rgb:");
        length += 5;
        length += write_hex_u16(
            &mut response[length..],
            ((color >> 16) as u8 as u16) * 0x101,
        );
        response[length] = b'/';
        length += 1;
        length += write_hex_u16(&mut response[length..], ((color >> 8) as u8 as u16) * 0x101);
        response[length] = b'/';
        length += 1;
        length += write_hex_u16(&mut response[length..], (color as u8 as u16) * 0x101);
        response[length..length + 2].copy_from_slice(b"\x1b\\");
        length += 2;
        self.queue_reply(&response[..length]);
    }

    fn palette_color(&self, index: u8) -> u32 {
        let index = usize::from(index);
        if self.palette_overridden[index] {
            self.palette_colors[index]
        } else {
            default_palette_color(index as u8)
        }
    }

    fn feed_csi(&mut self, byte: u8) {
        match byte {
            b'0'..=b'9' if self.csi_intermediate == 0 => {
                self.csi_value = self
                    .csi_value
                    .saturating_mul(10)
                    .saturating_add(u16::from(byte - b'0'))
                    .min(9999);
                self.csi_has_value = true;
            }
            b';' if self.csi_intermediate == 0 => self.push_csi_parameter(),
            b'?' | b'>' | b'='
                if self.csi_count == 0
                    && !self.csi_has_value
                    && self.csi_prefix == 0
                    && self.csi_intermediate == 0 =>
            {
                self.csi_prefix = byte;
            }
            0x20..=0x2f if self.csi_intermediate == 0 => {
                self.csi_intermediate = byte;
            }
            0x20..=0x2f | b'0'..=b'9' | b';' | b':' | b'<' | b'=' | b'>' | b'?' => {
                self.csi_unsupported = true;
            }
            0x40..=0x7e => {
                self.push_csi_parameter();
                self.execute_csi(byte);
                self.parser_state = ParserState::Ground;
            }
            _ => self.parser_state = ParserState::Ground,
        }
    }

    fn reset_csi(&mut self) {
        self.csi_parameters.fill(0);
        self.csi_count = 0;
        self.csi_value = 0;
        self.csi_has_value = false;
        self.csi_prefix = 0;
        self.csi_intermediate = 0;
        self.csi_unsupported = false;
    }

    fn push_csi_parameter(&mut self) {
        if self.csi_count < MAX_CSI_PARAMETERS {
            self.csi_parameters[self.csi_count] = if self.csi_has_value {
                self.csi_value
            } else {
                0
            };
            self.csi_count += 1;
        } else {
            self.csi_unsupported = true;
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
        if self.csi_unsupported {
            return;
        }
        if self.csi_prefix == b'?' {
            self.execute_private_csi(final_byte);
            return;
        }
        if self.csi_prefix == b'>' {
            if self.csi_intermediate == 0
                && final_byte == b'c'
                && self.csi_count == 1
                && self.csi_parameters[0] == 0
            {
                self.queue_reply(b"\x1b[>0;1;0c");
            }
            return;
        }
        if self.csi_prefix != 0 {
            return;
        }
        if final_byte == b'p' {
            match self.csi_intermediate {
                b'$' => self.report_mode(false),
                b'!' if self.csi_count == 1 && self.csi_parameters[0] == 0 => self.soft_reset(),
                _ => {}
            }
            return;
        }
        if self.csi_intermediate != 0 {
            return;
        }
        self.wrap_pending = false;
        let (vertical_top, vertical_bottom) = self.vertical_bounds();
        let (horizontal_left, horizontal_right) = self.horizontal_bounds();
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
                    .min(horizontal_right);
            }
            b'D' => {
                self.cursor_column = self
                    .cursor_column
                    .saturating_sub(self.parameter(0, 1))
                    .max(horizontal_left);
            }
            b'E' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_add(self.parameter(0, 1))
                    .min(vertical_bottom);
                self.cursor_column = horizontal_left;
            }
            b'F' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_sub(self.parameter(0, 1))
                    .max(vertical_top);
                self.cursor_column = horizontal_left;
            }
            b'G' => {
                self.cursor_column = horizontal_left
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(horizontal_right)
            }
            b'H' | b'f' => {
                self.cursor_row = vertical_top
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(vertical_bottom);
                self.cursor_column = horizontal_left
                    .saturating_add(self.parameter(1, 1).saturating_sub(1))
                    .min(horizontal_right);
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
                self.cursor_column = horizontal_left
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(horizontal_right)
            }
            b'b' => self.repeat_last(self.parameter(0, 1)),
            b'd' => {
                self.cursor_row = vertical_top
                    .saturating_add(self.parameter(0, 1).saturating_sub(1))
                    .min(vertical_bottom);
            }
            b'g' => self.clear_tab_stops(self.csi_parameters[0]),
            b'm' => self.select_graphics(),
            b'n' => self.report_device_status(),
            b't' => self.report_window_operation(),
            b'c' if self.csi_count == 1 && self.csi_parameters[0] == 0 => {
                self.queue_reply(b"\x1b[?1;2c");
            }
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
                    self.cursor_column = if self.origin_mode {
                        self.scroll_left
                    } else {
                        0
                    };
                }
            }
            b's' if self.left_right_margin_mode => self.set_horizontal_margins(),
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
        if self.csi_intermediate == b'$' && final_byte == b'p' {
            self.report_mode(true);
            return;
        }
        if self.csi_intermediate != 0 {
            return;
        }
        if final_byte == b'n' {
            if self.csi_count == 1 && self.csi_parameters[0] == 6 {
                self.report_cursor_position(true);
            }
            return;
        }
        if final_byte != b'h' && final_byte != b'l' {
            return;
        }
        self.wrap_pending = false;
        let enabled = final_byte == b'h';
        for index in 0..self.csi_count {
            match self.csi_parameters[index] {
                1 => self.set_application_cursor(enabled),
                5 => self.set_reverse_screen(enabled),
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
                69 => self.set_left_right_margin_mode(enabled),
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

    fn report_device_status(&mut self) {
        if self.csi_count != 1 {
            return;
        }
        match self.csi_parameters[0] {
            5 => self.queue_reply(b"\x1b[0n"),
            6 => self.report_cursor_position(false),
            _ => {}
        }
    }

    fn report_window_operation(&mut self) {
        if self.csi_count != 1 || !matches!(self.csi_parameters[0], 18 | 19) {
            return;
        }
        let mut response = [0_u8; 24];
        let mut length = 0;
        response[length..length + 4].copy_from_slice(b"\x1b[8;");
        length += 4;
        length += write_decimal(&mut response[length..], self.rows);
        response[length] = b';';
        length += 1;
        length += write_decimal(&mut response[length..], self.columns);
        response[length] = b't';
        length += 1;
        self.queue_reply(&response[..length]);
    }

    fn report_mode(&mut self, private: bool) {
        if self.csi_count != 1 {
            return;
        }
        let mode = self.csi_parameters[0];
        let status = if private {
            match mode {
                1 => mode_status(self.application_cursor),
                5 => mode_status(self.reverse_screen),
                6 => mode_status(self.origin_mode),
                7 => mode_status(self.auto_wrap),
                25 => mode_status(self.cursor_visible),
                47 | 1047 | 1049 => mode_status(self.alternate_active),
                66 => mode_status(self.application_keypad),
                67 => mode_status(self.backarrow_key),
                69 => mode_status(self.left_right_margin_mode),
                2004 => mode_status(self.bracketed_paste),
                _ => 0,
            }
        } else {
            match mode {
                4 => mode_status(self.insert_mode),
                20 => mode_status(self.new_line_mode),
                _ => 0,
            }
        };
        let mut response = [0_u8; 24];
        let mut length = 0;
        response[length..length + 2].copy_from_slice(b"\x1b[");
        length += 2;
        if private {
            response[length] = b'?';
            length += 1;
        }
        length += write_decimal(&mut response[length..], mode);
        response[length] = b';';
        length += 1;
        length += write_decimal(&mut response[length..], status);
        response[length..length + 2].copy_from_slice(b"$y");
        length += 2;
        self.queue_reply(&response[..length]);
    }

    fn report_cursor_position(&mut self, private: bool) {
        let mut response = [0_u8; 16];
        let mut length = 0;
        response[length..length + 2].copy_from_slice(b"\x1b[");
        length += 2;
        if private {
            response[length] = b'?';
            length += 1;
        }
        let row = if self.origin_mode {
            self.cursor_row.saturating_sub(self.scroll_top) + 1
        } else {
            self.cursor_row + 1
        };
        length += write_decimal(&mut response[length..], row);
        response[length] = b';';
        length += 1;
        let column = if self.origin_mode {
            self.cursor_column.saturating_sub(self.scroll_left) + 1
        } else {
            self.cursor_column + 1
        };
        length += write_decimal(&mut response[length..], column);
        response[length] = b'R';
        length += 1;
        self.queue_reply(&response[..length]);
    }

    fn queue_reply(&mut self, reply: &[u8]) {
        if reply.len() > MAX_REPLY_BYTES - self.reply_length {
            return;
        }
        let write_start = (self.reply_start + self.reply_length) % MAX_REPLY_BYTES;
        let first = reply.len().min(MAX_REPLY_BYTES - write_start);
        self.reply_bytes[write_start..write_start + first].copy_from_slice(&reply[..first]);
        self.reply_bytes[..reply.len() - first].copy_from_slice(&reply[first..]);
        self.reply_length += reply.len();
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

    fn set_reverse_screen(&mut self, enabled: bool) {
        if self.reverse_screen != enabled {
            self.reverse_screen = enabled;
            self.mark_dirty_range(0, self.rows);
        }
    }

    fn set_left_right_margin_mode(&mut self, enabled: bool) {
        if self.left_right_margin_mode == enabled {
            return;
        }
        self.left_right_margin_mode = enabled;
        self.scroll_left = 0;
        self.scroll_right = self.columns - 1;
        self.cursor_row = if self.origin_mode { self.scroll_top } else { 0 };
        self.cursor_column = 0;
        self.wrap_pending = false;
        self.mark_dirty(self.cursor_row);
    }

    fn set_horizontal_margins(&mut self) {
        let left = self.parameter(0, 1).saturating_sub(1).min(self.columns - 1);
        let right = self
            .parameter(1, self.columns)
            .saturating_sub(1)
            .min(self.columns - 1);
        if left >= right {
            return;
        }
        self.scroll_left = left;
        self.scroll_right = right;
        self.cursor_row = if self.origin_mode { self.scroll_top } else { 0 };
        self.cursor_column = if self.origin_mode { left } else { 0 };
        self.wrap_pending = false;
        self.mark_dirty(self.cursor_row);
    }

    fn horizontal_bounds(&self) -> (u16, u16) {
        if self.left_right_margin_mode && self.origin_mode {
            (self.scroll_left, self.scroll_right)
        } else {
            (0, self.columns - 1)
        }
    }

    fn text_horizontal_bounds(&self) -> (u16, u16) {
        if self.left_right_margin_mode
            && self.cursor_column >= self.scroll_left
            && self.cursor_column <= self.scroll_right
        {
            (self.scroll_left, self.scroll_right)
        } else {
            (0, self.columns - 1)
        }
    }

    fn line_start_column(&self) -> u16 {
        self.text_horizontal_bounds().0
    }

    fn set_origin_mode(&mut self, enabled: bool) {
        self.origin_mode = enabled;
        self.cursor_row = if enabled { self.scroll_top } else { 0 };
        self.cursor_column = if enabled { self.scroll_left } else { 0 };
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
        std::mem::swap(&mut self.scroll_left, &mut self.inactive_scroll_left);
        std::mem::swap(&mut self.scroll_right, &mut self.inactive_scroll_right);
        std::mem::swap(
            &mut self.row_soft_wrapped,
            &mut self.inactive_row_soft_wrapped,
        );
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
            self.scroll_left = 0;
            self.scroll_right = self.columns - 1;
            self.row_soft_wrapped.fill(false);
        }
        self.mark_dirty_range(0, self.rows);
    }

    fn put_codepoint(&mut self, codepoint: u32) {
        if self.try_append_codepoint(codepoint) {
            return;
        }
        let (left, right) = self.text_horizontal_bounds();
        if self.auto_wrap && self.wrap_pending {
            self.row_soft_wrapped[usize::from(self.cursor_row)] = true;
            self.cursor_column = left;
            self.line_feed();
            self.wrap_pending = false;
        }
        let mut width = codepoint_width(codepoint).clamp(1, 2);
        if width == 2 && self.cursor_column + 1 > right {
            if self.auto_wrap {
                self.row_soft_wrapped[usize::from(self.cursor_row)] = true;
                self.cursor_column = left;
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
        if self.auto_wrap && last_column >= right {
            self.cursor_column = last_column;
            self.wrap_pending = true;
        } else {
            self.cursor_column = (last_column + 1).min(right);
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
        if cell.attributes & ATTRIBUTE_GRAPHEME_TRUNCATED != 0 && codepoint_width(codepoint) == 0 {
            return true;
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
            cell.attributes |= ATTRIBUTE_GRAPHEME_TRUNCATED;
            self.cells[index] = cell;
            self.last_printed = Some(cell);
            self.mark_dirty(row);
            return true;
        }

        cell.set_codepoint(length, codepoint);
        cell.grapheme_len += 1;
        let old_width = cell.width.max(1);
        let new_width = grapheme_width(&candidate[..=length]).clamp(1, 2);
        let (_, right) = self.text_horizontal_bounds();
        if new_width > old_width && column < right {
            self.clear_wide_intersections(row, column, 2);
            self.cells[index + 1] =
                Cell::continuation(cell.foreground, cell.background, cell.attributes);
            if self.wrap_pending {
                if column + 1 >= right {
                    self.cursor_column = right;
                }
            } else if self.cursor_row == row && self.cursor_column == column + 1 {
                self.cursor_column = (column + 2).min(right);
                self.wrap_pending = self.auto_wrap && column + 2 > right;
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
        let inside_horizontal_region = !self.left_right_margin_mode
            || (self.scroll_left..=self.scroll_right).contains(&self.cursor_column);
        if self.cursor_row == self.scroll_bottom && inside_horizontal_region {
            self.scroll_up();
        } else {
            self.cursor_row = (self.cursor_row + 1).min(self.rows - 1);
        }
    }

    fn scroll_up(&mut self) {
        self.scroll_up_by(1);
    }

    fn reverse_index(&mut self) {
        let inside_horizontal_region = !self.left_right_margin_mode
            || (self.scroll_left..=self.scroll_right).contains(&self.cursor_column);
        if self.cursor_row == self.scroll_top && inside_horizontal_region {
            self.scroll_down_by(1);
        } else {
            self.cursor_row = self.cursor_row.saturating_sub(1);
        }
    }

    fn insert_characters(&mut self, count: u16) {
        if self.text_horizontal_bounds() != (0, self.columns - 1) {
            self.clear_margin_intersections(self.cursor_row);
        }
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, 1);
        let row_start = self.index(self.cursor_row, 0);
        let start = row_start + usize::from(self.cursor_column);
        let end = row_start + usize::from(self.text_horizontal_bounds().1) + 1;
        let count = usize::from(count).min(end - start);
        if count == 0 {
            return;
        }
        let erased = self.erased_cell();
        self.cells.copy_within(start..end - count, start + count);
        self.cells[start..start + count].fill(erased);
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn delete_characters(&mut self, count: u16) {
        if self.text_horizontal_bounds() != (0, self.columns - 1) {
            self.clear_margin_intersections(self.cursor_row);
        }
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, count);
        let row_start = self.index(self.cursor_row, 0);
        let start = row_start + usize::from(self.cursor_column);
        let end = row_start + usize::from(self.text_horizontal_bounds().1) + 1;
        let count = usize::from(count).min(end - start);
        if count == 0 {
            return;
        }
        let erased = self.erased_cell();
        self.cells.copy_within(start + count..end, start);
        self.cells[end - count..end].fill(erased);
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn erase_characters(&mut self, count: u16) {
        if self.text_horizontal_bounds() != (0, self.columns - 1) {
            self.clear_margin_intersections(self.cursor_row);
        }
        self.clear_wide_intersections(self.cursor_row, self.cursor_column, count);
        let start = self.index(self.cursor_row, self.cursor_column);
        let count = usize::from(count).min(usize::from(
            self.text_horizontal_bounds().1 - self.cursor_column + 1,
        ));
        if count == 0 {
            return;
        }
        let erased = self.erased_cell();
        self.cells[start..start + count].fill(erased);
        normalize_cell_row(&mut self.cells, self.columns, self.cursor_row);
        self.mark_dirty(self.cursor_row);
    }

    fn insert_lines(&mut self, count: u16) {
        if !(self.scroll_top..=self.scroll_bottom).contains(&self.cursor_row)
            || (self.left_right_margin_mode
                && !(self.scroll_left..=self.scroll_right).contains(&self.cursor_column))
        {
            return;
        }
        let count = count.min(self.scroll_bottom - self.cursor_row + 1);
        if self.left_right_margin_mode
            && (self.scroll_left != 0 || self.scroll_right + 1 != self.columns)
        {
            self.scroll_rectangle_down(self.cursor_row, self.scroll_bottom, count);
            return;
        }
        let columns = usize::from(self.columns);
        let start = self.index(self.cursor_row, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        let erased = self.erased_cell();
        self.cells.copy_within(start..end - offset, start + offset);
        self.cells[start..start + offset].fill(erased);
        let row_start = usize::from(self.cursor_row);
        let row_end = usize::from(self.scroll_bottom) + 1;
        let row_count = usize::from(count);
        self.row_soft_wrapped
            .copy_within(row_start..row_end - row_count, row_start + row_count);
        self.row_soft_wrapped[row_start..row_start + row_count].fill(false);
        self.mark_dirty_range(self.cursor_row, self.scroll_bottom + 1);
    }

    fn delete_lines(&mut self, count: u16) {
        if !(self.scroll_top..=self.scroll_bottom).contains(&self.cursor_row)
            || (self.left_right_margin_mode
                && !(self.scroll_left..=self.scroll_right).contains(&self.cursor_column))
        {
            return;
        }
        let count = count.min(self.scroll_bottom - self.cursor_row + 1);
        if self.left_right_margin_mode
            && (self.scroll_left != 0 || self.scroll_right + 1 != self.columns)
        {
            self.scroll_rectangle_up(self.cursor_row, self.scroll_bottom, count);
            return;
        }
        let columns = usize::from(self.columns);
        let start = self.index(self.cursor_row, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        let erased = self.erased_cell();
        self.cells.copy_within(start + offset..end, start);
        self.cells[end - offset..end].fill(erased);
        let row_start = usize::from(self.cursor_row);
        let row_end = usize::from(self.scroll_bottom) + 1;
        let row_count = usize::from(count);
        self.row_soft_wrapped
            .copy_within(row_start + row_count..row_end, row_start);
        self.row_soft_wrapped[row_end - row_count..row_end].fill(false);
        self.mark_dirty_range(self.cursor_row, self.scroll_bottom + 1);
    }

    fn scroll_up_by(&mut self, count: u16) {
        let count = count.min(self.scroll_bottom - self.scroll_top + 1);
        if self.left_right_margin_mode
            && (self.scroll_left != 0 || self.scroll_right + 1 != self.columns)
        {
            self.scroll_rectangle_up(self.scroll_top, self.scroll_bottom, count);
            return;
        }
        let columns = usize::from(self.columns);
        if !self.alternate_active && self.scroll_top == 0 && self.scroll_bottom + 1 == self.rows {
            for row in 0..count {
                let start = self.index(row, 0);
                self.scrollback.append_line(
                    &self.cells[start..start + columns],
                    self.row_soft_wrapped[usize::from(row)],
                    self.columns,
                );
            }
        }
        let top = self.index(self.scroll_top, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        let erased = self.erased_cell();
        self.cells.copy_within(top + offset..end, top);
        self.cells[end - offset..end].fill(erased);
        let row_start = usize::from(self.scroll_top);
        let row_end = usize::from(self.scroll_bottom) + 1;
        let row_count = usize::from(count);
        self.row_soft_wrapped
            .copy_within(row_start + row_count..row_end, row_start);
        self.row_soft_wrapped[row_end - row_count..row_end].fill(false);
        self.mark_dirty_range(self.scroll_top, self.scroll_bottom + 1);
    }

    fn scroll_down_by(&mut self, count: u16) {
        let count = count.min(self.scroll_bottom - self.scroll_top + 1);
        if self.left_right_margin_mode
            && (self.scroll_left != 0 || self.scroll_right + 1 != self.columns)
        {
            self.scroll_rectangle_down(self.scroll_top, self.scroll_bottom, count);
            return;
        }
        let columns = usize::from(self.columns);
        let top = self.index(self.scroll_top, 0);
        let end = self.index(self.scroll_bottom + 1, 0);
        let offset = usize::from(count) * columns;
        let erased = self.erased_cell();
        self.cells.copy_within(top..end - offset, top + offset);
        self.cells[top..top + offset].fill(erased);
        let row_start = usize::from(self.scroll_top);
        let row_end = usize::from(self.scroll_bottom) + 1;
        let row_count = usize::from(count);
        self.row_soft_wrapped
            .copy_within(row_start..row_end - row_count, row_start + row_count);
        self.row_soft_wrapped[row_start..row_start + row_count].fill(false);
        self.mark_dirty_range(self.scroll_top, self.scroll_bottom + 1);
    }

    fn scroll_rectangle_up(&mut self, top: u16, bottom: u16, count: u16) {
        let height = bottom - top + 1;
        let count = count.min(height);
        let left = usize::from(self.scroll_left);
        let right = usize::from(self.scroll_right) + 1;
        let erased = self.erased_cell();
        for row in top..=bottom {
            self.clear_margin_intersections(row);
        }
        if count < height {
            for row in top..=bottom - count {
                let source = self.index(row + count, 0);
                let destination = self.index(row, 0);
                self.cells
                    .copy_within(source + left..source + right, destination + left);
            }
        }
        for row in bottom - count + 1..=bottom {
            let start = self.index(row, 0);
            self.cells[start + left..start + right].fill(erased);
        }
        for row in top..=bottom {
            normalize_cell_row(&mut self.cells, self.columns, row);
            self.row_soft_wrapped[usize::from(row)] = false;
        }
        self.mark_dirty_range(top, bottom + 1);
    }

    fn scroll_rectangle_down(&mut self, top: u16, bottom: u16, count: u16) {
        let count = count.min(bottom - top + 1);
        let left = usize::from(self.scroll_left);
        let right = usize::from(self.scroll_right) + 1;
        let erased = self.erased_cell();
        for row in top..=bottom {
            self.clear_margin_intersections(row);
        }
        for row in (top + count..=bottom).rev() {
            let source = self.index(row - count, 0);
            let destination = self.index(row, 0);
            self.cells
                .copy_within(source + left..source + right, destination + left);
        }
        for row in top..top + count {
            let start = self.index(row, 0);
            self.cells[start + left..start + right].fill(erased);
        }
        for row in top..=bottom {
            normalize_cell_row(&mut self.cells, self.columns, row);
            self.row_soft_wrapped[usize::from(row)] = false;
        }
        self.mark_dirty_range(top, bottom + 1);
    }

    fn tab_forward(&mut self, count: u16) {
        let (_, right) = self.horizontal_bounds();
        for _ in 0..count.min(self.columns) {
            let mut next = self.cursor_column + 1;
            while next <= right && !self.tab_stops[usize::from(next)] {
                next += 1;
            }
            self.cursor_column = next.min(right);
        }
    }

    fn tab_backward(&mut self, count: u16) {
        let (left, _) = self.horizontal_bounds();
        for _ in 0..count.min(self.columns) {
            let mut previous = self.cursor_column.saturating_sub(1);
            while previous > left && !self.tab_stops[usize::from(previous)] {
                previous -= 1;
            }
            self.cursor_column = previous.max(left);
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
        let erased = self.erased_cell();
        match mode {
            0 => {
                let start = self.index(self.cursor_row, self.cursor_column);
                self.clear_wide_intersections(
                    self.cursor_row,
                    self.cursor_column,
                    self.columns - self.cursor_column,
                );
                self.cells[start..].fill(erased);
                for row in self.cursor_row..self.rows {
                    normalize_cell_row(&mut self.cells, self.columns, row);
                }
                self.row_soft_wrapped[usize::from(self.cursor_row)..].fill(false);
                self.mark_dirty_range(self.cursor_row, self.rows);
            }
            1 => {
                let end = self.index(self.cursor_row, self.cursor_column) + 1;
                self.clear_wide_intersections(self.cursor_row, 0, self.cursor_column + 1);
                self.cells[..end].fill(erased);
                for row in 0..=self.cursor_row {
                    normalize_cell_row(&mut self.cells, self.columns, row);
                }
                self.row_soft_wrapped[..=usize::from(self.cursor_row)].fill(false);
                self.mark_dirty_range(0, self.cursor_row + 1);
            }
            2 => {
                self.cells.fill(erased);
                self.row_soft_wrapped.fill(false);
                self.mark_dirty_range(0, self.rows);
            }
            3 => {
                self.scrollback.clear();
                self.mark_dirty_range(0, self.rows);
            }
            _ => {}
        }
    }

    fn erase_line(&mut self, mode: u16) {
        let start = self.index(self.cursor_row, 0);
        let column = usize::from(self.cursor_column);
        let (left_column, right_column) = self.text_horizontal_bounds();
        let left = start + usize::from(left_column);
        let end = start + usize::from(right_column) + 1;
        let erased = self.erased_cell();
        match mode {
            0 => {
                self.clear_wide_intersections(
                    self.cursor_row,
                    self.cursor_column,
                    right_column - self.cursor_column + 1,
                );
                self.cells[start + column..end].fill(erased);
            }
            1 => {
                self.clear_wide_intersections(
                    self.cursor_row,
                    left_column,
                    self.cursor_column - left_column + 1,
                );
                self.cells[left..=start + column].fill(erased);
            }
            2 => {
                self.clear_margin_intersections(self.cursor_row);
                self.cells[left..end].fill(erased);
                self.row_soft_wrapped[usize::from(self.cursor_row)] = false;
            }
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
                1 => self.attributes |= ATTRIBUTE_BOLD,
                2 => self.attributes |= ATTRIBUTE_FAINT,
                3 => self.attributes |= ATTRIBUTE_ITALIC,
                4 | 21 => self.attributes |= ATTRIBUTE_UNDERLINE,
                7 => self.attributes |= ATTRIBUTE_INVERSE,
                8 => self.attributes |= ATTRIBUTE_HIDDEN,
                9 => self.attributes |= ATTRIBUTE_STRIKE,
                22 => self.attributes &= !(ATTRIBUTE_BOLD | ATTRIBUTE_FAINT),
                23 => self.attributes &= !ATTRIBUTE_ITALIC,
                24 => self.attributes &= !ATTRIBUTE_UNDERLINE,
                27 => self.attributes &= !ATTRIBUTE_INVERSE,
                28 => self.attributes &= !ATTRIBUTE_HIDDEN,
                29 => self.attributes &= !ATTRIBUTE_STRIKE,
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
        self.scroll_left = 0;
        self.scroll_right = self.columns - 1;
        self.inactive_cursor_row = 0;
        self.inactive_cursor_column = 0;
        self.inactive_wrap_pending = false;
        self.inactive_saved_row = 0;
        self.inactive_saved_column = 0;
        self.inactive_scroll_top = 0;
        self.inactive_scroll_bottom = self.rows - 1;
        self.inactive_scroll_left = 0;
        self.inactive_scroll_right = self.columns - 1;
        self.row_soft_wrapped.fill(false);
        self.inactive_row_soft_wrapped.fill(false);
        self.cursor_visible = true;
        self.application_cursor = false;
        self.application_keypad = false;
        self.bracketed_paste = false;
        self.new_line_mode = false;
        self.backarrow_key = false;
        self.reverse_screen = false;
        self.left_right_margin_mode = false;
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

    fn soft_reset(&mut self) {
        self.cursor_visible = true;
        self.application_cursor = false;
        self.application_keypad = false;
        self.bracketed_paste = false;
        self.new_line_mode = false;
        self.backarrow_key = false;
        self.reverse_screen = false;
        self.left_right_margin_mode = false;
        self.insert_mode = false;
        self.origin_mode = false;
        self.auto_wrap = true;
        self.scroll_top = 0;
        self.scroll_bottom = self.rows - 1;
        self.scroll_left = 0;
        self.scroll_right = self.columns - 1;
        self.wrap_pending = false;
        self.saved_row = 0;
        self.saved_column = 0;
        self.last_printed = None;
        self.g0_charset = Charset::Ascii;
        self.g1_charset = Charset::Ascii;
        self.use_g1 = false;
        self.foreground = DEFAULT_FOREGROUND;
        self.background = DEFAULT_BACKGROUND;
        self.attributes = 0;
        self.utf8_remaining = 0;
        self.cursor_row = self.cursor_row.min(self.rows - 1);
        self.cursor_column = self.cursor_column.min(self.columns - 1);
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

    fn erased_cell(&self) -> Cell {
        Cell {
            background: self.background,
            ..Cell::blank()
        }
    }

    fn clear_margin_intersections(&mut self, row: u16) {
        let erased = self.erased_cell();
        if self.scroll_left > 0 {
            let previous = self.index(row, self.scroll_left - 1);
            if self.cells[previous].width == 2 {
                self.cells[previous] = erased;
                self.cells[previous + 1] = erased;
            }
        }
        if self.scroll_right + 1 < self.columns {
            let right = self.index(row, self.scroll_right);
            if self.cells[right].width == 2 {
                self.cells[right] = erased;
                self.cells[right + 1] = erased;
            }
        }
    }

    fn clear_wide_intersections(&mut self, row: u16, column: u16, count: u16) {
        if count == 0 || row >= self.rows || column >= self.columns {
            return;
        }
        let start = column;
        let end = column.saturating_add(count).min(self.columns);
        let erased = self.erased_cell();
        if start > 0 {
            let previous = self.index(row, start - 1);
            if self.cells[previous].width == 2 {
                self.cells[previous] = erased;
                self.cells[previous + 1] = erased;
            }
        }
        for current in start..end {
            let index = self.index(row, current);
            if self.cells[index].width == 2 {
                self.cells[index] = erased;
                if current + 1 < self.columns {
                    self.cells[index + 1] = erased;
                }
            } else if self.cells[index].width == 0 {
                self.cells[index] = erased;
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

fn resize_row_flags(source: &[bool], rows: u16, source_row: u16) -> Vec<bool> {
    let mut replacement = vec![false; usize::from(rows)];
    let available = source.len().saturating_sub(usize::from(source_row));
    let copied = replacement.len().min(available);
    replacement[..copied]
        .copy_from_slice(&source[usize::from(source_row)..usize::from(source_row) + copied]);
    replacement
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

fn cell_is_text_blank(cell: Cell) -> bool {
    cell.width == 1 && cell.grapheme_len == 1 && cell.codepoint == u32::from(' ')
}

fn write_wire_cell(
    output: &mut [u8],
    offset: usize,
    mut cell: Cell,
    palette_colors: &[u32; 256],
    palette_overridden: &[bool; 256],
) {
    cell.foreground = wire_color(cell.foreground, palette_colors, palette_overridden);
    cell.background = wire_color(cell.background, palette_colors, palette_overridden);
    for codepoint_index in 0..MAX_GRAPHEME_CODEPOINTS {
        let start = offset + codepoint_index * 4;
        output[start..start + 4].copy_from_slice(&cell.codepoint(codepoint_index).to_le_bytes());
    }
    output[offset + 64..offset + 68].copy_from_slice(&cell.foreground.to_le_bytes());
    output[offset + 68..offset + 72].copy_from_slice(&cell.background.to_le_bytes());
    output[offset + 72] = cell.attributes;
    output[offset + 73] = cell.width;
    output[offset + 74] = cell.grapheme_len;
}

fn wire_color(color: u32, palette_colors: &[u32; 256], palette_overridden: &[bool; 256]) -> u32 {
    if color & DIRECT_COLOR_FLAG != 0 {
        return color;
    }
    let index = color.min(255) as usize;
    if palette_overridden[index] {
        DIRECT_COLOR_FLAG | palette_colors[index]
    } else {
        color
    }
}

fn write_decimal(output: &mut [u8], value: u16) -> usize {
    let mut reversed = [0_u8; 5];
    let mut value = value;
    let mut length = 0;
    loop {
        reversed[length] = b'0' + (value % 10) as u8;
        length += 1;
        value /= 10;
        if value == 0 {
            break;
        }
    }
    for index in 0..length {
        output[index] = reversed[length - index - 1];
    }
    length
}

fn write_hex_u16(output: &mut [u8], value: u16) -> usize {
    for (index, shift) in [12, 8, 4, 0].into_iter().enumerate() {
        output[index] = hex_digit(((value >> shift) & 0xf) as u8);
    }
    4
}

const fn hex_digit(value: u8) -> u8 {
    if value < 10 {
        b'0' + value
    } else {
        b'a' + value - 10
    }
}

fn parse_palette_index(bytes: &[u8]) -> Option<u8> {
    if bytes.is_empty() || bytes.len() > 3 {
        return None;
    }
    let mut value = 0_u16;
    for byte in bytes {
        if !byte.is_ascii_digit() {
            return None;
        }
        value = value
            .checked_mul(10)?
            .checked_add(u16::from(*byte - b'0'))?;
    }
    u8::try_from(value).ok()
}

fn parse_palette_color(bytes: &[u8]) -> Option<u32> {
    if let Some(components) = bytes.strip_prefix(b"rgb:") {
        let mut fields = components.split(|byte| *byte == b'/');
        let red = parse_palette_component(fields.next()?)?;
        let green = parse_palette_component(fields.next()?)?;
        let blue = parse_palette_component(fields.next()?)?;
        if fields.next().is_some() {
            return None;
        }
        return Some((u32::from(red) << 16) | (u32::from(green) << 8) | u32::from(blue));
    }
    let components = bytes.strip_prefix(b"#")?;
    if components.len() % 3 != 0 {
        return None;
    }
    let component_length = components.len() / 3;
    if !(1..=4).contains(&component_length) {
        return None;
    }
    let red = parse_palette_component(&components[..component_length])?;
    let green = parse_palette_component(&components[component_length..component_length * 2])?;
    let blue = parse_palette_component(&components[component_length * 2..])?;
    Some((u32::from(red) << 16) | (u32::from(green) << 8) | u32::from(blue))
}

fn parse_palette_component(bytes: &[u8]) -> Option<u8> {
    if bytes.is_empty() || bytes.len() > 4 {
        return None;
    }
    let mut value = 0_u32;
    for byte in bytes {
        value = (value << 4) | u32::from(parse_hex_digit(*byte)?);
    }
    let maximum = (1_u32 << (bytes.len() * 4)) - 1;
    Some(((value * 255 + maximum / 2) / maximum) as u8)
}

const fn parse_hex_digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

const fn default_palette_color(index: u8) -> u32 {
    const BASIC: [u32; 16] = [
        0x1f2326, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xcdd6f4, 0x585b70,
        0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xffffff,
    ];
    if index < 16 {
        BASIC[index as usize]
    } else if index < 232 {
        let cube = index - 16;
        let red = ansi_cube_component(cube / 36);
        let green = ansi_cube_component((cube % 36) / 6);
        let blue = ansi_cube_component(cube % 6);
        (red << 16) | (green << 8) | blue
    } else {
        let level = 8 + (index as u32 - 232) * 10;
        (level << 16) | (level << 8) | level
    }
}

const fn ansi_cube_component(index: u8) -> u32 {
    match index {
        0 => 0,
        1 => 95,
        2 => 135,
        3 => 175,
        4 => 215,
        _ => 255,
    }
}

const fn mode_status(enabled: bool) -> u16 {
    if enabled { 1 } else { 2 }
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

    fn damage_text(damage: &[u8], row: u16, columns: u16) -> String {
        (0..columns)
            .map(|column| {
                let offset = DAMAGE_HEADER_SIZE
                    + (usize::from(row) * usize::from(columns) + usize::from(column))
                        * DAMAGE_CELL_SIZE;
                let grapheme_len = damage[offset + 74];
                if grapheme_len == 0 {
                    ' '
                } else {
                    char::from_u32(u32::from_le_bytes(
                        damage[offset..offset + 4].try_into().unwrap(),
                    ))
                    .unwrap_or('\u{fffd}')
                }
            })
            .collect()
    }

    fn ascii_cells(value: &[u8]) -> Vec<Cell> {
        value
            .iter()
            .map(|byte| {
                let mut cell = Cell::blank();
                cell.codepoint = u32::from(*byte);
                cell
            })
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
    fn overlong_graphemes_are_visibly_truncated_without_unbounded_growth() {
        let mut terminal = Terminal::new(2, 8).unwrap();
        let mut input = String::from("a");
        for _ in 0..32 {
            input.push('\u{301}');
        }
        input.push('Z');
        terminal.feed(input.as_bytes());

        let cell = terminal.cell(0, 0).unwrap();
        assert_eq!(usize::from(cell.grapheme_len), MAX_GRAPHEME_CODEPOINTS);
        assert_eq!(
            cell.codepoint(MAX_GRAPHEME_CODEPOINTS - 1),
            u32::from('\u{fffd}')
        );
        assert_ne!(cell.attributes & ATTRIBUTE_GRAPHEME_TRUNCATED, 0);
        assert_eq!(terminal.cell(0, 1).unwrap().codepoint, u32::from('Z'));
        assert_eq!(terminal.cursor(), (0, 2));
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
    fn fullscreen_scrolls_publish_bounded_history_viewports() {
        let mut terminal = Terminal::new(3, 8).unwrap();
        terminal.feed(b"one\r\ntwo\r\nthree\r\nfour");
        assert_eq!(terminal.history_rows(), 1);
        assert_eq!(text(&terminal, 0), "two     ");
        assert_eq!(text(&terminal, 1), "three   ");
        assert_eq!(text(&terminal, 2), "four    ");

        let mut damage = vec![0_u8; MAX_DAMAGE_BYTES];
        let length = terminal.write_view_damage(&mut damage, 1).unwrap();
        assert_eq!(length, DAMAGE_HEADER_SIZE + 3 * 8 * DAMAGE_CELL_SIZE);
        assert_eq!(u32::from_le_bytes(damage[32..36].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(damage[36..40].try_into().unwrap()), 1);
        assert_eq!(
            u32::from_le_bytes(damage[20..24].try_into().unwrap()) & FLAG_CURSOR_VISIBLE,
            0
        );
        assert_eq!(damage_text(&damage, 0, 8), "one     ");
        assert_eq!(damage_text(&damage, 1, 8), "two     ");
        assert_eq!(damage_text(&damage, 2, 8), "three   ");
    }

    #[test]
    fn selection_spans_history_and_live_rows_without_soft_wrap_newlines() {
        let mut terminal = Terminal::new(3, 8).unwrap();
        terminal.feed(b"abcdefghijk\r\nsecond\r\nthird\r\nfourth");
        assert!(terminal.history_rows() >= 2);
        let history_rows = terminal.history_rows();
        let mut output = [0_u8; MAX_SELECTION_BYTES];
        let length = terminal
            .write_selection(
                &mut output,
                terminal.history_origin_epoch(),
                0,
                0,
                history_rows,
                5,
            )
            .unwrap();
        let selected = std::str::from_utf8(&output[..length]).unwrap();
        assert_eq!(selected, "abcdefghijk\nsecond");
    }

    #[test]
    fn selection_rejects_stale_history_origin_after_eviction() {
        let mut terminal = Terminal::new(2, MAX_COLUMNS).unwrap();
        let origin = terminal.history_origin_epoch();
        let line = vec![b'x'; usize::from(MAX_COLUMNS)];
        for _ in 0..5_000 {
            terminal.feed(&line);
            terminal.feed(b"\r\n");
        }
        assert!(terminal.history_origin_epoch() > origin);
        let mut output = [0_u8; 32];
        assert_eq!(
            terminal.write_selection(&mut output, origin, 0, 0, 0, 0),
            Err(TerminalError::InvalidSelection)
        );
    }

    #[test]
    fn stored_physical_rows_rewrap_when_the_viewport_narrows() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        terminal.feed(b"abcdef\x1b[S");
        assert_eq!(terminal.history_rows(), 1);
        terminal.resize(2, 3).unwrap();
        assert_eq!(terminal.history_rows(), 2);

        let mut damage = vec![0_u8; MAX_DAMAGE_BYTES];
        terminal.write_view_damage(&mut damage, 2).unwrap();
        assert_eq!(damage_text(&damage, 0, 3), "abc");
        assert_eq!(damage_text(&damage, 1, 3), "def");
    }

    #[test]
    fn soft_wrapped_history_joins_and_reflows_as_one_logical_line() {
        let mut scrollback = Scrollback::new();
        scrollback.append_line(&ascii_cells(b"abcdef"), true, 6);
        scrollback.append_line(&ascii_cells(b"ghijkl"), true, 6);
        scrollback.append_line(&ascii_cells(b"mnop"), false, 6);
        assert_eq!(scrollback.line_count, 1);
        assert_eq!(scrollback.visual_rows(6), 3);
        assert_eq!(scrollback.visual_rows(4), 4);

        let mut output = vec![Cell::blank(); 4];
        for (row, expected) in [b"abcd", b"efgh", b"ijkl", b"mnop"].iter().enumerate() {
            assert!(scrollback.fill_visual_row(row as u32, 4, &mut output));
            let actual: Vec<u8> = output.iter().map(|cell| cell.codepoint as u8).collect();
            assert_eq!(&actual, expected);
        }
    }

    #[test]
    fn terminal_scrolls_consecutive_soft_rows_into_one_logical_history_line() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        terminal.feed(b"abcdefghijklmnopqrs");
        assert_eq!(terminal.scrollback.line_count, 1);
        assert_eq!(terminal.history_rows(), 2);

        terminal.resize(2, 4).unwrap();
        assert_eq!(terminal.history_rows(), 3);
        let mut damage = vec![0_u8; MAX_DAMAGE_BYTES];
        terminal.write_view_damage(&mut damage, 2).unwrap();
        assert_eq!(damage_text(&damage, 0, 4), "efgh");
        assert_eq!(damage_text(&damage, 1, 4), "ijkl");
        assert_eq!(text(&terminal, 0), "mnop");
        assert_eq!(text(&terminal, 1), "qrs ");
        assert_eq!(terminal.cursor(), (1, 3));

        terminal.resize(2, 6).unwrap();
        assert_eq!(terminal.history_rows(), 2);
        assert_eq!(text(&terminal, 0), "mnopqr");
        assert_eq!(text(&terminal, 1), "s     ");
        assert_eq!(terminal.cursor(), (1, 1));
    }

    #[test]
    fn widening_reflows_one_logical_line_across_history_and_live_screen() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        terminal.feed(b"abcdefghijklmnopqrs");
        assert_eq!(terminal.history_rows(), 2);
        assert_eq!(terminal.cursor(), (1, 1));

        terminal.resize(2, 10).unwrap();
        assert_eq!(terminal.history_rows(), 0);
        assert_eq!(text(&terminal, 0), "abcdefghij");
        assert_eq!(text(&terminal, 1), "klmnopqrs ");
        assert_eq!(terminal.cursor(), (1, 9));
        assert!(terminal.row_soft_wrapped[0]);
        assert!(!terminal.row_soft_wrapped[1]);
    }

    #[test]
    fn boundary_reflow_keeps_wide_graphemes_whole_and_cursor_visible() {
        let mut terminal = Terminal::new(2, 4).unwrap();
        terminal.feed("界界界界界".as_bytes());
        assert_eq!(terminal.history_rows(), 1);

        terminal.resize(2, 6).unwrap();
        assert_eq!(terminal.history_rows(), 0);
        for column in [0, 2, 4] {
            assert_eq!(terminal.cell(0, column).unwrap().codepoint, '界' as u32);
            assert_eq!(terminal.cell(0, column + 1).unwrap().width, 0);
        }
        for column in [0, 2] {
            assert_eq!(terminal.cell(1, column).unwrap().codepoint, '界' as u32);
            assert_eq!(terminal.cell(1, column + 1).unwrap().width, 0);
        }
        assert_eq!(terminal.cursor(), (1, 4));
    }

    #[test]
    fn logical_history_reflow_never_splits_wide_graphemes() {
        let wide = Cell {
            codepoint: '\u{754c}' as u32,
            trailing_codepoints: [0; MAX_GRAPHEME_CODEPOINTS - 1],
            foreground: DEFAULT_FOREGROUND,
            background: DEFAULT_BACKGROUND,
            attributes: 0,
            grapheme_len: 1,
            width: 2,
        };
        let continuation = Cell::continuation(DEFAULT_FOREGROUND, DEFAULT_BACKGROUND, 0);
        let row = [wide, continuation, wide, continuation];
        let mut scrollback = Scrollback::new();
        scrollback.append_line(&row, true, 4);
        scrollback.append_line(&row, false, 4);
        assert_eq!(scrollback.line_count, 1);
        assert_eq!(scrollback.visual_rows(3), 4);

        let mut output = vec![Cell::blank(); 3];
        for row in 0..4 {
            assert!(scrollback.fill_visual_row(row, 3, &mut output));
            assert_eq!(output[0], wide);
            assert_eq!(output[1], continuation);
            assert_eq!(output[2], Cell::blank());
        }
    }

    #[test]
    fn hard_newline_history_does_not_reflow_default_trailing_blanks() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        terminal.feed(b"abc\r\ndef\r\n");
        assert_eq!(terminal.history_rows(), 1);
        terminal.resize(2, 2).unwrap();
        assert_eq!(terminal.history_rows(), 3);

        let mut damage = vec![0_u8; MAX_DAMAGE_BYTES];
        terminal.write_view_damage(&mut damage, 3).unwrap();
        assert_eq!(damage_text(&damage, 0, 2), "ab");
        assert_eq!(damage_text(&damage, 1, 2), "c ");
    }

    #[test]
    fn scrollback_ring_evicts_and_wraps_without_losing_newest_cells() {
        let mut scrollback = Scrollback::new();
        let mut line = vec![Cell::blank(); usize::from(MAX_COLUMNS)];
        for sequence in 0..700_u32 {
            line[0].codepoint = 0x1000 + sequence;
            scrollback.append_line(&line, true, MAX_COLUMNS);
        }
        assert!(scrollback.line_count < 700);
        assert!(scrollback.bytes_used <= SCROLLBACK_BYTE_LIMIT);

        let mut output = vec![Cell::blank(); usize::from(MAX_COLUMNS)];
        assert!(scrollback.fill_visual_row(
            scrollback.visual_rows(MAX_COLUMNS) - 1,
            MAX_COLUMNS,
            &mut output,
        ));
        assert_eq!(output[0].codepoint, 0x1000 + 699);
        assert!(scrollback.fill_visual_row(0, MAX_COLUMNS, &mut output));
        assert_ne!(output[0].codepoint, 0x1000);
    }

    #[test]
    fn alternate_and_partial_region_scrolls_do_not_enter_primary_history() {
        let mut terminal = Terminal::new(4, 6).unwrap();
        terminal.feed(b"\x1b[2;3r\x1b[S");
        assert_eq!(terminal.history_rows(), 0);
        terminal.feed(b"\x1b[r\x1b[?1049h\x1b[S\x1b[?1049l");
        assert_eq!(terminal.history_rows(), 0);
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
    fn osc_palette_changes_are_bounded_queryable_and_resettable() {
        let mut terminal = Terminal::new(2, 6).unwrap();
        let mut damage = [0_u8; 2048];
        terminal.feed(b"\x1b[48;5;25mA");
        terminal.write_damage(&mut damage).unwrap();
        assert_eq!(terminal.cell(0, 0).unwrap().background, 25);

        terminal.feed(b"\x1b]4;25;rgb:12/34/56\x07");
        let length = terminal.write_damage(&mut damage).unwrap();
        assert_eq!(length, DAMAGE_HEADER_SIZE + 2 * 6 * DAMAGE_CELL_SIZE);
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + 68..DAMAGE_HEADER_SIZE + 72]
                    .try_into()
                    .unwrap()
            ),
            DIRECT_COLOR_FLAG | 0x123456
        );
        assert_eq!(terminal.cell(0, 0).unwrap().background, 25);

        terminal.feed(b"\x1b]4;25;?\x1b\\");
        assert_eq!(
            terminal.pending_reply(),
            b"\x1b]4;25;rgb:1212/3434/5656\x1b\\"
        );
        terminal.consume_reply(usize::MAX);

        terminal.feed(b"\x1b]4;25;#abc;196;#ff0000\x07");
        assert_eq!(terminal.palette_color(25), 0xaabbcc);
        assert_eq!(terminal.palette_color(196), 0xff0000);
        terminal.feed(b"\x1b]104;25\x07");
        assert!(!terminal.palette_overridden[25]);
        assert!(terminal.palette_overridden[196]);
        terminal.write_damage(&mut damage).unwrap();
        assert_eq!(
            u32::from_le_bytes(
                damage[DAMAGE_HEADER_SIZE + 68..DAMAGE_HEADER_SIZE + 72]
                    .try_into()
                    .unwrap()
            ),
            25
        );

        terminal.feed(b"\x1b]4;999;#ffffff\x07\x1b]4;25;not-a-color\x07");
        assert!(!terminal.palette_overridden[25]);
        terminal.feed(b"\x1b]104\x07");
        assert!(!terminal.palette_overridden.iter().any(|value| *value));
        terminal.feed(b"\x1b]4;16;?\x07");
        assert_eq!(
            terminal.pending_reply(),
            b"\x1b]4;16;rgb:0000/0000/0000\x1b\\"
        );
    }

    #[test]
    fn background_color_erase_applies_to_edits_and_new_scroll_rows() {
        let mut terminal = Terminal::new(3, 6).unwrap();
        terminal.feed(b"abcdef\x1b[1;3H\x1b[48;5;25m\x1b[K");
        assert_eq!(terminal.cell(0, 1).unwrap().background, DEFAULT_BACKGROUND);
        for column in 2..6 {
            let cell = terminal.cell(0, column).unwrap();
            assert_eq!(cell.codepoint, u32::from(' '));
            assert_eq!(cell.foreground, DEFAULT_FOREGROUND);
            assert_eq!(cell.background, 25);
            assert_eq!(cell.attributes, 0);
        }

        terminal.feed(b"\x1b[0m\x1b[2;1Habcdef\x1b[2;3H\x1b[48;5;46m\x1b[2X");
        assert_eq!(terminal.cell(1, 2).unwrap().background, 46);
        assert_eq!(terminal.cell(1, 3).unwrap().background, 46);
        assert_eq!(terminal.cell(1, 4).unwrap().background, DEFAULT_BACKGROUND);
        terminal.feed(b"\x1b[P");
        assert_eq!(terminal.cell(1, 2).unwrap().background, 46);
        assert_eq!(terminal.cell(1, 3).unwrap().background, DEFAULT_BACKGROUND);
        assert_eq!(terminal.cell(1, 4).unwrap().background, DEFAULT_BACKGROUND);
        assert_eq!(terminal.cell(1, 5).unwrap().background, 46);

        terminal.feed(b"\x1b[48;5;21m\x1b[3;1H\n");
        for column in 0..6 {
            assert_eq!(terminal.cell(2, column).unwrap().background, 21);
        }
    }

    #[test]
    fn sgr_styles_set_and_reset_independently() {
        let mut terminal = Terminal::new(2, 8).unwrap();
        terminal.feed(b"\x1b[1;2;3;4;7;8;9mA\x1b[22;23;24;27;28;29mB");
        assert_eq!(
            terminal.cell(0, 0).unwrap().attributes,
            ATTRIBUTE_BOLD
                | ATTRIBUTE_FAINT
                | ATTRIBUTE_ITALIC
                | ATTRIBUTE_UNDERLINE
                | ATTRIBUTE_INVERSE
                | ATTRIBUTE_HIDDEN
                | ATTRIBUTE_STRIKE
        );
        assert_eq!(terminal.cell(0, 1).unwrap().attributes, 0);
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
    fn reverse_screen_and_soft_reset_follow_the_advertised_xterm_contract() {
        let mut terminal = Terminal::new(4, 8).unwrap();
        let mut output = [0_u8; 4096];
        terminal.feed(b"keep");
        terminal.write_damage(&mut output).unwrap();

        terminal.feed(
            b"\x1b[2;3r\x1b[?1;5;6;67;69;2004h\x1b[2;4H\x1b=\x1b[4;20h\
              \x1b[31;44;1m\x1b(0\x1b[?25l\x1b[?5$p",
        );
        assert_eq!(terminal.pending_reply(), b"\x1b[?5;1$y");
        terminal.consume_reply(usize::MAX);
        let length = terminal.write_damage(&mut output).unwrap();
        assert_eq!(length, DAMAGE_HEADER_SIZE + 4 * 8 * DAMAGE_CELL_SIZE);
        assert_eq!(
            u32::from_le_bytes(output[20..24].try_into().unwrap()),
            FLAG_APPLICATION_CURSOR
                | FLAG_APPLICATION_KEYPAD
                | FLAG_BRACKETED_PASTE
                | FLAG_NEW_LINE_MODE
                | FLAG_BACKARROW_KEY
                | FLAG_REVERSE_SCREEN
        );

        terminal.feed(b"\x1b[!p\x1b[?5$p");
        assert_eq!(text(&terminal, 0), "keep    ");
        assert_eq!(terminal.cursor(), (2, 3));
        assert_eq!(terminal.scroll_top, 0);
        assert_eq!(terminal.scroll_bottom, 3);
        assert_eq!(terminal.saved_row, 0);
        assert_eq!(terminal.saved_column, 0);
        assert!(terminal.cursor_visible);
        assert!(!terminal.application_cursor);
        assert!(!terminal.application_keypad);
        assert!(!terminal.bracketed_paste);
        assert!(!terminal.new_line_mode);
        assert!(!terminal.backarrow_key);
        assert!(!terminal.reverse_screen);
        assert!(!terminal.left_right_margin_mode);
        assert_eq!(terminal.scroll_left, 0);
        assert_eq!(terminal.scroll_right, 7);
        assert!(!terminal.insert_mode);
        assert!(!terminal.origin_mode);
        assert!(terminal.auto_wrap);
        assert_eq!(terminal.g0_charset, Charset::Ascii);
        assert_eq!(terminal.pending_reply(), b"\x1b[?5;2$y");
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
    fn left_right_margins_bound_wrap_edit_and_rectangular_scroll() {
        let mut terminal = Terminal::new(5, 8).unwrap();
        terminal.feed(
            b"\x1b[1;1H00000000\x1b[2;1H11111111\x1b[3;1H22222222\
              \x1b[4;1H33333333\x1b[5;1H44444444\
              \x1b[?69h\x1b[3;6s\x1b[2;4r\x1b[?6habcdefghijklmn",
        );
        assert_eq!(text(&terminal, 0), "00000000");
        assert_eq!(text(&terminal, 1), "11efgh11");
        assert_eq!(text(&terminal, 2), "22ijkl22");
        assert_eq!(text(&terminal, 3), "33mn  33");
        assert_eq!(text(&terminal, 4), "44444444");
        assert_eq!(terminal.cursor(), (3, 4));
        assert_eq!(terminal.history_rows(), 0);
        terminal.feed(b"\x1b[6n");
        assert_eq!(terminal.pending_reply(), b"\x1b[3;3R");
        terminal.consume_reply(usize::MAX);

        terminal.feed(b"\x1b[2;2H\x1b[2@\x1b[2P\x1b[?69$p");
        assert_eq!(text(&terminal, 1), "11efgh11");
        assert_eq!(text(&terminal, 2), "22ij  22");
        assert_eq!(terminal.pending_reply(), b"\x1b[?69;1$y");
        terminal.consume_reply(usize::MAX);

        terminal.feed(b"\x1b[4;5H\xe7\x95\x8c");
        assert_eq!(text(&terminal, 1), "11ij  11");
        assert_eq!(text(&terminal, 2), "22mn  22");
        assert_eq!(text(&terminal, 3), "33界   33");
        assert_eq!(grapheme(&terminal, 3, 2), "界");
        assert_eq!(terminal.cell(3, 2).unwrap().width, 2);

        terminal.feed(b"\x1b[?69l\x1b[?69$p");
        assert_eq!(terminal.scroll_left, 0);
        assert_eq!(terminal.scroll_right, 7);
        assert_eq!(terminal.pending_reply(), b"\x1b[?69;2$y");
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

        let mut overlong_palette = b"\x1b]4;25;#".to_vec();
        overlong_palette.extend(std::iter::repeat_n(b'f', MAX_OSC_BYTES + 1));
        overlong_palette.push(0x07);
        terminal.feed(&overlong_palette);
        assert!(!terminal.palette_overridden[25]);
    }

    #[test]
    fn xterm_erase_saved_lines_preserves_the_visible_screen() {
        let mut terminal = Terminal::new(2, 5).unwrap();
        terminal.feed(b"one\r\ntwo\r\nthree");
        assert_eq!(terminal.history_rows(), 1);
        assert_eq!(text(&terminal, 0), "two  ");
        assert_eq!(text(&terminal, 1), "three");
        let old_epoch = terminal.history_origin_epoch();

        terminal.feed(b"\x1b[3J");

        assert_eq!(terminal.history_rows(), 0);
        assert!(terminal.history_origin_epoch() > old_epoch);
        assert_eq!(text(&terminal, 0), "two  ");
        assert_eq!(text(&terminal, 1), "three");
    }

    #[test]
    fn device_queries_publish_bounded_ordered_replies() {
        let mut terminal = Terminal::new(24, 80).unwrap();
        terminal.feed(b"\x1b[c\x1b[5n\x1b[4;9H\x1b[6n\x1bZ");
        assert_eq!(
            terminal.pending_reply(),
            b"\x1b[?1;2c\x1b[0n\x1b[4;9R\x1b[?1;2c"
        );
        terminal.consume_reply(10);
        assert_eq!(terminal.pending_reply(), b"n\x1b[4;9R\x1b[?1;2c");
        terminal.consume_reply(usize::MAX);
        assert!(terminal.pending_reply().is_empty());
    }

    #[test]
    fn xterm_device_size_and_mode_queries_are_bounded_and_exact() {
        let mut terminal = Terminal::new(24, 80).unwrap();
        terminal.feed(
            b"\x1b[>c\x1b[18t\
              \x1b[?2004$p\x1b[?2004h\x1b[?2004$p\
              \x1b[4$p\x1b[4h\x1b[4$p\x1b[9999$p",
        );
        assert_eq!(
            terminal.pending_reply(),
            b"\x1b[>0;1;0c\x1b[8;24;80t\
              \x1b[?2004;2$y\x1b[?2004;1$y\
              \x1b[4;2$y\x1b[4;1$y\x1b[9999;0$y"
        );
    }

    #[test]
    fn malformed_device_query_forms_do_not_impersonate_a_terminal() {
        let mut terminal = Terminal::new(24, 80).unwrap();
        terminal.feed(
            b"\x1b[>1c\x1b[=c\x1b[0;1c\x1b[0$c\x1b[18;19t\x1b[$4p\
              \x1b[4;4;4;4;4;4;4;4;4;4;4;4;4;4;4;4;4$p",
        );
        assert!(terminal.pending_reply().is_empty());
    }

    #[test]
    fn reply_ring_preserves_order_across_wrap_and_rejects_partial_overflow() {
        let mut terminal = Terminal::new(24, 80).unwrap();
        terminal.queue_reply(&vec![b'x'; MAX_REPLY_BYTES]);
        terminal.consume_reply(MAX_REPLY_BYTES - 2);
        terminal.queue_reply(b"abcdef");
        assert_eq!(terminal.pending_reply(), b"xx");
        terminal.consume_reply(2);
        assert_eq!(terminal.pending_reply(), b"abcdef");
        terminal.queue_reply(&vec![b'y'; MAX_REPLY_BYTES]);
        terminal.consume_reply(6);
        assert!(terminal.pending_reply().is_empty());
    }

    #[test]
    fn cursor_reports_follow_dec_origin_mode() {
        let mut terminal = Terminal::new(10, 20).unwrap();
        terminal.feed(b"\x1b[3;8r\x1b[?6h\x1b[4;7H\x1b[6n\x1b[?6n");
        assert_eq!(terminal.cursor(), (5, 6));
        assert_eq!(terminal.pending_reply(), b"\x1b[4;7R\x1b[?4;7R");
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
            u32::from_le_bytes(
                output[DAMAGE_HEADER_SIZE..DAMAGE_HEADER_SIZE + 4]
                    .try_into()
                    .unwrap()
            ),
            u32::from(b'A')
        );
        assert_eq!(u32::from_le_bytes(output[32..36].try_into().unwrap()), 0);
        assert_eq!(u32::from_le_bytes(output[36..40].try_into().unwrap()), 0);
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
