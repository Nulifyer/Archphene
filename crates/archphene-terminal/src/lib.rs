#![forbid(unsafe_code)]

pub const MIN_ROWS: u16 = 2;
pub const MAX_ROWS: u16 = 200;
pub const MIN_COLUMNS: u16 = 2;
pub const MAX_COLUMNS: u16 = 400;
pub const DAMAGE_PROTOCOL_VERSION: u32 = 1;
pub const DAMAGE_HEADER_SIZE: usize = 32;
pub const DAMAGE_CELL_SIZE: usize = 8;
pub const MAX_DAMAGE_BYTES: usize =
    DAMAGE_HEADER_SIZE + MAX_ROWS as usize * MAX_COLUMNS as usize * DAMAGE_CELL_SIZE;
const DAMAGE_MAGIC: u32 = u32::from_le_bytes(*b"ATRM");
const MAX_CSI_PARAMETERS: usize = 16;
const MAX_STRING_BYTES: usize = 8 * 1024;
const DEFAULT_FOREGROUND: u8 = 7;
const DEFAULT_BACKGROUND: u8 = 0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Cell {
    pub codepoint: u32,
    pub foreground: u8,
    pub background: u8,
    pub attributes: u8,
}

impl Cell {
    const fn blank() -> Self {
        Self {
            codepoint: 0x20,
            foreground: DEFAULT_FOREGROUND,
            background: DEFAULT_BACKGROUND,
            attributes: 0,
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
    foreground: u8,
    background: u8,
    attributes: u8,
    dirty_start: u16,
    dirty_end: u16,
    revision: u64,
}

impl Terminal {
    pub fn new(rows: u16, columns: u16) -> Result<Self, TerminalError> {
        validate_size(rows, columns)?;
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
        let flags = u32::from(self.cursor_visible);
        output[20..24].copy_from_slice(&flags.to_le_bytes());
        output[24..32].copy_from_slice(&self.revision.to_le_bytes());
        let mut offset = DAMAGE_HEADER_SIZE;
        for row in dirty_start..dirty_end {
            for column in 0..self.columns {
                let cell = self.cells[self.index(row, column)];
                output[offset..offset + 4].copy_from_slice(&cell.codepoint.to_le_bytes());
                output[offset + 4] = cell.foreground;
                output[offset + 5] = cell.background;
                output[offset + 6] = cell.attributes;
                output[offset + 7] = 1;
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
                self.cursor_column = ((self.cursor_column / 8 + 1) * 8).min(self.columns - 1);
            }
            0x20..=0x7e => self.put_codepoint(u32::from(byte)),
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
            b'7' => {
                self.saved_row = self.cursor_row;
                self.saved_column = self.cursor_column;
            }
            b'8' => {
                self.cursor_row = self.saved_row.min(self.rows - 1);
                self.cursor_column = self.saved_column.min(self.columns - 1);
            }
            b'D' => self.line_feed(),
            b'E' => {
                self.cursor_column = 0;
                self.line_feed();
            }
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
        match final_byte {
            b'A' => self.cursor_row = self.cursor_row.saturating_sub(self.parameter(0, 1)),
            b'B' => {
                self.cursor_row = self
                    .cursor_row
                    .saturating_add(self.parameter(0, 1))
                    .min(self.rows - 1);
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
            b'G' => {
                self.cursor_column = self.parameter(0, 1).saturating_sub(1).min(self.columns - 1)
            }
            b'H' | b'f' => {
                self.cursor_row = self.parameter(0, 1).saturating_sub(1).min(self.rows - 1);
                self.cursor_column = self.parameter(1, 1).saturating_sub(1).min(self.columns - 1);
            }
            b'J' => self.erase_display(self.csi_parameters[0]),
            b'K' => self.erase_line(self.csi_parameters[0]),
            b'm' => self.select_graphics(),
            b'r' => {
                let top = self.parameter(0, 1).saturating_sub(1).min(self.rows - 1);
                let bottom = self
                    .parameter(1, self.rows)
                    .saturating_sub(1)
                    .min(self.rows - 1);
                if top < bottom {
                    self.scroll_top = top;
                    self.scroll_bottom = bottom;
                    self.cursor_row = top;
                    self.cursor_column = 0;
                }
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
                25 => {
                    if self.cursor_visible != enabled {
                        self.cursor_visible = enabled;
                        self.mark_dirty(self.cursor_row);
                    }
                }
                47 => self.set_alternate_screen(enabled, false),
                1047 | 1049 => self.set_alternate_screen(enabled, enabled),
                1048 if enabled => {
                    self.saved_row = self.cursor_row;
                    self.saved_column = self.cursor_column;
                }
                1048 => {
                    self.cursor_row = self.saved_row.min(self.rows - 1);
                    self.cursor_column = self.saved_column.min(self.columns - 1);
                }
                _ => {}
            }
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
        if self.wrap_pending {
            self.cursor_column = 0;
            self.line_feed();
            self.wrap_pending = false;
        }
        let index = self.index(self.cursor_row, self.cursor_column);
        self.cells[index] = Cell {
            codepoint,
            foreground: self.foreground,
            background: self.background,
            attributes: self.attributes,
        };
        self.mark_dirty(self.cursor_row);
        if self.cursor_column + 1 >= self.columns {
            self.wrap_pending = true;
        } else {
            self.cursor_column += 1;
        }
    }

    fn line_feed(&mut self) {
        if self.cursor_row == self.scroll_bottom {
            self.scroll_up();
        } else {
            self.cursor_row = (self.cursor_row + 1).min(self.rows - 1);
        }
    }

    fn scroll_up(&mut self) {
        let columns = usize::from(self.columns);
        let top = self.index(self.scroll_top, 0);
        let bottom = self.index(self.scroll_bottom, 0);
        self.cells.copy_within(top + columns..bottom + columns, top);
        self.cells[bottom..bottom + columns].fill(Cell::blank());
        self.mark_dirty_range(self.scroll_top, self.scroll_bottom + 1);
    }

    fn erase_display(&mut self, mode: u16) {
        match mode {
            0 => {
                let start = self.index(self.cursor_row, self.cursor_column);
                self.cells[start..].fill(Cell::blank());
                self.mark_dirty_range(self.cursor_row, self.rows);
            }
            1 => {
                let end = self.index(self.cursor_row, self.cursor_column) + 1;
                self.cells[..end].fill(Cell::blank());
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
            0 => self.cells[start + column..end].fill(Cell::blank()),
            1 => self.cells[start..=start + column].fill(Cell::blank()),
            2 => self.cells[start..end].fill(Cell::blank()),
            _ => return,
        }
        self.mark_dirty(self.cursor_row);
    }

    fn select_graphics(&mut self) {
        for index in 0..self.csi_count.max(1) {
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
                30..=37 => self.foreground = self.csi_parameters[index] as u8 - 30,
                39 => self.foreground = DEFAULT_FOREGROUND,
                40..=47 => self.background = self.csi_parameters[index] as u8 - 40,
                49 => self.background = DEFAULT_BACKGROUND,
                90..=97 => self.foreground = self.csi_parameters[index] as u8 - 82,
                100..=107 => self.background = self.csi_parameters[index] as u8 - 92,
                _ => {}
            }
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
        self.foreground = DEFAULT_FOREGROUND;
        self.background = DEFAULT_BACKGROUND;
        self.attributes = 0;
        self.utf8_remaining = 0;
        self.mark_dirty_range(0, self.rows);
    }

    fn index(&self, row: u16, column: u16) -> usize {
        usize::from(row) * usize::from(self.columns) + usize::from(column)
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
    (replacement, source_row)
}

fn validate_size(rows: u16, columns: u16) -> Result<(), TerminalError> {
    if !(MIN_ROWS..=MAX_ROWS).contains(&rows) || !(MIN_COLUMNS..=MAX_COLUMNS).contains(&columns) {
        return Err(TerminalError::InvalidSize);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(terminal: &Terminal, row: u16) -> String {
        (0..terminal.columns())
            .map(|column| {
                char::from_u32(terminal.cell(row, column).unwrap().codepoint).unwrap_or('\u{fffd}')
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
        let mut output = [0_u8; 256];
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
        let mut output = [0_u8; 128];
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
