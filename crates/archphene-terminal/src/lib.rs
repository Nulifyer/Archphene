#![forbid(unsafe_code)]

pub const MIN_ROWS: u16 = 2;
pub const MAX_ROWS: u16 = 200;
pub const MIN_COLUMNS: u16 = 2;
pub const MAX_COLUMNS: u16 = 400;
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
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ParserState {
    Ground,
    Escape,
    Csi,
    Osc,
    OscEscape,
}

pub struct Terminal {
    rows: u16,
    columns: u16,
    cells: Vec<Cell>,
    cursor_row: u16,
    cursor_column: u16,
    wrap_pending: bool,
    saved_row: u16,
    saved_column: u16,
    scroll_top: u16,
    scroll_bottom: u16,
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
}

impl Terminal {
    pub fn new(rows: u16, columns: u16) -> Result<Self, TerminalError> {
        validate_size(rows, columns)?;
        Ok(Self {
            rows,
            columns,
            cells: vec![Cell::blank(); usize::from(rows) * usize::from(columns)],
            cursor_row: 0,
            cursor_column: 0,
            wrap_pending: false,
            saved_row: 0,
            saved_column: 0,
            scroll_top: 0,
            scroll_bottom: rows - 1,
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
        let mut replacement = vec![Cell::blank(); usize::from(rows) * usize::from(columns)];
        let copied_rows = rows.min(self.rows);
        let copied_columns = columns.min(self.columns);
        for row in 0..copied_rows {
            let old = self.index(row, 0);
            let new = usize::from(row) * usize::from(columns);
            replacement[new..new + usize::from(copied_columns)]
                .copy_from_slice(&self.cells[old..old + usize::from(copied_columns)]);
        }
        self.cells = replacement;
        self.rows = rows;
        self.columns = columns;
        self.cursor_row = self.cursor_row.min(rows - 1);
        self.cursor_column = self.cursor_column.min(columns - 1);
        self.wrap_pending = false;
        self.scroll_top = 0;
        self.scroll_bottom = rows - 1;
        self.dirty_start = 0;
        self.dirty_end = rows;
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
        self.cells.fill(Cell::blank());
        self.cursor_row = 0;
        self.cursor_column = 0;
        self.wrap_pending = false;
        self.scroll_top = 0;
        self.scroll_bottom = self.rows - 1;
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
    }
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
    }

    #[test]
    fn bounded_control_strings_never_escape_into_the_grid() {
        let mut terminal = Terminal::new(2, 12).unwrap();
        terminal.take_dirty_rows();
        terminal.feed(b"ok\x1b]0;secret\x07\x1bPignored\x1b\\!");
        assert_eq!(text(&terminal, 0), "ok!         ");
        assert_eq!(terminal.take_dirty_rows(), Some((0, 1)));
    }
}
