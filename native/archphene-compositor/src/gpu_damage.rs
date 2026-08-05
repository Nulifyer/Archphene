#[derive(Clone, Copy)]
pub(crate) enum SourceFormat {
    Argb8888,
    Xrgb8888,
}

#[derive(Clone, Copy)]
pub(crate) struct Damage {
    pub(crate) x: i32,
    pub(crate) y: i32,
    pub(crate) width: i32,
    pub(crate) height: i32,
}

pub(crate) fn stage_rgba_damage(
    source_width: i32,
    source_height: i32,
    source: &[u8],
    format: SourceFormat,
    damage: Damage,
    staging: &mut [u8],
) -> Result<usize, ()> {
    if source_width <= 0
        || source_height <= 0
        || damage.x < 0
        || damage.y < 0
        || damage.width <= 0
        || damage.height <= 0
        || damage
            .x
            .checked_add(damage.width)
            .is_none_or(|right| right > source_width)
        || damage
            .y
            .checked_add(damage.height)
            .is_none_or(|bottom| bottom > source_height)
    {
        return Err(());
    }
    let width = source_width as usize;
    let height = source_height as usize;
    let damage_width = damage.width as usize;
    let damage_height = damage.height as usize;
    let source_bytes = width
        .checked_mul(height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or(())?;
    let required = damage_width
        .checked_mul(damage_height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or(())?;
    if source.len() != source_bytes || required > staging.len() {
        return Err(());
    }
    for row in 0..damage_height {
        let source_start = ((damage.y as usize + row) * width + damage.x as usize) * 4;
        let destination_start = row * damage_width * 4;
        for column in 0..damage_width {
            let source_pixel = source_start + column * 4;
            let destination_pixel = destination_start + column * 4;
            staging[destination_pixel] = source[source_pixel + 2];
            staging[destination_pixel + 1] = source[source_pixel + 1];
            staging[destination_pixel + 2] = source[source_pixel];
            staging[destination_pixel + 3] = match format {
                SourceFormat::Argb8888 => source[source_pixel + 3],
                SourceFormat::Xrgb8888 => u8::MAX,
            };
        }
    }
    Ok(required)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stages_only_the_bounded_damage_rectangle() {
        let source = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
        let mut staging = [0xaa; 16];
        let length = stage_rgba_damage(
            2,
            2,
            &source,
            SourceFormat::Argb8888,
            Damage {
                x: 1,
                y: 0,
                width: 1,
                height: 2,
            },
            &mut staging,
        )
        .expect("valid damage");
        assert_eq!(length, 8);
        assert_eq!(&staging[..8], &[7, 6, 5, 8, 15, 14, 13, 16]);
        assert_eq!(&staging[8..], &[0xaa; 8]);
    }

    #[test]
    fn forces_xrgb_alpha_and_rejects_unsafe_bounds() {
        let source = [1, 2, 3, 0];
        let mut staging = [0; 4];
        assert_eq!(
            stage_rgba_damage(
                1,
                1,
                &source,
                SourceFormat::Xrgb8888,
                Damage {
                    x: 0,
                    y: 0,
                    width: 1,
                    height: 1,
                },
                &mut staging,
            ),
            Ok(4),
        );
        assert_eq!(staging, [3, 2, 1, 255]);
        assert!(
            stage_rgba_damage(
                1,
                1,
                &source,
                SourceFormat::Argb8888,
                Damage {
                    x: 1,
                    y: 0,
                    width: 1,
                    height: 1,
                },
                &mut staging,
            )
            .is_err()
        );
    }
}
